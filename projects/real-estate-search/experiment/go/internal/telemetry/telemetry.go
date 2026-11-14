package telemetry

import (
	"context"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

var (
	tpMu           sync.Mutex
	tracerProvider *sdktrace.TracerProvider
	isInitialized  bool
)

func getServiceName() string {
	if name := strings.TrimSpace(os.Getenv("OTEL_SERVICE_NAME")); name != "" {
		return name
	}
	return "real-estate-api"
}

// TelemetryOptions holds configuration options for initializing OpenTelemetry.
type TelemetryOptions struct {
	ServiceName    string
	SpanProcessors []sdktrace.SpanProcessor
}

func resolveTracesEndpoint() (endpoint string, path string, isInsecure bool) {
	raw := strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"))
	if raw == "" {
		base := strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
		if base != "" {
			raw = strings.TrimRight(base, "/") + "/v1/traces"
		}
	}

	if raw == "" {
		return "", "", false
	}

	u, err := url.Parse(raw)
	if err != nil {
		return raw, "/v1/traces", true
	}

	insecure := u.Scheme == "http"
	return u.Host, u.Path, insecure
}

// InitTelemetry initializes OpenTelemetry tracing with W3C propagation. Idempotent.
func InitTelemetry(ctx context.Context, opts ...func(*TelemetryOptions)) error {
	tpMu.Lock()
	defer tpMu.Unlock()

	if isInitialized {
		return nil
	}

	options := TelemetryOptions{
		ServiceName: getServiceName(),
	}
	for _, opt := range opts {
		opt(&options)
	}

	res, err := resource.Merge(
		resource.Default(),
		resource.NewWithAttributes(
			semconv.SchemaURL,
			semconv.ServiceName(options.ServiceName),
		),
	)
	if err != nil {
		return err
	}

	var processors []sdktrace.SpanProcessor
	processors = append(processors, options.SpanProcessors...)

	host, tracesPath, insecure := resolveTracesEndpoint()
	if host != "" {
		clientOpts := []otlptracehttp.Option{
			otlptracehttp.WithEndpoint(host),
			otlptracehttp.WithURLPath(tracesPath),
		}
		if insecure {
			clientOpts = append(clientOpts, otlptracehttp.WithInsecure())
		}
		exporter, err := otlptracehttp.New(ctx, clientOpts...)
		if err == nil {
			processors = append(processors, sdktrace.NewBatchSpanProcessor(exporter))
		}
	}

	var tpOpts []sdktrace.TracerProviderOption
	tpOpts = append(tpOpts, sdktrace.WithResource(res))
	for _, sp := range processors {
		tpOpts = append(tpOpts, sdktrace.WithSpanProcessor(sp))
	}

	tp := sdktrace.NewTracerProvider(tpOpts...)
	otel.SetTracerProvider(tp)

	propagator := propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	)
	otel.SetTextMapPropagator(propagator)

	tracerProvider = tp
	isInitialized = true
	return nil
}

// ShutdownTelemetry flushes and shuts down the TracerProvider cleanly. Idempotent.
func ShutdownTelemetry(ctx context.Context) error {
	tpMu.Lock()
	defer tpMu.Unlock()

	if !isInitialized {
		return nil
	}

	var err error
	if tracerProvider != nil {
		err = tracerProvider.Shutdown(ctx)
		tracerProvider = nil
	}
	isInitialized = false
	return err
}

// GetTracer returns the shared OpenTelemetry tracer for real-estate-api.
func GetTracer() trace.Tracer {
	return otel.GetTracerProvider().Tracer("real-estate-api")
}

// CaseInsensitiveCarrier implements propagation.TextMapCarrier for case-insensitive header lookup.
type CaseInsensitiveCarrier map[string]string

func (c CaseInsensitiveCarrier) Get(key string) string {
	return c[strings.ToLower(key)]
}

func (c CaseInsensitiveCarrier) Set(key string, value string) {
	c[strings.ToLower(key)] = value
}

func (c CaseInsensitiveCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for k := range c {
		keys = append(keys, k)
	}
	return keys
}

// ExtractTraceContext extracts incoming W3C traceparent/tracestate context case-insensitively.
func ExtractTraceContext(ctx context.Context, headers http.Header) context.Context {
	carrier := make(CaseInsensitiveCarrier)
	for key, values := range headers {
		if len(values) > 0 {
			carrier[strings.ToLower(key)] = values[0]
		}
	}
	return otel.GetTextMapPropagator().Extract(ctx, carrier)
}

// WithSpan executes an operation inside an active span, recording errors automatically.
func WithSpan(ctx context.Context, name string, kind trace.SpanKind, attrs []trace.SpanStartOption, fn func(ctx context.Context, span trace.Span) error) error {
	tracer := GetTracer()
	opts := append([]trace.SpanStartOption{trace.WithSpanKind(kind)}, attrs...)
	ctx, span := tracer.Start(ctx, name, opts...)
	defer span.End()

	err := fn(ctx, span)
	if err != nil {
		span.RecordError(err)
	}
	return err
}
