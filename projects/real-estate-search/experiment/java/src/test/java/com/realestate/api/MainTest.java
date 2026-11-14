package com.realestate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realestate.api.db.InMemoryListingRepository;
import com.realestate.api.domain.ListingRepository;
import com.realestate.api.telemetry.ApiTelemetry;
import com.realestate.api.telemetry.TelemetryConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ApiTelemetry.shutdownGlobal();
    }

    @Test
    void parsesServerConfigFromEnvironmentMap() {
        Main.ServerConfig defaults = Main.ServerConfig.fromMap(Map.of());
        assertThat(defaults.port()).isEqualTo(Main.DEFAULT_PORT);
        assertThat(defaults.databaseUrl()).isNull();
        assertThat(defaults.corsOrigin()).isNull();

        Main.ServerConfig custom = Main.ServerConfig.fromMap(Map.of(
            "PORT", "8090",
            "DATABASE_URL", "postgres://user:pass@localhost:5432/realestate",
            "CORS_ORIGIN", "https://app.example.com"
        ));
        assertThat(custom.port()).isEqualTo(8090);
        assertThat(custom.databaseUrl()).isEqualTo("postgres://user:pass@localhost:5432/realestate");
        assertThat(custom.corsOrigin()).isEqualTo("https://app.example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsServerOnEphemeralPortEmitsServerStartedLogAndStopsIdempotently() throws Exception {
        ByteArrayOutputStream logBuffer = new ByteArrayOutputStream();
        TelemetryConfig telemetryConfig = new TelemetryConfig(
            "real-estate-api",
            null,
            null,
            List.of(),
            List.of(),
            new PrintStream(logBuffer, true, StandardCharsets.UTF_8)
        );
        Main.ServerConfig config = new Main.ServerConfig(0, null, null, telemetryConfig);

        try (Main.RunningServer server = Main.startServer(config)) {
            assertThat(server.port()).isGreaterThan(0);

            List<Map<String, Object>> logs = logBuffer.toString(StandardCharsets.UTF_8)
                .lines()
                .map(line -> {
                    try {
                        return MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();

            Map<String, Object> started = logs.stream()
                .filter(entry -> "server.started".equals(entry.get("body")))
                .findFirst()
                .orElseThrow();
            assertThat(started.get("service")).isEqualTo("real-estate-api");
            assertThat(started.get("severityText")).isEqualTo("INFO");
            Map<String, Object> attrs = (Map<String, Object>) started.get("attributes");
            assertThat(attrs.get("server.port")).isEqualTo(server.port());
            assertThat(attrs.get("url.full")).isEqualTo("http://localhost:" + server.port() + "/graphql");

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/graphql"))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of(
                        "query", "query($input: SearchListingsInput!) { searchListings(input: $input) { __typename ... on SearchListingsSuccess { listings { id city } pagination { totalResults } } } }",
                        "variables", Map.of("input", Map.of("city", "Austin", "page", 1, "pageSize", 5))
                    ))))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertThat(response.statusCode()).isEqualTo(200);
            Map<String, Object> body = MAPPER.readValue(response.body(), new TypeReference<>() {});
            Map<String, Object> searchResult = (Map<String, Object>) ((Map<String, Object>) body.get("data")).get("searchListings");
            assertThat(searchResult.get("__typename")).isEqualTo("SearchListingsSuccess");
            assertThat((List<?>) searchResult.get("listings")).isNotEmpty();

            server.stop();
            server.stop(); // idempotent second stop
        }
    }

    @Test
    void usesPostgresFactoryAndClosesRepositoryOnStop() {
        interface CloseableRepo extends ListingRepository, AutoCloseable {
        }

        InMemoryListingRepository delegate = new InMemoryListingRepository();
        AtomicBoolean closed = new AtomicBoolean(false);
        CloseableRepo closeableRepo = new CloseableRepo() {
            @Override
            public void ensureSchema() {
                delegate.ensureSchema();
            }

            @Override
            public int countMatchingListings(com.realestate.api.domain.SearchCriteria criteria) {
                return delegate.countMatchingListings(criteria);
            }

            @Override
            public List<com.realestate.api.domain.Listing> fetchListingPage(
                com.realestate.api.domain.SearchCriteria criteria,
                int offset
            ) {
                return delegate.fetchListingPage(criteria, offset);
            }

            @Override
            public int insertListingsIdempotently(List<com.realestate.api.domain.NewListing> listings) {
                return delegate.insertListingsIdempotently(listings);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        ApiTelemetry telemetry = ApiTelemetry.noop();
        Main.ServerConfig config = new Main.ServerConfig(
            0,
            "postgres://user:pass@localhost:5432/mock_db",
            null,
            TelemetryConfig.fromMap(Map.of())
        );

        Main.RunningServer server = Main.startServer(config, telemetry, url -> closeableRepo, telemetry::close);
        assertThat(server.port()).isGreaterThan(0);
        server.stop();
        assertThat(closed).isTrue();
    }

    @Test
    void cleansUpTelemetryAndThrowsWhenPortBindFails() {
        Main.ServerConfig invalidPort = new Main.ServerConfig(-1, null, null, TelemetryConfig.fromMap(Map.of()));
        assertThatThrownBy(() -> Main.startServer(invalidPort))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void cleansUpCloseableRepositoryOnStartupFailureAndPropagatesStopFailures() {
        interface CloseableRepo extends ListingRepository, AutoCloseable {
        }
        AtomicBoolean closedOnInitFailure = new AtomicBoolean(false);
        CloseableRepo failingInitRepo = new CloseableRepo() {
            @Override
            public void ensureSchema() {
            }

            @Override
            public int countMatchingListings(com.realestate.api.domain.SearchCriteria criteria) {
                return 0;
            }

            @Override
            public List<com.realestate.api.domain.Listing> fetchListingPage(
                com.realestate.api.domain.SearchCriteria criteria,
                int offset
            ) {
                return List.of();
            }

            @Override
            public int insertListingsIdempotently(List<com.realestate.api.domain.NewListing> listings) {
                return 0;
            }

            @Override
            public void close() {
                closedOnInitFailure.set(true);
            }
        };

        ApiTelemetry telemetry = ApiTelemetry.noop();
        Main.ServerConfig invalidPort = new Main.ServerConfig(
            -1,
            "postgres://user:pass@localhost:5432/db",
            null,
            TelemetryConfig.fromMap(Map.of())
        );
        assertThatThrownBy(() -> Main.startServer(invalidPort, telemetry, url -> failingInitRepo, telemetry::close))
            .isInstanceOf(RuntimeException.class);
        assertThat(closedOnInitFailure).isTrue();

        CloseableRepo failingCloseRepo = new CloseableRepo() {
            private final InMemoryListingRepository delegate = new InMemoryListingRepository();

            @Override
            public void ensureSchema() {
            }

            @Override
            public int countMatchingListings(com.realestate.api.domain.SearchCriteria criteria) {
                return delegate.countMatchingListings(criteria);
            }

            @Override
            public List<com.realestate.api.domain.Listing> fetchListingPage(
                com.realestate.api.domain.SearchCriteria criteria,
                int offset
            ) {
                return delegate.fetchListingPage(criteria, offset);
            }

            @Override
            public int insertListingsIdempotently(List<com.realestate.api.domain.NewListing> listings) {
                return delegate.insertListingsIdempotently(listings);
            }

            @Override
            public void close() throws Exception {
                throw new Exception("repo close error");
            }
        };

        Main.ServerConfig validPort = new Main.ServerConfig(
            0,
            "postgres://user:pass@localhost:5432/db",
            null,
            TelemetryConfig.fromMap(Map.of())
        );
        Main.RunningServer server = Main.startServer(
            validPort,
            telemetry,
            url -> failingCloseRepo,
            () -> {
                throw new RuntimeException("telemetry shutdown error");
            }
        );
        assertThat(server.repository()).isSameAs(failingCloseRepo);
        assertThat(server.app()).isNotNull();
        Thread hook = Main.registerShutdownHook(server);
        assertThat(Runtime.getRuntime().removeShutdownHook(hook)).isTrue();
        assertThatThrownBy(server::stop)
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to close repository");

        List<Integer> runExitCodes = new ArrayList<>();
        Main.RunningServer started = Main.runWithConfig(
            new Main.ServerConfig(0, null, null, TelemetryConfig.fromMap(Map.of())),
            runExitCodes::add
        );
        assertThat(started).isNotNull();
        assertThat(runExitCodes).isEmpty();
        started.close();

        List<Integer> failRunCodes = new ArrayList<>();
        Main.RunningServer failed = Main.runWithConfig(
            new Main.ServerConfig(-1, null, null, TelemetryConfig.fromMap(Map.of())),
            failRunCodes::add
        );
        assertThat(failed).isNull();
        assertThat(failRunCodes).containsExactly(1);
    }

    @Test
    void shutdownActionStopsServerOnceAndReportsExitCodes() {
        AtomicInteger closeCount = new AtomicInteger(0);
        AutoCloseable stubServer = closeCount::incrementAndGet;
        List<Integer> exitCodes = new ArrayList<>();
        Runnable action = Main.createShutdownAction(stubServer, exitCodes::add);

        action.run();
        action.run();

        assertThat(closeCount.get()).isEqualTo(1);
        assertThat(exitCodes).containsExactly(0);

        AutoCloseable failingServer = () -> {
            throw new RuntimeException("close failure");
        };
        List<Integer> failCodes = new ArrayList<>();
        Main.createShutdownAction(failingServer, failCodes::add).run();
        assertThat(failCodes).containsExactly(1);
    }
}
