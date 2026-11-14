package com.realestate.api;

import com.realestate.api.db.InMemoryListingRepository;
import com.realestate.api.db.PostgresDataSourceFactory;
import com.realestate.api.db.SeedListings;
import com.realestate.api.domain.ListingRepository;
import com.realestate.api.graphql.CorsConfig;
import com.realestate.api.graphql.GraphqlHttpApp;
import com.realestate.api.scoring.DefaultRelevanceScorer;
import com.realestate.api.search.SearchService;
import com.realestate.api.telemetry.ApiTelemetry;
import com.realestate.api.telemetry.TelemetryConfig;
import com.realestate.api.validation.DefaultSearchValidator;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Application entrypoint for the Real Estate Search GraphQL HTTP server.
 */
public final class Main {

    public static final int DEFAULT_PORT = 4000;

    private Main() {
    }

    /**
     * Startup configuration resolved from environment variables or test overrides.
     */
    public record ServerConfig(
        int port,
        String databaseUrl,
        String corsOrigin,
        TelemetryConfig telemetryConfig
    ) {
        public ServerConfig {
            databaseUrl = (databaseUrl == null || databaseUrl.isBlank()) ? null : databaseUrl.trim();
            telemetryConfig = telemetryConfig != null ? telemetryConfig : TelemetryConfig.fromEnv();
        }

        public static ServerConfig fromEnv() {
            return fromMap(System.getenv());
        }

        public static ServerConfig fromMap(Map<String, String> env) {
            Map<String, String> safeEnv = env == null ? Map.of() : env;
            int port = parsePort(safeEnv.get("PORT"));
            String databaseUrl = safeEnv.get("DATABASE_URL");
            String corsOrigin = safeEnv.get("CORS_ORIGIN");
            return new ServerConfig(port, databaseUrl, corsOrigin, TelemetryConfig.fromMap(safeEnv));
        }

        private static int parsePort(String rawPort) {
            if (rawPort == null || rawPort.isBlank()) {
                return DEFAULT_PORT;
            }
            return Integer.parseInt(rawPort.trim());
        }
    }

    /**
     * Handle to a running HTTP server instance supporting idempotent graceful shutdown.
     */
    public static final class RunningServer implements AutoCloseable {
        private final GraphqlHttpApp app;
        private final int port;
        private final ListingRepository repository;
        private final Runnable shutdownTelemetry;
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        RunningServer(
            GraphqlHttpApp app,
            int port,
            ListingRepository repository,
            Runnable shutdownTelemetry
        ) {
            this.app = app;
            this.port = port;
            this.repository = repository;
            this.shutdownTelemetry = shutdownTelemetry;
        }

        public int port() {
            return port;
        }

        public ListingRepository repository() {
            return repository;
        }

        public GraphqlHttpApp app() {
            return app;
        }

        /**
         * Stops the HTTP server, closes any underlying repository resources, and flushes telemetry once.
         */
        public void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            RuntimeException firstFailure = null;
            try {
                app.stop();
            } catch (RuntimeException ex) {
                firstFailure = ex;
            }
            if (repository instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ex) {
                    if (firstFailure == null) {
                        firstFailure = new RuntimeException("Failed to close repository", ex);
                    }
                }
            }
            try {
                shutdownTelemetry.run();
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }

        @Override
        public void close() {
            stop();
        }
    }

    public static RunningServer startServer() {
        return startServer(ServerConfig.fromEnv());
    }

    public static RunningServer startServer(ServerConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        ApiTelemetry telemetry = ApiTelemetry.initGlobal(config.telemetryConfig());
        return startServer(
            config,
            telemetry,
            dbUrl -> PostgresDataSourceFactory.createAndInitializeRepository(dbUrl, telemetry.tracer()),
            ApiTelemetry::shutdownGlobal
        );
    }

    static RunningServer startServer(
        ServerConfig config,
        ApiTelemetry telemetry,
        Function<String, ListingRepository> postgresFactory,
        Runnable shutdownTelemetry
    ) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(telemetry, "telemetry must not be null");
        Objects.requireNonNull(postgresFactory, "postgresFactory must not be null");
        Objects.requireNonNull(shutdownTelemetry, "shutdownTelemetry must not be null");

        ListingRepository repository = null;
        try {
            if (config.databaseUrl() != null) {
                repository = postgresFactory.apply(config.databaseUrl());
            } else {
                repository = new InMemoryListingRepository(new DefaultRelevanceScorer(), telemetry.tracer());
                repository.ensureSchema();
            }

            SeedListings.seedDatabase(repository);

            SearchService searchService = new SearchService(repository, new DefaultSearchValidator(), telemetry);
            CorsConfig corsConfig = CorsConfig.fromEnvValue(config.corsOrigin());
            GraphqlHttpApp app = new GraphqlHttpApp(searchService, telemetry, corsConfig);
            app.start(config.port());

            int actualPort = app.port();
            telemetry.log(
                Severity.INFO,
                "INFO",
                "server.started",
                Attributes.builder()
                    .put("server.port", (long) actualPort)
                    .put("url.full", "http://localhost:" + actualPort + "/graphql")
                    .build()
            );

            return new RunningServer(app, actualPort, repository, shutdownTelemetry);
        } catch (RuntimeException ex) {
            if (repository instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception suppressed) {
                    ex.addSuppressed(suppressed);
                }
            }
            try {
                shutdownTelemetry.run();
            } catch (RuntimeException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex;
        }
    }

    /**
     * Creates an idempotent shutdown action that closes {@code server} once and reports exit status.
     */
    public static Runnable createShutdownAction(AutoCloseable server, IntConsumer onExit) {
        Objects.requireNonNull(server, "server must not be null");
        Objects.requireNonNull(onExit, "onExit must not be null");
        AtomicBoolean shuttingDown = new AtomicBoolean(false);
        return () -> {
            if (!shuttingDown.compareAndSet(false, true)) {
                return;
            }
            try {
                server.close();
                onExit.accept(0);
            } catch (Exception err) {
                System.err.println("Error during graceful shutdown: " + err.getMessage());
                onExit.accept(1);
            }
        };
    }

    public static Thread registerShutdownHook(RunningServer server) {
        Runnable action = createShutdownAction(server, code -> {});
        Thread hook = new Thread(action, "real-estate-api-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    public static void main(String[] args) {
        runWithConfig(ServerConfig.fromEnv(), System::exit);
    }

    static RunningServer runWithConfig(ServerConfig config, IntConsumer onExit) {
        try {
            RunningServer server = startServer(config);
            registerShutdownHook(server);
            return server;
        } catch (RuntimeException err) {
            System.err.println("Failed to start server: " + err.getMessage());
            onExit.accept(1);
            return null;
        }
    }
}
