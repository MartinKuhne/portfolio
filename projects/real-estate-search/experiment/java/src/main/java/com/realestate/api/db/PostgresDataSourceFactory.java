package com.realestate.api.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Factory and URI parser for PostgreSQL {@link HikariDataSource} instances.
 */
public final class PostgresDataSourceFactory {

    /**
     * Parsed JDBC connection parameters extracted from a PostgreSQL URI or JDBC URL.
     */
    public record JdbcConnectionConfig(String jdbcUrl, String username, String password) {
    }

    private PostgresDataSourceFactory() {
    }

    /**
     * Converts a {@code postgres://}, {@code postgresql://}, or {@code jdbc:postgresql://} URL
     * into a {@link JdbcConnectionConfig}.
     */
    public static JdbcConnectionConfig parseConnectionUrl(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalArgumentException("databaseUrl must not be null or blank");
        }
        String trimmed = databaseUrl.trim();
        if (trimmed.startsWith("jdbc:postgresql://")) {
            return new JdbcConnectionConfig(trimmed, null, null);
        }
        if (!trimmed.startsWith("postgres://") && !trimmed.startsWith("postgresql://")) {
            throw new IllegalArgumentException(
                "Unsupported PostgreSQL URL scheme; expected postgres://, postgresql://, or jdbc:postgresql://"
            );
        }

        URI uri = URI.create(trimmed);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL URL must include a host");
        }
        int port = uri.getPort();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            throw new IllegalArgumentException("PostgreSQL URL must include a database name");
        }

        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://").append(host);
        if (port > 0) {
            jdbcUrl.append(':').append(port);
        }
        jdbcUrl.append(path);
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            jdbcUrl.append('?').append(uri.getRawQuery());
        }

        String username = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colonIdx = userInfo.indexOf(':');
            if (colonIdx >= 0) {
                username = decode(userInfo.substring(0, colonIdx));
                password = decode(userInfo.substring(colonIdx + 1));
            } else {
                username = decode(userInfo);
            }
        }

        return new JdbcConnectionConfig(jdbcUrl.toString(), username, password);
    }

    /**
     * Builds a {@link HikariConfig} for the given PostgreSQL connection string without opening connections.
     */
    public static HikariConfig createHikariConfig(String databaseUrl) {
        JdbcConnectionConfig config = parseConnectionUrl(databaseUrl);
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        if (config.username() != null) {
            hikari.setUsername(config.username());
        }
        if (config.password() != null) {
            hikari.setPassword(config.password());
        }
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.setMaximumPoolSize(10);
        hikari.setMinimumIdle(1);
        hikari.setPoolName("real-estate-hikari");
        return hikari;
    }

    /**
     * Creates a configured {@link HikariDataSource} for the given PostgreSQL connection string.
     */
    public static HikariDataSource createDataSource(String databaseUrl) {
        return new HikariDataSource(createHikariConfig(databaseUrl));
    }

    /**
     * Creates a {@link PostgresListingRepository} backed by a new {@link HikariDataSource} and
     * runs {@link PostgresListingRepository#ensureSchema()}, closing the pool if initialization fails.
     */
    public static PostgresListingRepository createAndInitializeRepository(String databaseUrl) {
        return createAndInitializeRepository(
            databaseUrl,
            io.opentelemetry.api.GlobalOpenTelemetry.getTracer("real-estate-api")
        );
    }

    /**
     * Creates a {@link PostgresListingRepository} with an explicit OTel {@link io.opentelemetry.api.trace.Tracer},
     * runs {@link PostgresListingRepository#ensureSchema()}, and closes the pool if initialization fails.
     */
    public static PostgresListingRepository createAndInitializeRepository(
        String databaseUrl,
        io.opentelemetry.api.trace.Tracer tracer
    ) {
        Objects.requireNonNull(databaseUrl, "databaseUrl must not be null");
        Objects.requireNonNull(tracer, "tracer must not be null");
        HikariDataSource dataSource = createDataSource(databaseUrl);
        return initializeRepository(new PostgresListingRepository(dataSource, tracer), dataSource);
    }

    static PostgresListingRepository initializeRepository(
        PostgresListingRepository repository,
        AutoCloseable poolResource
    ) {
        try {
            repository.ensureSchema();
            return repository;
        } catch (RuntimeException ex) {
            try {
                poolResource.close();
            } catch (Exception closeEx) {
                ex.addSuppressed(closeEx);
            }
            throw ex;
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
