package com.realestate.api.telemetry;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normalizes incoming HTTP headers case-insensitively and extracts W3C TraceContext.
 */
public final class HeaderTraceExtractor {

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? List.of() : carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            if (carrier == null || key == null) {
                return null;
            }
            return carrier.get(key.toLowerCase(Locale.ROOT));
        }
    };

    private HeaderTraceExtractor() {
    }

    /**
     * Normalizes single-value HTTP headers to lowercase keys.
     */
    public static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    /**
     * Normalizes multi-value HTTP headers (joining non-empty lists with {@code ","}) to lowercase keys.
     */
    public static Map<String, String> normalizeMultiHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key != null && values != null && !values.isEmpty()) {
                normalized.put(key.toLowerCase(Locale.ROOT), String.join(",", values));
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    /**
     * Extracts W3C {@code traceparent}/{@code tracestate} into a clean root {@link Context}.
     */
    public static Context extractContext(ContextPropagators propagators, Map<String, String> headers) {
        Map<String, String> carrier = normalizeHeaders(headers);
        return propagators.getTextMapPropagator().extract(Context.root(), carrier, MAP_GETTER);
    }
}
