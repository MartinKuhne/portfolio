package graphql

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"

	"github.com/graphql-go/graphql"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/telemetry"
)

const maxRequestBodyBytes = 1 << 20 // 1 MiB

type requestPayload struct {
	Query         string         `json:"query"`
	OperationName string         `json:"operationName,omitempty"`
	Variables     map[string]any `json:"variables,omitempty"`
}

type sanitizedErrorExtension struct {
	Code string `json:"code"`
}

type sanitizedError struct {
	Message    string                   `json:"message"`
	Extensions *sanitizedErrorExtension `json:"extensions,omitempty"`
}

type graphQLResponse struct {
	Data   any              `json:"data"`
	Errors []sanitizedError `json:"errors,omitempty"`
}

// Handler handles GraphQL HTTP POST and CORS preflight OPTIONS requests.
type Handler struct {
	schema        graphql.Schema
	allowedOrigin map[string]bool
}

func getAllowedOrigins() map[string]bool {
	raw := os.Getenv("CORS_ORIGIN")
	if strings.TrimSpace(raw) == "" {
		raw = "http://localhost:8080,http://localhost:5173"
	}

	origins := make(map[string]bool)
	for _, part := range strings.Split(raw, ",") {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			origins[trimmed] = true
		}
	}
	return origins
}

// NewHandler initializes a GraphQL HTTP handler.
func NewHandler(schema graphql.Schema) *Handler {
	return &Handler{
		schema:        schema,
		allowedOrigin: getAllowedOrigins(),
	}
}

func (h *Handler) handleCORS(w http.ResponseWriter, r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin != "" && h.allowedOrigin[origin] {
		w.Header().Add("Vary", "Origin")
		w.Header().Set("Access-Control-Allow-Origin", origin)
		w.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "content-type, traceparent, tracestate")
	}

	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return true
	}
	return false
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	ctx := telemetry.ExtractTraceContext(r.Context(), r.Header)
	method := strings.ToUpper(r.Method)
	path := r.URL.Path
	if path == "" {
		path = "/graphql"
	}

	tracer := telemetry.GetTracer()
	ctx, span := tracer.Start(ctx, method+" "+path,
		trace.WithSpanKind(trace.SpanKindServer),
		trace.WithAttributes(
			attribute.String("http.request.method", method),
			attribute.String("url.path", path),
		),
	)
	defer span.End()

	if h.handleCORS(w, r) {
		span.SetAttributes(attribute.Int("http.response.status_code", http.StatusOK))
		return
	}

	if r.Method != http.MethodPost {
		span.SetAttributes(attribute.Int("http.response.status_code", http.StatusMethodNotAllowed))
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	w.Header().Set("Content-Type", "application/json")

	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBodyBytes)
	bodyBytes, err := io.ReadAll(r.Body)
	if err != nil {
		status := http.StatusBadRequest
		errMsg := "Failed to read request body."
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			status = http.StatusRequestEntityTooLarge
			errMsg = "Request body exceeds maximum allowed size."
		}

		span.SetAttributes(attribute.Int("http.response.status_code", status))
		telemetry.LogApi(ctx, telemetry.SeverityWarn, "WARN", "http.request.rejected", map[string]any{
			"http.request.method":       method,
			"url.path":                  path,
			"http.response.status_code": status,
		})
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(graphQLResponse{
			Errors: []sanitizedError{{Message: errMsg}},
		})
		return
	}

	var req requestPayload
	if err := json.Unmarshal(bodyBytes, &req); err != nil {
		span.SetAttributes(attribute.Int("http.response.status_code", http.StatusBadRequest))
		telemetry.LogApi(ctx, telemetry.SeverityWarn, "WARN", "http.request.rejected", map[string]any{
			"http.request.method":       method,
			"url.path":                  path,
			"http.response.status_code": http.StatusBadRequest,
		})
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(graphQLResponse{
			Errors: []sanitizedError{{Message: "Invalid JSON payload: " + err.Error()}},
		})
		return
	}

	// Validate query complexity, fragment cycles, literal security, and strict input types
	if err := ValidateGraphQLRequest(req.Query, req.Variables); err != nil {
		span.SetAttributes(attribute.Int("http.response.status_code", http.StatusBadRequest))
		telemetry.LogApi(ctx, telemetry.SeverityWarn, "WARN", "http.request.rejected", map[string]any{
			"http.request.method":       method,
			"url.path":                  path,
			"http.response.status_code": http.StatusBadRequest,
		})
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(graphQLResponse{
			Errors: []sanitizedError{{
				Message: err.Error(),
				Extensions: &sanitizedErrorExtension{
					Code: "BAD_USER_INPUT",
				},
			}},
		})
		return
	}

	serverCtx := &ServerContext{}
	ctx = context.WithValue(ctx, serverContextKey, serverCtx)

	result := graphql.Do(graphql.Params{
		Schema:         h.schema,
		RequestString:  req.Query,
		OperationName:  req.OperationName,
		VariableValues: req.Variables,
		Context:        ctx,
	})

	// Pre-execution errors (e.g. syntax, schema validation)
	if len(result.Errors) > 0 && result.Data == nil && !serverCtx.HasInternalError {
		span.SetAttributes(attribute.Int("http.response.status_code", http.StatusBadRequest))
		telemetry.LogApi(ctx, telemetry.SeverityWarn, "WARN", "http.request.rejected", map[string]any{
			"http.request.method":       method,
			"url.path":                  path,
			"http.response.status_code": http.StatusBadRequest,
		})
		w.WriteHeader(http.StatusBadRequest)
		errs := make([]sanitizedError, len(result.Errors))
		for i, e := range result.Errors {
			errs[i] = sanitizedError{
				Message:    e.Message,
				Extensions: &sanitizedErrorExtension{Code: "BAD_USER_INPUT"},
			}
		}
		_ = json.NewEncoder(w).Encode(graphQLResponse{Errors: errs})
		return
	}

	span.SetAttributes(attribute.Int("http.response.status_code", http.StatusOK))

	if serverCtx.HasInternalError || len(result.Errors) > 0 {
		span.SetStatus(codes.Error, "GraphQL INTERNAL_SERVER_ERROR")
		telemetry.LogApi(ctx, telemetry.SeverityError, "ERROR", "http.request.failed", map[string]any{
			"http.request.method":       method,
			"url.path":                  path,
			"http.response.status_code": http.StatusOK,
		})

		errCount := len(result.Errors)
		if errCount == 0 {
			errCount = 1
		}
		sanitizedErrs := make([]sanitizedError, errCount)
		for i := 0; i < errCount; i++ {
			sanitizedErrs[i] = sanitizedError{
				Message: "An unexpected internal error occurred.",
				Extensions: &sanitizedErrorExtension{
					Code: "INTERNAL_SERVER_ERROR",
				},
			}
		}

		data := result.Data
		if data == nil {
			data = map[string]any{"searchListings": nil}
		}

		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(graphQLResponse{
			Data:   data,
			Errors: sanitizedErrs,
		})
		return
	}

	if serverCtx.HasDomainError {
		telemetry.LogApi(ctx, telemetry.SeverityWarn, "WARN", "http.request.rejected", map[string]any{
			"http.request.method":       method,
			"url.path":                  path,
			"http.response.status_code": http.StatusOK,
		})
	} else {
		telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "http.request.completed", map[string]any{
			"http.request.method":       method,
			"url.path":                  path,
			"http.response.status_code": http.StatusOK,
		})
	}

	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(result)
}
