package com.realestate.api.graphql;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses and evaluates {@code CORS_ORIGIN} allowlists matching ADR-008 header rules.
 */
public final class CorsConfig {

    public static final String DEFAULT_CORS_ORIGINS = "http://localhost:8080,http://localhost:5173";
    public static final String ALLOWED_METHODS = "GET, POST, OPTIONS";
    public static final String ALLOWED_HEADERS = "content-type, traceparent, tracestate";

    private final Set<String> allowedOrigins;

    public CorsConfig(Set<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
    }

    /**
     * Parses a comma-separated {@code CORS_ORIGIN} string, falling back to {@link #DEFAULT_CORS_ORIGINS}.
     */
    public static CorsConfig fromEnvValue(String corsOriginEnv) {
        String raw = corsOriginEnv != null ? corsOriginEnv : DEFAULT_CORS_ORIGINS;
        Set<String> origins = Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new CorsConfig(origins);
    }

    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    public boolean isAllowed(String origin) {
        return origin != null && allowedOrigins.contains(origin);
    }
}
