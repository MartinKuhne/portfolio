package com.realestate.api.telemetry;

import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * Configuration for OpenTelemetry tracing and structured JSON logging.
 */
public record TelemetryConfig(
    String serviceName,
    String tracesEndpoint,
    String logsEndpoint,
    List<SpanProcessor> additionalSpanProcessors,
    List<LogRecordProcessor> additionalLogProcessors,
    PrintStream consoleOut
) {
    public static final String DEFAULT_SERVICE_NAME = "real-estate-api";

    public TelemetryConfig {
        serviceName = (serviceName == null || serviceName.isBlank()) ? DEFAULT_SERVICE_NAME : serviceName.trim();
        tracesEndpoint = blankToNull(tracesEndpoint);
        logsEndpoint = blankToNull(logsEndpoint);
        additionalSpanProcessors = additionalSpanProcessors == null ? List.of() : List.copyOf(additionalSpanProcessors);
        additionalLogProcessors = additionalLogProcessors == null ? List.of() : List.copyOf(additionalLogProcessors);
        consoleOut = consoleOut == null ? System.out : consoleOut;
    }

    /**
     * Builds configuration from process environment variables ({@link System#getenv()}).
     */
    public static TelemetryConfig fromEnv() {
        return fromMap(System.getenv());
    }

    /**
     * Builds configuration from an environment variable map.
     */
    public static TelemetryConfig fromMap(Map<String, String> env) {
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        String serviceName = safeEnv.get("OTEL_SERVICE_NAME");
        String tracesEndpoint = resolveTracesEndpoint(
            safeEnv.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"),
            safeEnv.get("OTEL_EXPORTER_OTLP_ENDPOINT")
        );
        String logsEndpoint = blankToNull(safeEnv.get("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT"));
        return new TelemetryConfig(serviceName, tracesEndpoint, logsEndpoint, List.of(), List.of(), System.out);
    }

    /**
     * Resolves the OTLP HTTP traces endpoint following ADR-008 semantics.
     */
    public static String resolveTracesEndpoint(String explicitTracesEndpoint, String baseEndpoint) {
        String explicit = blankToNull(explicitTracesEndpoint);
        if (explicit != null) {
            return explicit;
        }
        String base = blankToNull(baseEndpoint);
        if (base != null) {
            String trimmed = base.replaceAll("/+$", "");
            return trimmed + "/v1/traces";
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
