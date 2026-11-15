package telemetry

import (
	"context"
	"encoding/json"
	"io"
	"os"
	"sync"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type SeverityNumber int

const (
	SeverityDebug SeverityNumber = 5
	SeverityInfo  SeverityNumber = 9
	SeverityWarn  SeverityNumber = 13
	SeverityError SeverityNumber = 17
)

// LogEntry represents the structured single-line JSON OpenTelemetry log record.
type LogEntry struct {
	Timestamp      string         `json:"timestamp"`
	Service        string         `json:"service"`
	SeverityNumber SeverityNumber `json:"severityNumber"`
	SeverityText   string         `json:"severityText"`
	Body           string         `json:"body"`
	TraceID        *string        `json:"traceId"`
	SpanID         *string        `json:"spanId"`
	TraceFlags     *int           `json:"traceFlags"`
	Attributes     map[string]any `json:"attributes,omitempty"`
}

var (
	logMu     sync.Mutex
	logOutput io.Writer = os.Stdout
)

// SetLogOutput overrides the destination writer for structured JSON logs (used in tests).
func SetLogOutput(w io.Writer) {
	logMu.Lock()
	defer logMu.Unlock()
	if w == nil {
		logOutput = os.Stdout
		return
	}
	logOutput = w
}

// LogApi logs a structured trace-correlated JSON line and adds an event to the active span.
func LogApi(ctx context.Context, severityNumber SeverityNumber, severityText, body string, attrs map[string]any) {
	now := time.Now().UTC().Format("2006-01-02T15:04:05.000Z")
	service := getServiceName()

	var traceIDStr, spanIDStr *string
	var traceFlagsVal *int
	span := trace.SpanFromContext(ctx)
	if span.SpanContext().IsValid() {
		sc := span.SpanContext()
		tid := sc.TraceID().String()
		sid := sc.SpanID().String()
		tf := int(sc.TraceFlags())
		traceIDStr = &tid
		spanIDStr = &sid
		traceFlagsVal = &tf

		// Add event to active span
		var otelAttrs []attribute.KeyValue
		for k, v := range attrs {
			switch val := v.(type) {
			case string:
				otelAttrs = append(otelAttrs, attribute.String(k, val))
			case int:
				otelAttrs = append(otelAttrs, attribute.Int(k, val))
			case int64:
				otelAttrs = append(otelAttrs, attribute.Int64(k, val))
			case float64:
				otelAttrs = append(otelAttrs, attribute.Float64(k, val))
			case bool:
				otelAttrs = append(otelAttrs, attribute.Bool(k, val))
			}
		}
		span.AddEvent(body, trace.WithAttributes(otelAttrs...))
	}

	entry := LogEntry{
		Timestamp:      now,
		Service:        service,
		SeverityNumber: severityNumber,
		SeverityText:   severityText,
		Body:           body,
		TraceID:        traceIDStr,
		SpanID:         spanIDStr,
		TraceFlags:     traceFlagsVal,
		Attributes:     attrs,
	}

	data, err := json.Marshal(entry)
	if err == nil {
		logMu.Lock()
		_, _ = logOutput.Write(append(data, '\n'))
		logMu.Unlock()
	}
}
