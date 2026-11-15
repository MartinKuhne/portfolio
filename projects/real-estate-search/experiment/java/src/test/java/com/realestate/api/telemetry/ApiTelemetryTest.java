package com.realestate.api.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApiTelemetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ApiTelemetry.shutdownGlobal();
    }

    @Test
    void resolvesConfigFromEnvironmentMapWithDefaultsAndOverrides() {
        TelemetryConfig defaults = TelemetryConfig.fromMap(Map.of());
        assertThat(defaults.serviceName()).isEqualTo("real-estate-api");
        assertThat(defaults.tracesEndpoint()).isNull();
        assertThat(defaults.logsEndpoint()).isNull();

        TelemetryConfig baseOnly = TelemetryConfig.fromMap(Map.of(
            "OTEL_SERVICE_NAME", " custom-svc ",
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://jaeger:4318///"
        ));
        assertThat(baseOnly.serviceName()).isEqualTo("custom-svc");
        assertThat(baseOnly.tracesEndpoint()).isEqualTo("http://jaeger:4318/v1/traces");
        assertThat(baseOnly.logsEndpoint()).isNull();

        TelemetryConfig explicit = TelemetryConfig.fromMap(Map.of(
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://jaeger:4318/custom/traces",
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://ignored:4318",
            "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", "http://collector:4318/v1/logs"
        ));
        assertThat(explicit.tracesEndpoint()).isEqualTo("http://jaeger:4318/custom/traces");
        assertThat(explicit.logsEndpoint()).isEqualTo("http://collector:4318/v1/logs");
    }

    @Test
    void normalizesHeadersCaseInsensitivelyAndExtractsW3cTraceparent() {
        try (ApiTelemetry telemetry = ApiTelemetry.create(TelemetryConfig.fromMap(Map.of()))) {
            String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
            String parentSpanId = "00f067aa0ba902b7";
            String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";

            assertThat(HeaderTraceExtractor.normalizeHeaders(null)).isEmpty();
            assertThat(HeaderTraceExtractor.normalizeMultiHeaders(null)).isEmpty();

            Map<String, String> multi = HeaderTraceExtractor.normalizeMultiHeaders(Map.of(
                "TRACEPARENT", List.of(traceparent),
                "X-Multi", List.of("a", "b")
            ));
            assertThat(multi).containsEntry("traceparent", traceparent).containsEntry("x-multi", "a,b");

            Context extracted = telemetry.extractTraceContext(Map.of(
                "TraceParent", traceparent,
                "TraceState", "congo=t61rcWkgMzE"
            ));
            SpanContext spanContext = Span.fromContext(extracted).getSpanContext();
            assertThat(spanContext.isValid()).isTrue();
            assertThat(spanContext.getTraceId()).isEqualTo(traceId);
            assertThat(spanContext.getSpanId()).isEqualTo(parentSpanId);
        }
    }

    @Test
    void initializesAndShutsDownGlobalTelemetryIdempotently() {
        ApiTelemetry.shutdownGlobal();
        ApiTelemetry first = ApiTelemetry.initGlobal(TelemetryConfig.fromMap(Map.of()));
        ApiTelemetry second = ApiTelemetry.initGlobal(TelemetryConfig.fromMap(Map.of()));
        assertThat(second).isSameAs(first);
        assertThat(ApiTelemetry.getGlobal()).isSameAs(first);

        ApiTelemetry.shutdownGlobal();
        ApiTelemetry.shutdownGlobal();
        assertThat(ApiTelemetry.getGlobal()).isNotSameAs(first);
    }

    @Test
    void emitsStructuredJsonLogsSynchronizedWithSpanEventsAndExportsOtlp() throws Exception {
        List<String> receivedPaths = new CopyOnWriteArrayList<>();
        HttpServer mockCollector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockCollector.createContext("/", exchange -> {
            receivedPaths.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        mockCollector.start();
        String baseUrl = "http://127.0.0.1:" + mockCollector.getAddress().getPort();

        try {
            ByteArrayOutputStream logBuffer = new ByteArrayOutputStream();
            PrintStream printOut = new PrintStream(logBuffer, true, StandardCharsets.UTF_8);
            InMemorySpanExporter spanExporter = InMemorySpanExporter.create();

            TelemetryConfig config = new TelemetryConfig(
                "custom-api-service",
                baseUrl + "/v1/traces",
                baseUrl + "/v1/logs",
                List.of(SimpleSpanProcessor.create(spanExporter)),
                List.of(),
                printOut
            );

            try (ApiTelemetry telemetry = ApiTelemetry.create(config)) {
                // Log outside an active span -> null traceId/spanId/traceFlags
                telemetry.log(
                    Severity.INFO,
                    "INFO",
                    "outside.span",
                    Attributes.of(AttributeKey.stringKey("detail"), "none")
                );

                // Log inside an active span
                String outcome = telemetry.withSpan(
                    "env.test.span",
                    SpanKind.INTERNAL,
                    Attributes.of(AttributeKey.booleanKey("test"), true),
                    span -> {
                        telemetry.log(
                            Severity.INFO,
                            "INFO",
                            "env.test.log",
                            Attributes.of(AttributeKey.booleanKey("ok"), true)
                        );
                        return "done";
                    }
                );
                assertThat(outcome).isEqualTo("done");

                assertThatThrownBy(() -> telemetry.withSpan("failing.span", Attributes.empty(), span -> {
                    throw new IllegalStateException("boom");
                })).isInstanceOf(IllegalStateException.class);

                List<SpanData> finishedSpans = spanExporter.getFinishedSpanItems();
                SpanData testSpan = finishedSpans.stream()
                    .filter(s -> s.getName().equals("env.test.span"))
                    .findFirst()
                    .orElseThrow();
                assertThat(testSpan.getEvents()).anyMatch(e -> e.getName().equals("env.test.log"));

                SpanData failedSpan = finishedSpans.stream()
                    .filter(s -> s.getName().equals("failing.span"))
                    .findFirst()
                    .orElseThrow();
                assertThat(failedSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(failedSpan.getEvents()).anyMatch(e -> e.getName().equals("exception"));
            }

            List<String> lines = logBuffer.toString(StandardCharsets.UTF_8).lines().toList();
            assertThat(lines).hasSize(2);

            Map<String, Object> outsideLog = MAPPER.readValue(lines.get(0), new TypeReference<>() {});
            assertThat(outsideLog.get("service")).isEqualTo("custom-api-service");
            assertThat(outsideLog.get("body")).isEqualTo("outside.span");
            assertThat(outsideLog.get("traceId")).isNull();
            assertThat(outsideLog.get("spanId")).isNull();
            assertThat(outsideLog.get("traceFlags")).isNull();

            Map<String, Object> insideLog = MAPPER.readValue(lines.get(1), new TypeReference<>() {});
            assertThat(insideLog.get("body")).isEqualTo("env.test.log");
            assertThat(insideLog.get("severityText")).isEqualTo("INFO");
            assertThat(insideLog.get("severityNumber")).isEqualTo(9);
            assertThat((String) insideLog.get("traceId")).matches("^[0-9a-f]{32}$");
            assertThat((String) insideLog.get("spanId")).matches("^[0-9a-f]{16}$");
            assertThat(insideLog.get("traceFlags")).isEqualTo(1);
            assertThat((String) insideLog.get("timestamp")).endsWith("Z");

            assertThat(receivedPaths).contains("/v1/traces", "/v1/logs");
        } finally {
            mockCollector.stop(0);
        }
    }
}
