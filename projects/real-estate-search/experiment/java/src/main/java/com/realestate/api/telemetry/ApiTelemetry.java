package com.realestate.api.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Manages OpenTelemetry tracing, W3C TraceContext propagation, and trace-correlated JSON logging.
 */
public final class ApiTelemetry implements AutoCloseable {

    public static final String INSTRUMENTATION_NAME = "real-estate-api";
    private static final String ATTR_SERVICE_NAME = "service.name";

    private static volatile ApiTelemetry globalInstance;
    private static final Object GLOBAL_LOCK = new Object();

    private final OpenTelemetry openTelemetry;
    private final SdkTracerProvider tracerProvider;
    private final SdkLoggerProvider loggerProvider;
    private final Tracer tracer;
    private final Logger logger;

    private ApiTelemetry(
        OpenTelemetry openTelemetry,
        SdkTracerProvider tracerProvider,
        SdkLoggerProvider loggerProvider
    ) {
        this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
        this.tracerProvider = tracerProvider;
        this.loggerProvider = loggerProvider;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
        this.logger = openTelemetry.getLogsBridge().get(INSTRUMENTATION_NAME);
    }

    /**
     * Creates an isolated {@link ApiTelemetry} instance from {@link TelemetryConfig}.
     */
    public static ApiTelemetry create(TelemetryConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        Resource resource = Resource.getDefault().merge(
            Resource.builder().put(ATTR_SERVICE_NAME, config.serviceName()).build()
        );

        SdkTracerProviderBuilder tracerBuilder = SdkTracerProvider.builder().setResource(resource);
        for (SpanProcessor processor : config.additionalSpanProcessors()) {
            tracerBuilder.addSpanProcessor(processor);
        }
        if (config.tracesEndpoint() != null) {
            OtlpHttpSpanExporter traceExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(config.tracesEndpoint())
                .build();
            tracerBuilder.addSpanProcessor(
                BatchSpanProcessor.builder(traceExporter)
                    .setScheduleDelay(Duration.ofMillis(500))
                    .build()
            );
        }
        SdkTracerProvider sdkTracerProvider = tracerBuilder.build();

        SdkLoggerProviderBuilder loggerBuilder = SdkLoggerProvider.builder().setResource(resource);
        loggerBuilder.addLogRecordProcessor(
            SimpleLogRecordProcessor.create(
                new JsonConsoleLogRecordExporter(config.serviceName(), config.consoleOut())
            )
        );
        for (LogRecordProcessor processor : config.additionalLogProcessors()) {
            loggerBuilder.addLogRecordProcessor(processor);
        }
        if (config.logsEndpoint() != null) {
            OtlpHttpLogRecordExporter logExporter = OtlpHttpLogRecordExporter.builder()
                .setEndpoint(config.logsEndpoint())
                .build();
            loggerBuilder.addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter));
        }
        SdkLoggerProvider sdkLoggerProvider = loggerBuilder.build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(sdkTracerProvider)
            .setLoggerProvider(sdkLoggerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

        return new ApiTelemetry(sdk, sdkTracerProvider, sdkLoggerProvider);
    }

    /**
     * Creates a no-op {@link ApiTelemetry} instance suitable for lightweight unit tests.
     */
    public static ApiTelemetry noop() {
        return new ApiTelemetry(OpenTelemetry.noop(), null, null);
    }

    /**
     * Initializes and registers the global {@link ApiTelemetry} singleton idempotently.
     */
    public static ApiTelemetry initGlobal(TelemetryConfig config) {
        synchronized (GLOBAL_LOCK) {
            if (globalInstance != null) {
                return globalInstance;
            }
            ApiTelemetry created = create(config);
            GlobalOpenTelemetry.resetForTest();
            GlobalOpenTelemetry.set(created.openTelemetry);
            globalInstance = created;
            return created;
        }
    }

    /**
     * Returns the initialized global {@link ApiTelemetry}, or {@link #noop()} if uninitialized.
     */
    public static ApiTelemetry getGlobal() {
        ApiTelemetry instance = globalInstance;
        return instance != null ? instance : noop();
    }

    /**
     * Flushes and shuts down the global {@link ApiTelemetry} singleton idempotently.
     */
    public static void shutdownGlobal() {
        synchronized (GLOBAL_LOCK) {
            if (globalInstance == null) {
                return;
            }
            ApiTelemetry toClose = globalInstance;
            globalInstance = null;
            try {
                toClose.close();
            } finally {
                GlobalOpenTelemetry.resetForTest();
            }
        }
    }

    public OpenTelemetry openTelemetry() {
        return openTelemetry;
    }

    public Tracer tracer() {
        return tracer;
    }

    /**
     * Extracts W3C {@code traceparent}/{@code tracestate} context from case-insensitive HTTP headers.
     */
    public Context extractTraceContext(Map<String, String> headers) {
        return HeaderTraceExtractor.extractContext(openTelemetry.getPropagators(), headers);
    }

    /**
     * Emits a trace-correlated structured log record and appends a corresponding span event.
     */
    public void log(Severity severity, String severityText, String message, Attributes attributes) {
        Context activeContext = Context.current();
        Span activeSpan = Span.fromContext(activeContext);
        SpanContext spanContext = activeSpan.getSpanContext();

        Attributes safeAttributes = attributes != null ? attributes : Attributes.empty();
        if (spanContext.isValid()) {
            activeSpan.addEvent(message, safeAttributes);
        }

        AttributesBuilder enrichedBuilder = Attributes.builder().putAll(safeAttributes);
        if (spanContext.isValid()) {
            enrichedBuilder.put("traceId", spanContext.getTraceId());
            enrichedBuilder.put("spanId", spanContext.getSpanId());
            enrichedBuilder.put("traceFlags", (long) (spanContext.getTraceFlags().asByte() & 0xFF));
        }

        logger.logRecordBuilder()
            .setTimestamp(Instant.now())
            .setContext(activeContext)
            .setSeverity(severity)
            .setSeverityText(severityText)
            .setBody(message)
            .setAllAttributes(enrichedBuilder.build())
            .emit();
    }

    /**
     * Runs an operation inside a new active child span, recording uncaught exceptions and closing the span.
     */
    public <T> T withSpan(String name, SpanKind kind, Attributes attributes, Function<Span, T> fn) {
        Span span = tracer.spanBuilder(name)
            .setSpanKind(kind)
            .setAllAttributes(attributes != null ? attributes : Attributes.empty())
            .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return fn.apply(span);
        } catch (RuntimeException | Error err) {
            span.recordException(err);
            span.setStatus(StatusCode.ERROR, safeMessage(err));
            throw err;
        } finally {
            span.end();
        }
    }

    /**
     * Runs an operation inside a new active {@link SpanKind#INTERNAL} span.
     */
    public <T> T withSpan(String name, Attributes attributes, Function<Span, T> fn) {
        return withSpan(name, SpanKind.INTERNAL, attributes, fn);
    }

    private static String safeMessage(Throwable err) {
        return err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
    }

    @Override
    public void close() {
        if (tracerProvider != null) {
            CompletableResultCode traceShutdown = tracerProvider.shutdown();
            traceShutdown.join(5, TimeUnit.SECONDS);
        }
        if (loggerProvider != null) {
            CompletableResultCode logShutdown = loggerProvider.shutdown();
            logShutdown.join(5, TimeUnit.SECONDS);
        }
    }
}
