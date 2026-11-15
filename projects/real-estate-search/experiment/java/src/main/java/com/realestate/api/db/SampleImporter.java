package com.realestate.api.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realestate.api.domain.ListingRepository;
import com.realestate.api.domain.NewListing;
import com.realestate.api.scoring.DefaultRelevanceScorer;
import com.realestate.api.telemetry.ApiTelemetry;
import com.realestate.api.telemetry.TelemetryConfig;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Tracer;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Sample listings JSON parser and standalone idempotent CLI importer matching
 * {@code packages/api/src/db/importSample.ts}.
 */
public final class SampleImporter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_UTC_MILLIS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    private SampleImporter() {
    }

    /**
     * Resolves an explicit file path or searches standard locations for {@code sample_listings.json}.
     */
    public static Path resolveSampleListingsPath(String explicitPath) {
        return resolveSampleListingsPath(explicitPath, Path.of("").toAbsolutePath().normalize());
    }

    static Path resolveSampleListingsPath(String explicitPath, Path workingDir) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            return workingDir.resolve(explicitPath).toAbsolutePath().normalize();
        }

        Path cwd = workingDir.toAbsolutePath().normalize();
        List<Path> candidates = List.of(
            cwd.resolve("sample_listings.json"),
            cwd.resolve("../sample_listings.json").normalize(),
            cwd.resolve("../../sample_listings.json").normalize(),
            cwd.resolve("../../../sample_listings.json").normalize(),
            Path.of("/app/sample_listings.json")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return cwd.resolve("sample_listings.json");
    }

    /**
     * Parses and validates a JSON array string of sample listing records.
     */
    public static List<NewListing> parseSampleListings(String jsonContent) {
        if (jsonContent == null) {
            throw new IllegalArgumentException("Sample listings file contains invalid JSON: input is null");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonContent);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Sample listings file contains invalid JSON: " + ex.getMessage(), ex);
        }

        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Sample listings file must contain a JSON array of listing objects.");
        }

        List<NewListing> records = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            records.add(parseRecord(root.get(i), i));
        }
        return List.copyOf(records);
    }

    /**
     * Reads, validates, and idempotently imports sample listings from disk into {@code repository}.
     */
    public static int importSampleListings(ListingRepository repository, String explicitPath) {
        return importSampleListings(repository, explicitPath, Path.of("").toAbsolutePath().normalize());
    }

    static int importSampleListings(ListingRepository repository, String explicitPath, Path workingDir) {
        Objects.requireNonNull(repository, "repository must not be null");
        Path resolved = resolveSampleListingsPath(explicitPath, workingDir);
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Sample listings file not found at: " + resolved);
        }

        String rawContent;
        try {
            rawContent = Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new PersistenceException("Failed to read sample listings file at " + resolved + ": " + ex.getMessage(), ex);
        }

        List<NewListing> records = parseSampleListings(rawContent);
        if (records.isEmpty()) {
            return 0;
        }
        return repository.insertListingsIdempotently(records);
    }

    /**
     * Runs the standalone CLI import workflow against PostgreSQL when {@code databaseUrl} is set,
     * or an in-memory repository otherwise, emitting OpenTelemetry structured logs and OTLP exports.
     */
    public static int runImportCli(String explicitPath, String databaseUrl) {
        return runImportCli(explicitPath, databaseUrl, System.out);
    }

    static int runImportCli(String explicitPath, String databaseUrl, PrintStream out) {
        return runImportCli(
            explicitPath,
            databaseUrl,
            PostgresDataSourceFactory::createAndInitializeRepository,
            Path.of("").toAbsolutePath().normalize(),
            withConsoleOut(TelemetryConfig.fromEnv(), out)
        );
    }

    static int runImportCli(
        String explicitPath,
        String databaseUrl,
        Function<String, ListingRepository> postgresRepoFactory,
        PrintStream out
    ) {
        return runImportCli(
            explicitPath,
            databaseUrl,
            postgresRepoFactory,
            Path.of("").toAbsolutePath().normalize(),
            out
        );
    }

    static int runImportCli(
        String explicitPath,
        String databaseUrl,
        Function<String, ListingRepository> postgresRepoFactory,
        Path workingDir,
        PrintStream out
    ) {
        Objects.requireNonNull(postgresRepoFactory, "postgresRepoFactory must not be null");
        return runImportCli(
            explicitPath,
            databaseUrl,
            (url, tracer) -> postgresRepoFactory.apply(url),
            workingDir,
            withConsoleOut(TelemetryConfig.fromEnv(), out)
        );
    }

    static int runImportCli(
        String explicitPath,
        String databaseUrl,
        BiFunction<String, Tracer, ListingRepository> postgresRepoFactory,
        Path workingDir,
        TelemetryConfig telemetryConfig
    ) {
        Objects.requireNonNull(postgresRepoFactory, "postgresRepoFactory must not be null");
        Objects.requireNonNull(telemetryConfig, "telemetryConfig must not be null");

        boolean hasDatabaseUrl = databaseUrl != null && !databaseUrl.isBlank();
        try (ApiTelemetry telemetry = ApiTelemetry.create(telemetryConfig)) {
            ListingRepository repository;
            AutoCloseable closeable = null;

            if (hasDatabaseUrl) {
                repository = postgresRepoFactory.apply(databaseUrl, telemetry.tracer());
                if (repository instanceof AutoCloseable autoCloseable) {
                    closeable = autoCloseable;
                }
            } else {
                repository = new InMemoryListingRepository(new DefaultRelevanceScorer(), telemetry.tracer());
                repository.ensureSchema();
            }

            try {
                int count = importSampleListings(repository, explicitPath, workingDir);
                AttributesBuilder attrs = Attributes.builder()
                    .put("db.system", "postgresql")
                    .put("db.target", hasDatabaseUrl ? "postgres" : "in-memory")
                    .put("import.count", (long) count);
                if (explicitPath != null && !explicitPath.isBlank()) {
                    attrs.put("import.path", explicitPath);
                }
                telemetry.log(Severity.INFO, "INFO", "db.import.completed", attrs.build());
                return count;
            } finally {
                if (closeable != null) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                        // Best-effort close on CLI exit
                    }
                }
            }
        }
    }

    private static TelemetryConfig withConsoleOut(TelemetryConfig base, PrintStream out) {
        return new TelemetryConfig(
            base.serviceName(),
            base.tracesEndpoint(),
            base.logsEndpoint(),
            base.additionalSpanProcessors(),
            base.additionalLogProcessors(),
            out
        );
    }

    /**
     * CLI entrypoint: {@code java -cp ... com.realestate.api.db.SampleImporter [path/to/sample_listings.json]}.
     */
    public static void main(String[] args) {
        String explicitPath = args != null && args.length > 0 ? args[0] : null;
        String databaseUrl = System.getenv("DATABASE_URL");
        try {
            runImportCli(explicitPath, databaseUrl);
        } catch (RuntimeException ex) {
            System.err.println("Failed to import sample listings: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static NewListing parseRecord(JsonNode item, int index) {
        if (item == null || !item.isObject()) {
            throw missingRequiredFieldError(index);
        }

        String id = requireNonEmptyText(item, "id", index);
        String address = requireNonEmptyText(item, "address", index);
        String city = requireNonEmptyText(item, "city", index);
        double priceVal = requireFiniteNumber(item, "price", index);
        double bedroomsVal = requireFiniteNumber(item, "bedrooms", index);
        String description = requireText(item, "description", index);
        String listedDateRaw = requireNonEmptyText(item, "listedDate", index);
        String normalizedListedDate = parseAndNormalizeListedDate(listedDateRaw, index);

        String source = parseOptionalNonEmptyString(item, "source", index);
        if (source == null) {
            source = "IMPORT";
        }
        String state = parseOptionalNonEmptyString(item, "state", index);
        String zip = parseOptionalNonEmptyString(item, "zip", index);
        String status = parseOptionalNonEmptyString(item, "status", index);
        if (status == null) {
            status = "active";
        }

        Double bathrooms = parseOptionalBathrooms(item, index);
        Integer sqft = parseOptionalSqft(item, index);

        boolean hasLat = item.has("latitude");
        boolean hasLon = item.has("longitude");
        if (hasLat != hasLon) {
            throw new IllegalArgumentException(
                "Invalid listing record at index " + index
                    + ": latitude and longitude must both be provided or both omitted."
            );
        }

        Double latitude = null;
        Double longitude = null;
        if (hasLat) {
            latitude = parseCoordinate(item.get("latitude"), "latitude", -90.0, 90.0, index);
            longitude = parseCoordinate(item.get("longitude"), "longitude", -180.0, 180.0, index);
        }

        String formattedAddress = state != null && zip != null
            ? address + ", " + city + ", " + state + " " + zip
            : address;

        return new NewListing(
            id,
            source,
            formattedAddress,
            city,
            state,
            zip,
            roundToInt(priceVal, index),
            roundToInt(bedroomsVal, index),
            bathrooms,
            sqft,
            latitude,
            longitude,
            status,
            description,
            normalizedListedDate
        );
    }

    private static int roundToInt(double value, int index) {
        if (value < (double) Integer.MIN_VALUE - 0.5 || value > (double) Integer.MAX_VALUE + 0.5) {
            throw missingRequiredFieldError(index);
        }
        long rounded = Math.round(value);
        if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
            throw missingRequiredFieldError(index);
        }
        return (int) rounded;
    }

    private static String requireNonEmptyText(JsonNode item, String field, int index) {
        JsonNode node = item.get(field);
        if (node == null || !node.isTextual() || node.asText().trim().isEmpty()) {
            throw missingRequiredFieldError(index);
        }
        return node.asText().trim();
    }

    private static String requireText(JsonNode item, String field, int index) {
        JsonNode node = item.get(field);
        if (node == null || !node.isTextual()) {
            throw missingRequiredFieldError(index);
        }
        return node.asText();
    }

    private static double requireFiniteNumber(JsonNode item, String field, int index) {
        JsonNode node = item.get(field);
        if (node == null || !node.isNumber()) {
            throw missingRequiredFieldError(index);
        }
        double value = node.asDouble();
        if (!Double.isFinite(value)) {
            throw missingRequiredFieldError(index);
        }
        return value;
    }

    private static String parseOptionalNonEmptyString(JsonNode item, String field, int index) {
        if (!item.has(field)) {
            return null;
        }
        JsonNode node = item.get(field);
        if (!node.isTextual() || node.asText().trim().isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid listing record at index " + index
                    + ": optional field \"" + field + "\" must be a non-empty string."
            );
        }
        return node.asText().trim();
    }

    private static Double parseOptionalBathrooms(JsonNode item, int index) {
        if (!item.has("bathrooms")) {
            return null;
        }
        JsonNode node = item.get("bathrooms");
        if (!node.isNumber() || !Double.isFinite(node.asDouble()) || node.asDouble() < 0.0) {
            throw new IllegalArgumentException(
                "Invalid listing record at index " + index
                    + ": optional field \"bathrooms\" must be a finite number >= 0."
            );
        }
        return node.asDouble();
    }

    private static Integer parseOptionalSqft(JsonNode item, int index) {
        if (!item.has("sqft")) {
            return null;
        }
        JsonNode node = item.get("sqft");
        if (!node.isNumber()) {
            throw invalidSqftError(index);
        }
        BigDecimal bd = node.decimalValue();
        if (bd.stripTrailingZeros().scale() > 0 || bd.compareTo(BigDecimal.ZERO) < 0
            || bd.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw invalidSqftError(index);
        }
        return bd.intValueExact();
    }

    private static Double parseCoordinate(JsonNode node, String field, double min, double max, int index) {
        if (node == null || !node.isNumber()) {
            throw invalidCoordinateError(field, min, max, index);
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < min || value > max) {
            throw invalidCoordinateError(field, min, max, index);
        }
        return value;
    }

    private static String parseAndNormalizeListedDate(String raw, int index) {
        try {
            return ISO_UTC_MILLIS.format(Instant.parse(raw));
        } catch (DateTimeParseException ignored) {
            try {
                return ISO_UTC_MILLIS.format(OffsetDateTime.parse(raw).toInstant());
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return ISO_UTC_MILLIS.format(LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant());
                } catch (DateTimeParseException ignoredDate) {
                    throw missingRequiredFieldError(index);
                }
            }
        }
    }

    private static IllegalArgumentException missingRequiredFieldError(int index) {
        return new IllegalArgumentException(
            "Invalid listing record at index " + index
                + ": required fields (id, address, city, price, bedrooms, description, listedDate) are missing or invalid."
        );
    }

    private static IllegalArgumentException invalidSqftError(int index) {
        return new IllegalArgumentException(
            "Invalid listing record at index " + index
                + ": optional field \"sqft\" must be a non-negative integer."
        );
    }

    private static IllegalArgumentException invalidCoordinateError(String field, double min, double max, int index) {
        return new IllegalArgumentException(
            "Invalid listing record at index " + index
                + ": " + field + " must be a finite number between " + (int) min + " and " + (int) max + "."
        );
    }
}
