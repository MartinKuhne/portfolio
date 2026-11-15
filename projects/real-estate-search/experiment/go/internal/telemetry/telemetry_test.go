package telemetry_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"

	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/telemetry"
)

func TestTelemetryLifecycle_Idempotent(t *testing.T) {
	ctx := context.Background()
	_ = telemetry.ShutdownTelemetry(ctx)

	if err := telemetry.InitTelemetry(ctx); err != nil {
		t.Fatalf("InitTelemetry failed: %v", err)
	}
	// Second init should be no-op
	if err := telemetry.InitTelemetry(ctx); err != nil {
		t.Fatalf("Second InitTelemetry failed: %v", err)
	}

	if err := telemetry.ShutdownTelemetry(ctx); err != nil {
		t.Fatalf("ShutdownTelemetry failed: %v", err)
	}
	// Second shutdown should be no-op
	if err := telemetry.ShutdownTelemetry(ctx); err != nil {
		t.Fatalf("Second ShutdownTelemetry failed: %v", err)
	}
}

func TestExtractTraceContext_CaseInsensitive(t *testing.T) {
	ctx := context.Background()
	_ = telemetry.InitTelemetry(ctx)
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	traceID := "4bf92f3577b34da6a3ce929d0e0e4736"
	parentSpanID := "00f067aa0ba902b7"
	traceparent := "00-" + traceID + "-" + parentSpanID + "-01"

	headers := http.Header{}
	headers.Set("TraceParent", traceparent)
	headers.Set("TraceState", "congo=t61rcWkgMzE")

	extractedCtx := telemetry.ExtractTraceContext(ctx, headers)
	spanCtx := trace.SpanContextFromContext(extractedCtx)

	if !spanCtx.IsValid() {
		t.Fatalf("expected valid extracted span context")
	}
	if spanCtx.TraceID().String() != traceID {
		t.Errorf("expected traceID %s, got %s", traceID, spanCtx.TraceID().String())
	}
	if spanCtx.SpanID().String() != parentSpanID {
		t.Errorf("expected spanID %s, got %s", parentSpanID, spanCtx.SpanID().String())
	}
}

func TestStructuredLogging_WithTraceCorrelation(t *testing.T) {
	ctx := context.Background()
	exporter := tracetest.NewInMemoryExporter()
	processor := sdktrace.NewSimpleSpanProcessor(exporter)

	_ = telemetry.InitTelemetry(ctx, func(opts *telemetry.TelemetryOptions) {
		opts.SpanProcessors = []sdktrace.SpanProcessor{processor}
	})
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	var buf bytes.Buffer
	telemetry.SetLogOutput(&buf)

	tracer := telemetry.GetTracer()
	ctx, span := tracer.Start(ctx, "test.operation")
	telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "test.message", map[string]any{
		"custom.key": "custom.value",
	})
	span.End()

	output := buf.String()
	if output == "" {
		t.Fatalf("expected log output, got empty")
	}

	var entry telemetry.LogEntry
	if err := json.Unmarshal([]byte(output), &entry); err != nil {
		t.Fatalf("failed to unmarshal log entry: %v", err)
	}

	if entry.Service != "real-estate-api" {
		t.Errorf("expected service 'real-estate-api', got %s", entry.Service)
	}
	if entry.SeverityText != "INFO" || entry.SeverityNumber != telemetry.SeverityInfo {
		t.Errorf("unexpected severity: %+v", entry)
	}
	if entry.Body != "test.message" {
		t.Errorf("expected body 'test.message', got %s", entry.Body)
	}
	if entry.TraceID == nil || *entry.TraceID != span.SpanContext().TraceID().String() {
		t.Errorf("expected correlated traceId %s, got %v", span.SpanContext().TraceID().String(), entry.TraceID)
	}
	if entry.SpanID == nil || *entry.SpanID != span.SpanContext().SpanID().String() {
		t.Errorf("expected correlated spanId %s, got %v", span.SpanContext().SpanID().String(), entry.SpanID)
	}
	if entry.TraceFlags == nil || *entry.TraceFlags != 1 {
		t.Errorf("expected integer traceFlags 1, got %v", entry.TraceFlags)
	}
	if !strings.Contains(output, `"traceFlags":1`) {
		t.Errorf("expected raw JSON to contain integer \"traceFlags\":1, got: %s", output)
	}

	// Verify span event correlation
	spans := exporter.GetSpans()
	if len(spans) == 0 {
		t.Fatalf("expected finished span in exporter")
	}
	if len(spans[0].Events) == 0 || spans[0].Events[0].Name != "test.message" {
		t.Errorf("expected span event 'test.message', got: %+v", spans[0].Events)
	}
}

func TestStructuredLogging_OutsideSpan(t *testing.T) {
	var buf bytes.Buffer
	telemetry.SetLogOutput(&buf)

	telemetry.LogApi(context.Background(), telemetry.SeverityWarn, "WARN", "untraced.event", nil)

	var entry telemetry.LogEntry
	if err := json.Unmarshal([]byte(buf.String()), &entry); err != nil {
		t.Fatalf("failed to unmarshal entry: %v", err)
	}

	if entry.TraceID != nil || entry.SpanID != nil || entry.TraceFlags != nil {
		t.Errorf("expected nil traceId, spanId, and traceFlags outside active span, got: %v, %v, %v", entry.TraceID, entry.SpanID, entry.TraceFlags)
	}
	if entry.Body != "untraced.event" {
		t.Errorf("expected body untraced.event, got %s", entry.Body)
	}
}

func TestTelemetry_OtlpEndpointPreservesDefaultPath(t *testing.T) {
	var mu sync.Mutex
	var paths []string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		paths = append(paths, r.URL.Path)
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	defer ts.Close()

	// Explicit OTEL_EXPORTER_OTLP_TRACES_ENDPOINT omitting path must default to /v1/traces
	os.Setenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", ts.URL)
	defer os.Unsetenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")

	ctx := context.Background()
	_ = telemetry.ShutdownTelemetry(ctx)
	if err := telemetry.InitTelemetry(ctx); err != nil {
		t.Fatalf("InitTelemetry failed: %v", err)
	}

	_, span := telemetry.GetTracer().Start(ctx, "otlp.default.path")
	span.End()

	if err := telemetry.ShutdownTelemetry(ctx); err != nil {
		t.Fatalf("ShutdownTelemetry failed: %v", err)
	}

	mu.Lock()
	defer mu.Unlock()
	found := false
	for _, p := range paths {
		if p == "/v1/traces" {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("expected exported traces to hit /v1/traces when path omitted, got paths: %v", paths)
	}
}

func TestExtractTraceContext_MultiValueHeadersJoined(t *testing.T) {
	ctx := context.Background()
	_ = telemetry.InitTelemetry(ctx)
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	traceID := "4bf92f3577b34da6a3ce929d0e0e4736"
	parentSpanID := "00f067aa0ba902b7"
	traceparent := "00-" + traceID + "-" + parentSpanID + "-01"

	headers := http.Header{}
	headers.Add("TraceParent", traceparent)
	headers.Add("TraceState", "rojo=00f067aa0ba902b7")
	headers.Add("TraceState", "congo=t61rcWkgMzE")

	extractedCtx := telemetry.ExtractTraceContext(ctx, headers)
	spanCtx := trace.SpanContextFromContext(extractedCtx)
	ts := spanCtx.TraceState().String()
	if !strings.Contains(ts, "rojo=00f067aa0ba902b7") || !strings.Contains(ts, "congo=t61rcWkgMzE") {
		t.Errorf("expected multi-value tracestate joined with comma, got %q", ts)
	}

	// Verify carrier also works with propagator.Inject
	carrier := make(telemetry.CaseInsensitiveCarrier)
	propagation.TraceContext{}.Inject(extractedCtx, carrier)
	if carrier.Get("tracestate") == "" {
		t.Errorf("expected injected tracestate in carrier")
	}
}

func TestTelemetry_OtlpEndpointResolution(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer ts.Close()

	os.Setenv("OTEL_SERVICE_NAME", "custom-service-name")
	os.Setenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", ts.URL+"/v1/traces")
	defer func() {
		os.Unsetenv("OTEL_SERVICE_NAME")
		os.Unsetenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")
	}()

	ctx := context.Background()
	_ = telemetry.ShutdownTelemetry(ctx)
	if err := telemetry.InitTelemetry(ctx); err != nil {
		t.Fatalf("InitTelemetry with endpoint failed: %v", err)
	}
	_ = telemetry.ShutdownTelemetry(ctx)
}

func TestCaseInsensitiveCarrier_Methods(t *testing.T) {
	carrier := make(telemetry.CaseInsensitiveCarrier)
	carrier.Set("TraceParent", "00-123-456-01")
	if carrier.Get("traceparent") != "00-123-456-01" {
		t.Errorf("expected carrier get '00-123-456-01', got %s", carrier.Get("traceparent"))
	}
	keys := carrier.Keys()
	if len(keys) != 1 || keys[0] != "traceparent" {
		t.Errorf("expected keys [traceparent], got %v", keys)
	}
}

func TestWithSpan_Helper(t *testing.T) {
	ctx := context.Background()
	exporter := tracetest.NewInMemoryExporter()
	processor := sdktrace.NewSimpleSpanProcessor(exporter)

	_ = telemetry.InitTelemetry(ctx, func(opts *telemetry.TelemetryOptions) {
		opts.SpanProcessors = []sdktrace.SpanProcessor{processor}
	})
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	err := telemetry.WithSpan(ctx, "helper.span", trace.SpanKindInternal, nil, func(ctx context.Context, span trace.Span) error {
		return nil
	})
	if err != nil {
		t.Fatalf("WithSpan returned error: %v", err)
	}

	spans := exporter.GetSpans()
	if len(spans) == 0 || spans[0].Name != "helper.span" {
		t.Errorf("expected finished helper.span, got: %+v", spans)
	}
}
