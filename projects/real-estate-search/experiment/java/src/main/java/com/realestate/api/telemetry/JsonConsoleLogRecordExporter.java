package com.realestate.api.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exports OpenTelemetry log records as single-line structured JSON to stdout (or a configured stream).
 */
public final class JsonConsoleLogRecordExporter implements LogRecordExporter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String serviceName;
    private final PrintStream out;

    public JsonConsoleLogRecordExporter(String serviceName) {
        this(serviceName, System.out);
    }

    public JsonConsoleLogRecordExporter(String serviceName, PrintStream out) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
        this.out = Objects.requireNonNull(out, "out must not be null");
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
        for (LogRecordData record : logs) {
            String json = formatRecord(record);
            if (json != null) {
                out.println(json);
            }
        }
        return CompletableResultCode.ofSuccess();
    }

    String formatRecord(LogRecordData record) {
        long nanos = record.getTimestampEpochNanos();
        if (nanos == 0L) {
            nanos = record.getObservedTimestampEpochNanos();
        }
        Instant instant = nanos > 0L ? Instant.ofEpochSecond(0L, nanos) : Instant.now();

        Severity severity = record.getSeverity();
        int severityNumber = severity != null ? severity.getSeverityNumber() : Severity.UNDEFINED_SEVERITY_NUMBER.getSeverityNumber();
        String severityText = record.getSeverityText();
        if (severityText == null || severityText.isBlank()) {
            severityText = severity != null && severity != Severity.UNDEFINED_SEVERITY_NUMBER
                ? severity.name()
                : "UNSPECIFIED";
        }

        String body = record.getBodyValue() != null ? record.getBodyValue().asString() : null;

        SpanContext spanContext = record.getSpanContext();
        boolean hasValidSpan = spanContext != null && spanContext.isValid();
        String traceId = hasValidSpan ? spanContext.getTraceId() : null;
        String spanId = hasValidSpan ? spanContext.getSpanId() : null;
        Integer traceFlags = hasValidSpan ? (spanContext.getTraceFlags().asByte() & 0xFF) : null;

        Map<String, Object> attributes = new LinkedHashMap<>();
        if (record.getAttributes() != null) {
            record.getAttributes().forEach((AttributeKey<?> key, Object value) ->
                attributes.put(key.getKey(), value)
            );
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", instant.toString());
        entry.put("service", serviceName);
        entry.put("severityNumber", severityNumber);
        entry.put("severityText", severityText);
        entry.put("body", body);
        entry.put("traceId", traceId);
        entry.put("spanId", spanId);
        entry.put("traceFlags", traceFlags);
        entry.put("attributes", attributes);

        try {
            return OBJECT_MAPPER.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            System.err.println("Failed to serialize structured OTel log: " + e.getMessage());
            return null;
        }
    }

    @Override
    public CompletableResultCode flush() {
        out.flush();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        out.flush();
        return CompletableResultCode.ofSuccess();
    }
}
