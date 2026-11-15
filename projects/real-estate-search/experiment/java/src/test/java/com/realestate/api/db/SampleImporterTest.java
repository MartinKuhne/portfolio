package com.realestate.api.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realestate.api.domain.Listing;
import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.telemetry.TelemetryConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleImporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void resolveSampleListingsPathFindsExplicitAndDefaultFileInWorkingDir() throws Exception {
        Path explicit = SampleImporter.resolveSampleListingsPath("custom/sample.json");
        assertThat(explicit).isEqualTo(Path.of("custom/sample.json").toAbsolutePath().normalize());

        Path isolatedDir = tempDir.resolve("isolated-workdir");
        Files.createDirectories(isolatedDir);
        Path sampleInCwd = isolatedDir.resolve("sample_listings.json");
        Files.writeString(sampleInCwd, "[]", StandardCharsets.UTF_8);

        Path resolvedFromCwd = SampleImporter.resolveSampleListingsPath(null, isolatedDir);
        assertThat(resolvedFromCwd).isEqualTo(sampleInCwd);

        // Fallback when sample_listings.json does not exist in isolatedDir or parents
        Path missingDir = Files.createDirectories(isolatedDir.resolve("nested/empty"));
        Files.deleteIfExists(sampleInCwd);
        Path fallback = SampleImporter.resolveSampleListingsPath(null, missingDir);
        assertThat(fallback.getFileName().toString()).isEqualTo("sample_listings.json");
    }

    @Test
    void importsSampleListingsFixtureIdempotentlyWithFullFieldMapping() throws Exception {
        Path fixturePath = copyFixtureToTempSampleFile();

        InMemoryListingRepository repository = new InMemoryListingRepository();
        int first = SampleImporter.importSampleListings(repository, fixturePath.toString());
        int second = SampleImporter.importSampleListings(repository, fixturePath.toString());

        assertThat(first).isEqualTo(12);
        assertThat(second).isZero();

        List<Listing> springfield = repository.fetchListingPage(
            new SearchCriteria(null, null, null, "Springfield", null, null, 1, 20), 0
        );
        Listing a1 = springfield.stream().filter(l -> "A1".equals(l.id())).findFirst().orElseThrow();
        assertThat(UUID.fromString(a1.internalId())).isNotNull();
        assertThat(a1.source()).isEqualTo("MLS_A");
        assertThat(a1.address()).isEqualTo("123 Main St, Apt 4B, Springfield, VA 22150");
        assertThat(a1.state()).isEqualTo("VA");
        assertThat(a1.zip()).isEqualTo("22150");
        assertThat(a1.price()).isEqualTo(450000);
        assertThat(a1.bedrooms()).isEqualTo(2);
        assertThat(a1.bathrooms()).isEqualTo(1.5);
        assertThat(a1.sqft()).isEqualTo(980);
        assertThat(a1.latitude()).isEqualTo(38.7893);
        assertThat(a1.longitude()).isEqualTo(-77.1873);
        assertThat(a1.status()).isEqualTo("active");
        assertThat(a1.listedDate()).isEqualTo("2026-08-29T00:00:00.000Z");

        List<Listing> chantilly = repository.fetchListingPage(
            new SearchCriteria(null, null, null, "Chantilly", null, null, 1, 20), 0
        );
        assertThat(chantilly).hasSize(1);
        assertThat(chantilly.get(0).status()).isEqualTo("pending");
    }

    @Test
    void formatsAddressWithoutStateZipAndHandlesEmptyArray() throws Exception {
        Path emptyFile = tempDir.resolve("empty.json");
        Files.writeString(emptyFile, "[]", StandardCharsets.UTF_8);

        InMemoryListingRepository repository = new InMemoryListingRepository();
        assertThat(SampleImporter.importSampleListings(repository, emptyFile.toString())).isZero();

        Path minimalFile = tempDir.resolve("minimal.json");
        Files.writeString(minimalFile, """
            [{
              "id": "MIN-1",
              "address": "789 Pine Rd",
              "city": "Alexandria",
              "price": 300000,
              "bedrooms": 2,
              "listedDate": "2026-09-01",
              "description": "Cozy condo"
            }]
            """, StandardCharsets.UTF_8);

        assertThat(SampleImporter.importSampleListings(repository, minimalFile.toString())).isEqualTo(1);
        Listing loaded = repository.fetchListingPage(
            new SearchCriteria(null, null, null, "Alexandria", null, null, 1, 10), 0
        ).get(0);
        assertThat(loaded.address()).isEqualTo("789 Pine Rd");
        assertThat(loaded.source()).isEqualTo("IMPORT");
        assertThat(loaded.status()).isEqualTo("active");
        assertThat(loaded.listedDate()).isEqualTo("2026-09-01T00:00:00.000Z");
    }

    @Test
    void rejectsMissingFileInvalidJsonAndNonArrayRoot() throws Exception {
        InMemoryListingRepository repository = new InMemoryListingRepository();

        assertThatThrownBy(() -> SampleImporter.importSampleListings(
            repository, tempDir.resolve("missing.json").toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Sample listings file not found");

        assertThatThrownBy(() -> SampleImporter.parseSampleListings(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid JSON");

        assertThatThrownBy(() -> SampleImporter.parseSampleListings("{ bad json"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid JSON");

        assertThatThrownBy(() -> SampleImporter.parseSampleListings("{\"id\":\"1\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JSON array");
    }

    @Test
    void rejectsMissingOrInvalidRequiredAndOptionalFields() {
        List<String> invalidPayloads = List.of(
            "[null]",
            "[{\"id\":\"1\"}]",
            "[{\"id\":\" \",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"2\",\"address\":\"1 St\",\"city\":\"City\",\"price\":\"bad\",\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"3\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":\"two\",\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"4\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":123,\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"5\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"not-a-date\"}]",
            "[{\"id\":\"6\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"source\":\"\"}]",
            "[{\"id\":\"7\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"state\":123}]",
            "[{\"id\":\"8\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"bathrooms\":-1}]",
            "[{\"id\":\"9\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"sqft\":1250.5}]",
            "[{\"id\":\"10\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"sqft\":\"1250\"}]",
            "[{\"id\":\"11\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"latitude\":38.9}]",
            "[{\"id\":\"12\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"longitude\":-77.1}]",
            "[{\"id\":\"13\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"latitude\":95,\"longitude\":-77.1}]",
            "[{\"id\":\"14\",\"address\":\"1 St\",\"city\":\"City\",\"price\":100,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"latitude\":38.9,\"longitude\":-181}]"
        );

        for (String payload : invalidPayloads) {
            assertThatThrownBy(() -> SampleImporter.parseSampleListings(payload))
                .as("payload=%s", payload)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid listing record");
        }
    }

    @Test
    void runImportCliEmitsStructuredJsonLogInInMemoryMode() throws Exception {
        Path fixtureDir = tempDir.resolve("cli-workdir");
        Files.createDirectories(fixtureDir);
        try (var in = SampleImporterTest.class.getResourceAsStream("/sample_listings_fixture.json")) {
            assertThat(in).isNotNull();
            Files.copy(in, fixtureDir.resolve("sample_listings.json"));
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos, true, StandardCharsets.UTF_8);

        int count = SampleImporter.runImportCli(
            null,
            null,
            PostgresDataSourceFactory::createAndInitializeRepository,
            fixtureDir,
            capture
        );

        assertThat(count).isEqualTo(12);
        String line = baos.toString(StandardCharsets.UTF_8).trim();
        JsonNode json = MAPPER.readTree(line);
        assertThat(json.get("service").asText()).isEqualTo("real-estate-api");
        assertThat(json.get("severityText").asText()).isEqualTo("INFO");
        assertThat(json.get("body").asText()).isEqualTo("db.import.completed");
        assertThat(json.get("attributes").get("db.system").asText()).isEqualTo("postgresql");
        assertThat(json.get("attributes").get("db.target").asText()).isEqualTo("in-memory");
        assertThat(json.get("attributes").get("import.count").asInt()).isEqualTo(12);
        assertThat(json.get("attributes").has("import.path")).isFalse();
    }

    private Path copyFixtureToTempSampleFile() throws Exception {
        Path target = tempDir.resolve("sample_listings.json");
        try (var in = SampleImporterTest.class.getResourceAsStream("/sample_listings_fixture.json")) {
            assertThat(in).isNotNull();
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    @Test
    void runImportCliInPostgresModeLogsPathAndClosesRepository() throws Exception {
        Path explicitFile = tempDir.resolve("cli-pg.json");
        Files.writeString(explicitFile, """
            [{
              "id": "CLI-PG-1",
              "address": "500 Market St",
              "city": "Reston",
              "price": 550000,
              "bedrooms": 3,
              "listedDate": "2026-09-15T04:00:00+04:00",
              "description": "CLI Postgres import test"
            }]
            """, StandardCharsets.UTF_8);

        var closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        class CloseableInMemoryRepo extends InMemoryListingRepository implements AutoCloseable {
            @Override
            public void close() {
                closed.set(true);
            }
        }
        CloseableInMemoryRepo fakePgRepo = new CloseableInMemoryRepo();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos, true, StandardCharsets.UTF_8);

        int count = SampleImporter.runImportCli(
            explicitFile.toString(),
            "postgres://user:pass@localhost:5432/realestate",
            url -> fakePgRepo,
            capture
        );

        assertThat(count).isEqualTo(1);
        assertThat(closed).isTrue();

        JsonNode json = MAPPER.readTree(baos.toString(StandardCharsets.UTF_8).trim());
        assertThat(json.get("attributes").get("db.target").asText()).isEqualTo("postgres");
        assertThat(json.get("attributes").get("import.count").asInt()).isEqualTo(1);
        assertThat(json.get("attributes").get("import.path").asText()).isEqualTo(explicitFile.toString());

        // Also cover additional parse edge cases (sqft negative/overflow, non-numeric coords, bathrooms non-number, valid Instant/empty desc)
        assertThat(SampleImporter.parseSampleListings(
            "[{\"id\":\"OK-1\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100.6,\"bedrooms\":2,\"description\":\"\",\"listedDate\":\"2026-09-01T00:00:00Z\"}]"
        ).get(0).price()).isEqualTo(101);
        assertThatThrownBy(() -> SampleImporter.parseSampleListings(
            "[{\"id\":\"X\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100,\"bedrooms\":1,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"zip\":123}]"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleImporter.parseSampleListings(
            "[{\"id\":\"X\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100,\"bedrooms\":1,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"status\":\"  \"}]"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleImporter.parseSampleListings(
            "[{\"id\":\"X\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100,\"bedrooms\":1,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"latitude\":-91,\"longitude\":0}]"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleImporter.parseSampleListings(
            "[{\"id\":\"X\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100,\"bedrooms\":1,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"latitude\":0,\"longitude\":181}]"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleImporter.parseSampleListings(
            "[{\"id\":\"X\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100,\"bedrooms\":1,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"sqft\":-1}]"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleImporter.parseSampleListings(
            "[{\"id\":\"X\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100,\"bedrooms\":1,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"sqft\":3000000000}]"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleImporter.parseSampleListings(
            "[{\"id\":\"X\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100,\"bedrooms\":1,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"bathrooms\":\"two\"}]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleImporter.parseSampleListings(
            "[{\"id\":\"X\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100,\"bedrooms\":1,\"description\":\"d\",\"listedDate\":\"2026-09-01\",\"latitude\":\"38.9\",\"longitude\":-77.1}]")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPriceBedroomsAndSqftIntegerBoundsOverflow() {
        List<String> outOfBoundsPayloads = List.of(
            "[{\"id\":\"O1\",\"address\":\"1 St\",\"city\":\"C\",\"price\":2147483648,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"O2\",\"address\":\"1 St\",\"city\":\"C\",\"price\":1e15,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"O3\",\"address\":\"1 St\",\"city\":\"C\",\"price\":-2147483649,\"bedrooms\":2,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"O4\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100000,\"bedrooms\":2147483648,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"O5\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100000,\"bedrooms\":-2147483649,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"O6\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100000,\"bedrooms\":2,\"sqft\":2147483648,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]",
            "[{\"id\":\"O7\",\"address\":\"1 St\",\"city\":\"C\",\"price\":100000,\"bedrooms\":2,\"sqft\":-1,\"description\":\"d\",\"listedDate\":\"2026-09-01\"}]"
        );

        for (String payload : outOfBoundsPayloads) {
            assertThatThrownBy(() -> SampleImporter.parseSampleListings(payload))
                .as("payload=%s", payload)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void runImportCliExportsOtlpLogsWhenLogsEndpointIsConfigured() throws Exception {
        Path fixtureDir = tempDir.resolve("cli-otlp-workdir");
        Files.createDirectories(fixtureDir);
        try (var in = SampleImporterTest.class.getResourceAsStream("/sample_listings_fixture.json")) {
            assertThat(in).isNotNull();
            Files.copy(in, fixtureDir.resolve("sample_listings.json"));
        }

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
        String logsEndpoint = "http://127.0.0.1:" + mockCollector.getAddress().getPort() + "/v1/logs";

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream capture = new PrintStream(baos, true, StandardCharsets.UTF_8);
            TelemetryConfig telemetryConfig = new TelemetryConfig(
                "real-estate-importer-test",
                null,
                logsEndpoint,
                List.of(),
                List.of(),
                capture
            );

            int count = SampleImporter.runImportCli(
                null,
                null,
                PostgresDataSourceFactory::createAndInitializeRepository,
                fixtureDir,
                telemetryConfig
            );

            assertThat(count).isEqualTo(12);
            assertThat(receivedPaths).contains("/v1/logs");
            JsonNode json = MAPPER.readTree(baos.toString(StandardCharsets.UTF_8).trim());
            assertThat(json.get("service").asText()).isEqualTo("real-estate-importer-test");
            assertThat(json.get("body").asText()).isEqualTo("db.import.completed");
            assertThat(json.get("attributes").get("import.count").asInt()).isEqualTo(12);
        } finally {
            mockCollector.stop(0);
        }
    }
}
