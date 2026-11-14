package graphql_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/graphql-go/graphql/language/ast"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/domain"
	gql "real-estate-search/internal/graphql"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/search"
	"real-estate-search/internal/telemetry"
)

type failingRepo struct {
	repository.Repository
}

func (f *failingRepo) CountMatchingListings(_ context.Context, _ domain.ValidatedSearchInput) (int, error) {
	return 0, fmt.Errorf("Database connection lost")
}

func setupTestHandler(t *testing.T) (*gql.Handler, repository.Repository) {
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	source := "SEED"
	state := "TX"
	zip := "78701"
	bathrooms := 2.0
	sqft := 1550
	lat := 30.2638
	lon := -97.7443
	status := "active"

	listings := []domain.Listing{
		{
			ID:             "LST-001",
			Source:         &source,
			Address:        "101 Congress Ave",
			City:           "Austin",
			State:          &state,
			Zip:            &zip,
			Price:          450000,
			Bedrooms:       3,
			Bathrooms:      &bathrooms,
			Sqft:           &sqft,
			Latitude:       &lat,
			Longitude:      &lon,
			Status:         &status,
			Description:    "Condo downtown with pool and gym.",
			ListedDate:     "2026-10-20T00:00:00.000Z",
			RelevanceScore: 0.95,
		},
		{
			ID:             "LST-002",
			Source:         &source,
			Address:        "202 Barton Springs Rd",
			City:           "Austin",
			State:          &state,
			Zip:            &zip,
			Price:          550000,
			Bedrooms:       3,
			Bathrooms:      &bathrooms,
			Sqft:           &sqft,
			Latitude:       &lat,
			Longitude:      &lon,
			Status:         &status,
			Description:    "Ranch home with heated pool.",
			ListedDate:     "2026-10-25T00:00:00.000Z",
			RelevanceScore: 0.90,
		},
	}

	if _, err := repo.InsertOrBackfillListings(ctx, listings); err != nil {
		t.Fatalf("failed to insert test listings: %v", err)
	}

	svc := search.NewService(repo)
	schema, err := gql.BuildSchema(svc)
	if err != nil {
		t.Fatalf("failed to build schema: %v", err)
	}

	return gql.NewHandler(schema), repo
}

func TestGraphQL_SearchListings_Success(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchListingsSuccess {
				listings {
					internalId
					id
					source
					address
					city
					state
					zip
					price
					bedrooms
					bathrooms
					sqft
					latitude
					longitude
					status
					description
					listedDate
					relevanceScore
				}
				pagination { page pageSize totalResults totalPages }
			}
			... on SearchError { code message field }
			... on SearchOutOfRangeError { totalPages }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{
				"city":         "Austin",
				"targetBudget": 500000,
				"page":         1,
				"pageSize":     5,
			},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d. Body: %s", w.Code, w.Body.String())
	}

	var res struct {
		Data struct {
			SearchListings struct {
				Typename string `json:"__typename"`
				Listings []struct {
					ID             string   `json:"id"`
					Source         *string  `json:"source"`
					State          *string  `json:"state"`
					Bathrooms      *float64 `json:"bathrooms"`
					Sqft           *int     `json:"sqft"`
					RelevanceScore float64  `json:"relevanceScore"`
				} `json:"listings"`
				Pagination struct {
					Page         int `json:"page"`
					PageSize     int `json:"pageSize"`
					TotalResults int `json:"totalResults"`
					TotalPages   int `json:"totalPages"`
				} `json:"pagination"`
			} `json:"searchListings"`
		} `json:"data"`
		Errors []any `json:"errors"`
	}

	if err := json.Unmarshal(w.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if len(res.Errors) > 0 {
		t.Fatalf("unexpected errors: %+v", res.Errors)
	}

	if res.Data.SearchListings.Typename != "SearchListingsSuccess" {
		t.Errorf("expected SearchListingsSuccess, got %s", res.Data.SearchListings.Typename)
	}
	if len(res.Data.SearchListings.Listings) != 2 {
		t.Errorf("expected 2 listings, got %d", len(res.Data.SearchListings.Listings))
	}
	if res.Data.SearchListings.Pagination.TotalResults != 2 {
		t.Errorf("expected totalResults 2, got %d", res.Data.SearchListings.Pagination.TotalResults)
	}
}

func TestGraphQL_SearchListings_InlineLiterals(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search {
		searchListings(input: {
			city: "Austin"
			minPrice: 100000
			maxPrice: 900000
			minBedrooms: 2
			targetBudget: 500000
			page: 1
			pageSize: 5
		}) {
			__typename
			... on SearchListingsSuccess {
				listings { id city }
				pagination { page pageSize totalResults totalPages }
			}
			... on SearchError { code message field }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{"query": query})
	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d. Body: %s", w.Code, w.Body.String())
	}

	if !strings.Contains(w.Body.String(), "SearchListingsSuccess") {
		t.Errorf("expected SearchListingsSuccess in body: %s", w.Body.String())
	}
}

func TestGraphQL_SearchListings_EmptyResults(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchListingsSuccess {
				listings { id }
				pagination { totalResults totalPages }
			}
			... on SearchError { code message }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{"city": "NonExistentCity"},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d", w.Code)
	}

	if !strings.Contains(w.Body.String(), `"totalResults":0`) {
		t.Errorf("expected totalResults 0 in body: %s", w.Body.String())
	}
}

func TestGraphQL_SearchListings_ValidationErrorUnion(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchError { code message field }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{"minPrice": "not-a-number"},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 for domain validation error, got %d", w.Code)
	}

	body := w.Body.String()
	if !strings.Contains(body, "SearchValidationError") || !strings.Contains(body, "VALIDATION_ERROR") || !strings.Contains(body, "minPrice") {
		t.Errorf("expected SearchValidationError in body, got: %s", body)
	}
}

func TestGraphQL_SearchListings_OutOfRangeErrorUnion(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchError { code message field }
			... on SearchOutOfRangeError { totalPages }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{
				"city":     "Austin",
				"page":     999,
				"pageSize": 5,
			},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 for out of range error, got %d", w.Code)
	}

	body := w.Body.String()
	if !strings.Contains(body, "SearchOutOfRangeError") || !strings.Contains(body, "OUT_OF_RANGE") || !strings.Contains(body, `"totalPages":1`) {
		t.Errorf("expected SearchOutOfRangeError in body, got: %s", body)
	}
}

func TestGraphQL_SearchListings_DatabaseInternalError(t *testing.T) {
	failingRepo := &failingRepo{Repository: repository.NewMemoryRepository()}
	svc := search.NewService(failingRepo)
	schema, err := gql.BuildSchema(svc)
	if err != nil {
		t.Fatalf("failed to build schema: %v", err)
	}
	handler := gql.NewHandler(schema)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchError { code message field }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{"city": "Austin"},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 for internal resolver error, got %d", w.Code)
	}

	body := w.Body.String()
	if !strings.Contains(body, `"searchListings":null`) {
		t.Errorf("expected null searchListings, got: %s", body)
	}
	if !strings.Contains(body, "INTERNAL_SERVER_ERROR") || !strings.Contains(body, "An unexpected internal error occurred.") {
		t.Errorf("expected sanitized INTERNAL_SERVER_ERROR in errors, got: %s", body)
	}
	// Confirm internal DB error detail was NOT leaked to client
	if strings.Contains(body, "Database connection lost") {
		t.Errorf("internal database error detail was leaked: %s", body)
	}
}

func TestGraphQL_MethodNotAllowed(t *testing.T) {
	handler, _ := setupTestHandler(t)
	req := httptest.NewRequest(http.MethodGet, "/graphql", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusMethodNotAllowed {
		t.Errorf("expected 405 Method Not Allowed, got %d", w.Code)
	}
}

type errReader struct{}

func (errReader) Read(p []byte) (n int, err error) {
	return 0, fmt.Errorf("read error")
}

func TestGraphQL_BodyReadError(t *testing.T) {
	handler, _ := setupTestHandler(t)
	req := httptest.NewRequest(http.MethodPost, "/graphql", errReader{})
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400 Bad Request, got %d", w.Code)
	}
}

func TestGraphQL_UnknownFieldPreExecutionError(t *testing.T) {
	handler, _ := setupTestHandler(t)
	reqBody, _ := json.Marshal(map[string]any{
		"query": `query { nonExistentField }`,
	})
	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for unknown field, got %d", w.Code)
	}
}

func TestGraphQL_CORS_OptionsAndAllowedOrigins(t *testing.T) {
	handler, _ := setupTestHandler(t)

	// Allowed origin
	reqAllowed := httptest.NewRequest(http.MethodOptions, "/graphql", nil)
	reqAllowed.Header.Set("Origin", "http://localhost:5173")
	reqAllowed.Header.Set("Access-Control-Request-Method", "POST")
	wAllowed := httptest.NewRecorder()
	handler.ServeHTTP(wAllowed, reqAllowed)

	if wAllowed.Code != http.StatusOK && wAllowed.Code != http.StatusNoContent {
		t.Errorf("expected 200/204 for allowed origin, got %d", wAllowed.Code)
	}
	if wAllowed.Header().Get("Access-Control-Allow-Origin") != "http://localhost:5173" {
		t.Errorf("expected allow origin header set to http://localhost:5173, got %s", wAllowed.Header().Get("Access-Control-Allow-Origin"))
	}
	allowHeaders := strings.ToLower(wAllowed.Header().Get("Access-Control-Allow-Headers"))
	if !strings.Contains(allowHeaders, "traceparent") || !strings.Contains(allowHeaders, "tracestate") {
		t.Errorf("expected traceparent and tracestate in allowed headers: %s", allowHeaders)
	}

	// Untrusted origin
	reqBlocked := httptest.NewRequest(http.MethodOptions, "/graphql", nil)
	reqBlocked.Header.Set("Origin", "https://evil.com")
	reqBlocked.Header.Set("Access-Control-Request-Method", "POST")
	wBlocked := httptest.NewRecorder()
	handler.ServeHTTP(wBlocked, reqBlocked)

	if wBlocked.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Errorf("expected empty Access-Control-Allow-Origin for untrusted origin, got %s", wBlocked.Header().Get("Access-Control-Allow-Origin"))
	}
}

func TestGraphQL_ComplexityRules_RootFieldsAndDepth(t *testing.T) {
	handler, _ := setupTestHandler(t)

	// 1. More than 10 root fields
	aliased := make([]string, 11)
	for i := 0; i < 11; i++ {
		aliased[i] = fmt.Sprintf("q%d: searchListings { __typename }", i)
	}
	tooManyAliasesQuery := "query TooManyAliases { " + strings.Join(aliased, " ") + " }"
	reqBody1, _ := json.Marshal(map[string]any{"query": tooManyAliasesQuery})

	req1 := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody1))
	req1.Header.Set("Content-Type", "application/json")
	w1 := httptest.NewRecorder()
	handler.ServeHTTP(w1, req1)

	if w1.Code != http.StatusBadRequest {
		t.Errorf("expected HTTP 400 for root field limit, got %d", w1.Code)
	}
	if !strings.Contains(w1.Body.String(), "maximum allowed root field count") {
		t.Errorf("expected root field limit error in body: %s", w1.Body.String())
	}

	// 2. Query depth > 15
	nested := "__typename"
	for i := 0; i < 16; i++ {
		nested = fmt.Sprintf("... on SearchListingsResult { %s }", nested)
	}
	tooDeepQuery := fmt.Sprintf("query TooDeep { searchListings { %s } }", nested)
	reqBody2, _ := json.Marshal(map[string]any{"query": tooDeepQuery})

	req2 := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody2))
	req2.Header.Set("Content-Type", "application/json")
	w2 := httptest.NewRecorder()
	handler.ServeHTTP(w2, req2)

	if w2.Code != http.StatusBadRequest {
		t.Errorf("expected HTTP 400 for depth limit, got %d", w2.Code)
	}
	if !strings.Contains(w2.Body.String(), "maximum allowed depth") {
		t.Errorf("expected depth limit error in body: %s", w2.Body.String())
	}
}

func TestJSONScalar_ASTParsingAndSecurity(t *testing.T) {
	// Primitives
	if val := gql.ParseLiteral(&ast.StringValue{Value: "hello"}); val != "hello" {
		t.Errorf("expected 'hello', got %v", val)
	}
	if val := gql.ParseLiteral(&ast.IntValue{Value: "42"}); val != 42 {
		t.Errorf("expected 42, got %v", val)
	}
	if val := gql.ParseLiteral(&ast.FloatValue{Value: "42.5"}); val != 42.5 {
		t.Errorf("expected 42.5, got %v", val)
	}
	if val := gql.ParseLiteral(&ast.BooleanValue{Value: true}); val != true {
		t.Errorf("expected true, got %v", val)
	}
	if val := gql.ParseLiteral(&ast.EnumValue{Value: "CUSTOM_ENUM"}); val != "CUSTOM_ENUM" {
		t.Errorf("expected CUSTOM_ENUM, got %v", val)
	}
	if val := gql.ParseLiteral(nil); val != nil {
		t.Errorf("expected nil for nil astNode, got %v", val)
	}

	// List
	listVal := gql.ParseLiteral(&ast.ListValue{
		Values: []ast.Value{
			&ast.IntValue{Value: "1"},
			&ast.IntValue{Value: "2"},
		},
	})
	if slice, ok := listVal.([]any); !ok || len(slice) != 2 || slice[0] != 1 || slice[1] != 2 {
		t.Errorf("unexpected list: %v", listVal)
	}

	// Object
	objVal := gql.ParseLiteral(&ast.ObjectValue{
		Fields: []*ast.ObjectField{
			{
				Name:  &ast.Name{Value: "key"},
				Value: &ast.StringValue{Value: "val"},
			},
		},
	})
	if m, ok := objVal.(map[string]any); !ok || m["key"] != "val" {
		t.Errorf("unexpected object: %v", objVal)
	}

	// Depth limit rejection (> 5 levels)
	var deepNode ast.Value = &ast.IntValue{Value: "1"}
	for i := 0; i < 7; i++ {
		deepNode = &ast.ListValue{Values: []ast.Value{deepNode}}
	}
	assertPanics(t, func() { gql.ParseLiteral(deepNode) }, "Input literal nesting exceeds maximum allowed depth.")

	// Prototype pollution key rejection
	for _, forbidden := range []string{"__proto__", "constructor", "prototype"} {
		badObj := &ast.ObjectValue{
			Fields: []*ast.ObjectField{
				{
					Name:  &ast.Name{Value: forbidden},
					Value: &ast.StringValue{Value: "attack"},
				},
			},
		}
		assertPanics(t, func() { gql.ParseLiteral(badObj) }, "Invalid input object key.")
	}

	// Serialize and ParseValue pass-through
	if res := gql.JSONScalar.Serialize(123); res != 123 {
		t.Errorf("expected serialize pass-through 123, got %v", res)
	}
	if res := gql.JSONScalar.ParseValue("abc"); res != "abc" {
		t.Errorf("expected parseValue pass-through 'abc', got %v", res)
	}
}

func assertPanics(t *testing.T, fn func(), expectedMessage string) {
	t.Helper()
	defer func() {
		r := recover()
		if r == nil {
			t.Errorf("expected panic containing %q, but code did not panic", expectedMessage)
			return
		}
		var msg string
		if err, ok := r.(error); ok {
			msg = err.Error()
		} else {
			msg = fmt.Sprint(r)
		}
		if !strings.Contains(msg, expectedMessage) {
			t.Errorf("expected panic containing %q, got %q", expectedMessage, msg)
		}
	}()
	fn()
}

func TestGraphQL_TraceparentPropagation(t *testing.T) {
	ctx := context.Background()
	exporter := tracetest.NewInMemoryExporter()
	processor := sdktrace.NewSimpleSpanProcessor(exporter)

	_ = telemetry.InitTelemetry(ctx, func(opts *telemetry.TelemetryOptions) {
		opts.SpanProcessors = []sdktrace.SpanProcessor{processor}
	})
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	handler, _ := setupTestHandler(t)

	incomingTraceID := "11111111222222223333333344444444"
	incomingSpanID := "5555555566666666"
	traceparent := fmt.Sprintf("00-%s-%s-01", incomingTraceID, incomingSpanID)

	query := `query Search { searchListings { __typename } }`
	reqBody, _ := json.Marshal(map[string]any{"query": query})
	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("TraceParent", traceparent)

	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d", w.Code)
	}

	spans := exporter.GetSpans()
	var serverSpan *tracetest.SpanStub
	for _, s := range spans {
		if s.Name == "POST /graphql" {
			spanCopy := s
			serverSpan = &spanCopy
			break
		}
	}

	if serverSpan == nil {
		t.Fatalf("expected POST /graphql server span")
	}
	if serverSpan.SpanKind != trace.SpanKindServer {
		t.Errorf("expected SpanKind.SERVER, got %v", serverSpan.SpanKind)
	}
	if serverSpan.SpanContext.TraceID().String() != incomingTraceID {
		t.Errorf("expected traceId %s, got %s", incomingTraceID, serverSpan.SpanContext.TraceID().String())
	}
	if serverSpan.Parent.SpanID().String() != incomingSpanID {
		t.Errorf("expected parentSpanId %s, got %s", incomingSpanID, serverSpan.Parent.SpanID().String())
	}
}
