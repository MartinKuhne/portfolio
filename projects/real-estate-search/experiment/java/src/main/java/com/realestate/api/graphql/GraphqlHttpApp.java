package com.realestate.api.graphql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realestate.api.graphql.GraphqlPayloads.RequestTelemetryContext;
import com.realestate.api.search.SearchService;
import com.realestate.api.telemetry.ApiTelemetry;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ContentTooLargeResponse;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Connector;

/**
 * Javalin HTTP server exposing {@code /graphql} with CORS, W3C TraceContext propagation,
 * OpenTelemetry {@code SERVER} spans, and GraphQL-over-HTTP status semantics.
 */
public final class GraphqlHttpApp {

    public static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    public static final long HTTP_IDLE_TIMEOUT_MS = 30_000L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final GraphQL graphQL;
    private final ApiTelemetry telemetry;
    private final CorsConfig corsConfig;
    private final Javalin javalin;

    public GraphqlHttpApp(SearchService searchService, ApiTelemetry telemetry, CorsConfig corsConfig) {
        this(
            GraphqlSchemaFactory.buildGraphQL(
                Objects.requireNonNull(searchService, "searchService must not be null"),
                Objects.requireNonNull(telemetry, "telemetry must not be null")
            ),
            telemetry,
            corsConfig
        );
    }

    GraphqlHttpApp(GraphQL graphQL, ApiTelemetry telemetry, CorsConfig corsConfig) {
        this.graphQL = Objects.requireNonNull(graphQL, "graphQL must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
        this.corsConfig = Objects.requireNonNull(corsConfig, "corsConfig must not be null");
        this.javalin = createJavalin();
    }

    private Javalin createJavalin() {
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.maxRequestSize = MAX_REQUEST_BODY_BYTES;
            config.http.asyncTimeout = HTTP_IDLE_TIMEOUT_MS;
            config.jetty.modifyHttpConfiguration(httpConfig -> httpConfig.setIdleTimeout(HTTP_IDLE_TIMEOUT_MS));
            config.jetty.modifyServer(server -> applyConnectorIdleTimeout(server.getConnectors()));
        });
        app.events(events -> events.serverStarting(() -> {
            if (app.jettyServer() != null && app.jettyServer().server() != null) {
                applyConnectorIdleTimeout(app.jettyServer().server().getConnectors());
            }
        }));
        app.options("/graphql", ctx -> handleTracedRequest(ctx, HttpAction.OPTIONS));
        app.post("/graphql", ctx -> handleTracedRequest(ctx, HttpAction.POST));
        app.get("/graphql", ctx -> handleTracedRequest(ctx, HttpAction.GET));
        return app;
    }

    private static void applyConnectorIdleTimeout(Connector[] connectors) {
        if (connectors == null) {
            return;
        }
        for (Connector connector : connectors) {
            if (connector instanceof AbstractConnector abstractConnector) {
                abstractConnector.setIdleTimeout(HTTP_IDLE_TIMEOUT_MS);
            }
        }
    }

    public Javalin javalin() {
        return javalin;
    }

    public GraphqlHttpApp start(int port) {
        javalin.start(port);
        return this;
    }

    public int port() {
        return javalin.port();
    }

    public void stop() {
        javalin.stop();
    }

    private enum HttpAction {
        OPTIONS,
        POST,
        GET
    }

    private void handleTracedRequest(Context ctx, HttpAction action) {
        io.opentelemetry.context.Context parentContext = telemetry.extractTraceContext(ctx.headerMap());
        String method = ctx.method().name().toUpperCase();
        String path = ctx.path();

        Span serverSpan = telemetry.tracer()
            .spanBuilder(method + " " + path)
            .setParent(parentContext)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute("http.request.method", method)
            .setAttribute("url.path", path)
            .startSpan();

        RequestTelemetryContext reqCtx = new RequestTelemetryContext();

        try (Scope ignored = serverSpan.makeCurrent()) {
            applyCorsHeaders(ctx, action == HttpAction.OPTIONS);
            if (action == HttpAction.OPTIONS) {
                ctx.status(204);
            } else if (action == HttpAction.POST) {
                executeGraphqlPost(ctx, reqCtx);
            } else {
                executeGraphqlGet(ctx, reqCtx);
            }

            int finalStatus = ctx.status().getCode();
            recordCompletionTelemetry(serverSpan, reqCtx, method, path, finalStatus);
        } catch (HttpResponseException err) {
            int status = err.getStatus();
            if (status >= 500) {
                serverSpan.recordException(err);
            }
            recordCompletionTelemetry(serverSpan, reqCtx, method, path, status);
            String msg = status >= 500
                ? "An unexpected internal error occurred."
                : (err.getMessage() != null && !err.getMessage().isBlank() ? err.getMessage() : "HTTP " + status);
            String code = switch (status) {
                case 413 -> "PAYLOAD_TOO_LARGE";
                case 400 -> "BAD_USER_INPUT";
                default -> status >= 500 ? "INTERNAL_SERVER_ERROR" : "HTTP_" + status;
            };
            writeJson(
                ctx,
                status,
                Map.of(
                    "errors",
                    List.of(
                        Map.of(
                            "message", msg,
                            "extensions", Map.of("code", code)
                        )
                    )
                )
            );
        } catch (RuntimeException err) {
            serverSpan.recordException(err);
            String msg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
            serverSpan.setStatus(StatusCode.ERROR, msg);
            ctx.status(500);
            serverSpan.setAttribute("http.response.status_code", 500);
            telemetry.log(
                Severity.ERROR,
                "ERROR",
                "http.request.failed",
                Attributes.builder()
                    .put("http.request.method", method)
                    .put("url.path", path)
                    .put("http.response.status_code", 500L)
                    .build()
            );
            writeJson(
                ctx,
                500,
                Map.of(
                    "errors",
                    List.of(
                        Map.of(
                            "message", "An unexpected internal error occurred.",
                            "extensions", Map.of("code", "INTERNAL_SERVER_ERROR")
                        )
                    )
                )
            );
        } finally {
            serverSpan.end();
        }
    }

    private void recordCompletionTelemetry(
        Span serverSpan,
        RequestTelemetryContext reqCtx,
        String method,
        String path,
        int finalStatus
    ) {
        serverSpan.setAttribute("http.response.status_code", finalStatus);
        Attributes logAttrs = Attributes.builder()
            .put("http.request.method", method)
            .put("url.path", path)
            .put("http.response.status_code", (long) finalStatus)
            .build();

        if (finalStatus >= 500 || reqCtx.hasInternalError()) {
            serverSpan.setStatus(
                StatusCode.ERROR,
                reqCtx.hasInternalError() ? "GraphQL INTERNAL_SERVER_ERROR" : "HTTP " + finalStatus
            );
            telemetry.log(Severity.ERROR, "ERROR", "http.request.failed", logAttrs);
        } else if (finalStatus >= 400 || reqCtx.hasDomainError()) {
            telemetry.log(Severity.WARN, "WARN", "http.request.rejected", logAttrs);
        } else {
            telemetry.log(Severity.INFO, "INFO", "http.request.completed", logAttrs);
        }
    }

    private void applyCorsHeaders(Context ctx, boolean isPreflight) {
        String origin = ctx.header("Origin");
        if (origin != null && corsConfig.isAllowed(origin)) {
            ctx.header("Access-Control-Allow-Origin", origin);
            ctx.header("Vary", "Origin");
            if (isPreflight) {
                ctx.header("Access-Control-Allow-Methods", CorsConfig.ALLOWED_METHODS);
                ctx.header("Access-Control-Allow-Headers", CorsConfig.ALLOWED_HEADERS);
            }
        }
    }

    private void executeGraphqlPost(Context ctx, RequestTelemetryContext reqCtx) {
        String rawBody = readBoundedBody(ctx, MAX_REQUEST_BODY_BYTES);
        if (rawBody == null || rawBody.isBlank()) {
            writeBadRequestError(ctx, "Request body must be a valid JSON object.");
            return;
        }

        Map<String, Object> payload;
        try {
            payload = OBJECT_MAPPER.readValue(rawBody, MAP_TYPE);
        } catch (Exception e) {
            writeBadRequestError(ctx, "Malformed JSON payload.");
            return;
        }

        if (payload == null) {
            writeBadRequestError(ctx, "Request body must be a JSON object.");
            return;
        }

        Object rawQuery = payload.get("query");
        if (!(rawQuery instanceof String query) || query.isBlank()) {
            writeBadRequestError(ctx, "GraphQL query must be a non-empty string.");
            return;
        }

        Object rawVariables = payload.get("variables");
        Map<String, Object> variables;
        if (rawVariables == null) {
            variables = Map.of();
        } else if (rawVariables instanceof Map<?, ?> varMap) {
            variables = castStringMap(varMap);
        } else {
            writeBadRequestError(ctx, "GraphQL variables must be a JSON object.");
            return;
        }

        Object rawOpName = payload.get("operationName");
        String operationName = null;
        if (rawOpName != null) {
            if (!(rawOpName instanceof String opStr)) {
                writeBadRequestError(ctx, "GraphQL operationName must be a string.");
                return;
            }
            operationName = opStr;
        }

        runGraphqlAndRespond(ctx, reqCtx, query, operationName, variables);
    }

    private void executeGraphqlGet(Context ctx, RequestTelemetryContext reqCtx) {
        String query = ctx.queryParam("query");
        if (query == null || query.isBlank()) {
            writeBadRequestError(ctx, "GraphQL query parameter is required.");
            return;
        }

        String rawVariables = ctx.queryParam("variables");
        Map<String, Object> variables = Map.of();
        if (rawVariables != null && !rawVariables.isBlank()) {
            try {
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(rawVariables, MAP_TYPE);
                if (parsed == null) {
                    writeBadRequestError(ctx, "GraphQL variables must be a JSON object.");
                    return;
                }
                variables = parsed;
            } catch (Exception e) {
                writeBadRequestError(ctx, "Malformed JSON in GraphQL variables parameter.");
                return;
            }
        }

        String operationName = ctx.queryParam("operationName");
        runGraphqlAndRespond(ctx, reqCtx, query, operationName, variables);
    }

    private void runGraphqlAndRespond(
        Context ctx,
        RequestTelemetryContext reqCtx,
        String query,
        String operationName,
        Map<String, Object> variables
    ) {
        ExecutionInput.Builder inputBuilder = ExecutionInput.newExecutionInput()
            .query(query)
            .variables(variables)
            .graphQLContext(builder -> builder.of(GraphqlSchemaFactory.REQUEST_TELEMETRY_CONTEXT_KEY, reqCtx));

        if (operationName != null) {
            inputBuilder.operationName(operationName);
        }

        ExecutionResult executionResult = graphQL.execute(inputBuilder.build());
        Map<String, Object> spec = executionResult.toSpecification();

        int httpStatus = (!executionResult.isDataPresent() && !executionResult.getErrors().isEmpty())
            ? 400
            : 200;

        writeJson(ctx, httpStatus, spec);
    }

    private static String readBoundedBody(Context ctx, int maxBytes) {
        long contentLength = ctx.req().getContentLengthLong();
        if (contentLength > maxBytes) {
            throw new ContentTooLargeResponse("Request body exceeds maximum allowed size of " + maxBytes + " bytes.");
        }
        try (InputStream in = ctx.bodyInputStream()) {
            ByteArrayOutputStream bufferOut = new ByteArrayOutputStream(
                contentLength > 0 ? (int) Math.min(contentLength, maxBytes) : 4096
            );
            byte[] chunk = new byte[8192];
            int totalRead = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (read > maxBytes - totalRead) {
                    throw new ContentTooLargeResponse(
                        "Request body exceeds maximum allowed size of " + maxBytes + " bytes."
                    );
                }
                bufferOut.write(chunk, 0, read);
                totalRead += read;
            }
            return bufferOut.toString(StandardCharsets.UTF_8);
        } catch (ContentTooLargeResponse ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BadRequestResponse("Failed to read request body.");
        }
    }

    private static Map<String, Object> castStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private void writeBadRequestError(Context ctx, String message) {
        writeJson(
            ctx,
            400,
            Map.of(
                "errors",
                List.of(
                    Map.of(
                        "message", message,
                        "extensions", Map.of("code", "BAD_USER_INPUT")
                    )
                )
            )
        );
    }

    private void writeJson(Context ctx, int status, Object body) {
        ctx.status(status);
        ctx.contentType("application/json; charset=utf-8");
        try {
            ctx.result(OBJECT_MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize GraphQL response JSON", e);
        }
    }
}
