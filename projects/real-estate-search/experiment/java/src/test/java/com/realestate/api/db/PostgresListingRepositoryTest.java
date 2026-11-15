package com.realestate.api.db;

import com.realestate.api.domain.Listing;
import com.realestate.api.domain.NewListing;
import com.realestate.api.domain.SearchCriteria;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresListingRepositoryTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private Tracer tracer;

    @BeforeEach
    void setUp() {
        OTEL.clearSpans();
        tracer = OTEL.getOpenTelemetry().getTracer("real-estate-api");
    }

    @Test
    void ensureSchemaExecutesExpectedDdlStatementsWithoutLegacyMigrations() {
        List<String> executedSql = new ArrayList<>();
        Statement stmt = proxy(Statement.class, (method, args) -> {
            if ("execute".equals(method)) {
                executedSql.add((String) args[0]);
                return true;
            }
            return defaultValue(method);
        });
        Connection conn = proxy(Connection.class, (method, args) -> {
            if ("createStatement".equals(method)) {
                return stmt;
            }
            return defaultValue(method);
        });
        PostgresListingRepository repo = new PostgresListingRepository(singleConnectionDataSource(conn), tracer);

        repo.ensureSchema();

        assertThat(executedSql).hasSize(8);
        assertThat(executedSql.get(0)).contains("CREATE TABLE IF NOT EXISTS listings")
            .contains("internal_id UUID PRIMARY KEY DEFAULT gen_random_uuid()")
            .contains("location POINT")
            .contains("listed_date TIMESTAMPTZ NOT NULL");
        assertThat(executedSql.get(1)).contains("listings_id_source_unique");
        assertThat(executedSql.get(2)).contains("listings_location_gist");
        assertThat(executedSql.get(3)).contains("listings_listed_date_desc");
        assertThat(executedSql.get(4)).contains("listings_price_idx");
        assertThat(executedSql.get(5)).contains("listings_bedrooms_idx");
        assertThat(executedSql.get(6)).contains("listings_city_lower_idx");
        assertThat(executedSql.get(7)).contains("pg_trgm").contains("listings_description_trgm");
        assertThat(executedSql).noneMatch(sql -> sql.contains("ALTER TABLE"));
    }

    @Test
    void ensureSchemaWrapsSqlExceptionInPersistenceException() {
        Connection conn = proxy(Connection.class, (method, args) -> {
            if ("createStatement".equals(method)) {
                throw new SQLException("DDL failed");
            }
            return defaultValue(method);
        });
        PostgresListingRepository repo = new PostgresListingRepository(singleConnectionDataSource(conn), tracer);

        assertThatThrownBy(repo::ensureSchema)
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("Failed to ensure PostgreSQL listings schema");
    }

    @Test
    void countMatchingListingsEmitsOtelClientSpanAndReturnsCount() {
        Map<Integer, Object> boundParams = new HashMap<>();
        List<String> capturedSql = new ArrayList<>();

        ResultSet rs = proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> true;
            case "getInt" -> 7;
            default -> defaultValue(method);
        });
        List<Integer> timeouts = new ArrayList<>();
        PreparedStatement ps = proxy(PreparedStatement.class, (method, args) -> {
            if ("setQueryTimeout".equals(method)) {
                timeouts.add((Integer) args[0]);
                return null;
            }
            if ("setObject".equals(method)) {
                boundParams.put((Integer) args[0], args[1]);
                return null;
            }
            if ("executeQuery".equals(method)) {
                return rs;
            }
            return defaultValue(method);
        });
        Connection conn = proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                capturedSql.add((String) args[0]);
                return ps;
            }
            return defaultValue(method);
        });

        PostgresListingRepository repo = new PostgresListingRepository(singleConnectionDataSource(conn), tracer);
        SearchCriteria criteria = new SearchCriteria(300000.0, null, 2, "Austin", "pool", null, 1, 10);

        int count = repo.countMatchingListings(criteria);

        assertThat(count).isEqualTo(7);
        assertThat(timeouts).containsExactly(PostgresListingRepository.QUERY_TIMEOUT_SECONDS);
        assertThat(capturedSql.get(0)).contains("SELECT COUNT(*)::int FROM listings WHERE");
        assertThat(boundParams).containsEntry(1, BigDecimal.valueOf(300000.0));
        assertThat(boundParams).containsEntry(2, 2);
        assertThat(boundParams).containsEntry(3, "Austin");
        assertThat(boundParams).containsEntry(4, "%pool%");

        List<SpanData> spans = OTEL.getSpans();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("search.db.count");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.system"))).isEqualTo("postgresql");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.operation.name"))).isEqualTo("SELECT");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("db.collection.name"))).isEqualTo("listings");
        assertThat(span.getAttributes().get(AttributeKey.longKey("search.totalResults"))).isEqualTo(7L);
    }

    @Test
    void countMatchingListingsRecordsExceptionAndErrorStatusOnSqlFailure() {
        Connection conn = proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                throw new SQLException("Query timeout");
            }
            return defaultValue(method);
        });
        PostgresListingRepository repo = new PostgresListingRepository(singleConnectionDataSource(conn), tracer);
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null, 1, 10);

        assertThatThrownBy(() -> repo.countMatchingListings(criteria))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("Failed to count matching listings");

        List<SpanData> spans = OTEL.getSpans();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(spans.get(0).getEvents()).anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    void fetchListingPageMapsRowsNormalizesDatesAndEmitsClientSpan() {
        UUID uuid1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID uuid2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

        List<Map<String, Object>> rows = List.of(
            Map.ofEntries(
                Map.entry("internal_id", uuid1),
                Map.entry("id", "LST-001"),
                Map.entry("source", "SEED"),
                Map.entry("address", "101 Congress Ave"),
                Map.entry("city", "Austin"),
                Map.entry("state", "TX"),
                Map.entry("zip", "78701"),
                Map.entry("price", 450000),
                Map.entry("bedrooms", 3),
                Map.entry("bathrooms", 2.5),
                Map.entry("sqft", 1550),
                Map.entry("latitude", 30.2638),
                Map.entry("longitude", -97.7443),
                Map.entry("status", "active"),
                Map.entry("description", "Pool and gym"),
                Map.entry("listed_date", OffsetDateTime.of(2026, 10, 20, 0, 0, 0, 0, ZoneOffset.UTC)),
                Map.entry("relevance_score", 0.92)
            ),
            new HashMap<>() {{
                put("internal_id", uuid2);
                put("id", "LST-002");
                put("source", "IMPORT");
                put("address", "202 Oak St");
                put("city", "Austin");
                put("state", null);
                put("zip", null);
                put("price", 350000);
                put("bedrooms", 2);
                put("bathrooms", null);
                put("sqft", null);
                put("latitude", null);
                put("longitude", null);
                put("status", "pending");
                put("description", "Cozy condo");
                put("listed_date", Timestamp.from(Instant.parse("2026-09-15T12:30:00Z")));
                put("relevance_score", 0.78);
            }}
        );

        final int[] rowIdx = { -1 };
        ResultSet rs = proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> ++rowIdx[0] < rows.size();
            case "getObject" -> rows.get(rowIdx[0]).get((String) args[0]);
            case "getString" -> (String) rows.get(rowIdx[0]).get((String) args[0]);
            case "getInt" -> ((Number) rows.get(rowIdx[0]).get((String) args[0])).intValue();
            case "getDouble" -> ((Number) rows.get(rowIdx[0]).get((String) args[0])).doubleValue();
            default -> defaultValue(method);
        });
        PreparedStatement ps = proxy(PreparedStatement.class, (method, args) -> {
            if ("executeQuery".equals(method)) {
                return rs;
            }
            return defaultValue(method);
        });
        Connection conn = proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                return ps;
            }
            return defaultValue(method);
        });

        PostgresListingRepository repo = new PostgresListingRepository(singleConnectionDataSource(conn), tracer);
        SearchCriteria criteria = new SearchCriteria(null, null, null, "Austin", null, 500000.0, 1, 10);

        List<Listing> listings = repo.fetchListingPage(criteria, 0);

        assertThat(listings).hasSize(2);
        Listing first = listings.get(0);
        assertThat(first.internalId()).isEqualTo(uuid1.toString());
        assertThat(first.id()).isEqualTo("LST-001");
        assertThat(first.bathrooms()).isEqualTo(2.5);
        assertThat(first.sqft()).isEqualTo(1550);
        assertThat(first.latitude()).isEqualTo(30.2638);
        assertThat(first.longitude()).isEqualTo(-97.7443);
        assertThat(first.listedDate()).isEqualTo("2026-10-20T00:00:00.000Z");
        assertThat(first.relevanceScore()).isEqualTo(0.92);

        Listing second = listings.get(1);
        assertThat(second.internalId()).isEqualTo(uuid2.toString());
        assertThat(second.bathrooms()).isNull();
        assertThat(second.sqft()).isNull();
        assertThat(second.latitude()).isNull();
        assertThat(second.longitude()).isNull();
        assertThat(second.listedDate()).isEqualTo("2026-09-15T12:30:00.000Z");

        List<SpanData> spans = OTEL.getSpans();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("search.db.query");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.longKey("search.page"))).isEqualTo(1L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("search.pageSize"))).isEqualTo(10L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("search.returnedCount"))).isEqualTo(2L);
    }

    @Test
    void fetchListingPageRecordsErrorOnSqlFailure() {
        Connection conn = proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                throw new SQLException("Connection reset");
            }
            return defaultValue(method);
        });
        PostgresListingRepository repo = new PostgresListingRepository(singleConnectionDataSource(conn), tracer);
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null, 1, 10);

        assertThatThrownBy(() -> repo.fetchListingPage(criteria, 0))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("Failed to fetch listing page");

        assertThat(OTEL.getSpans()).hasSize(1);
        assertThat(OTEL.getSpans().get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void insertListingsIdempotentlyBatchesParametersAndCommits() {
        AtomicBoolean connectionOpened = new AtomicBoolean(false);
        DataSource unusedDs = proxy(DataSource.class, (method, args) -> {
            if ("getConnection".equals(method)) {
                connectionOpened.set(true);
            }
            return defaultValue(method);
        });
        PostgresListingRepository emptyRepo = new PostgresListingRepository(unusedDs, tracer);
        assertThat(emptyRepo.insertListingsIdempotently(List.of())).isZero();
        assertThat(emptyRepo.insertListingsIdempotently(null)).isZero();
        assertThat(connectionOpened).isFalse();

        List<Map<Integer, Object>> batches = new ArrayList<>();
        Map<Integer, Object> currentBatch = new HashMap<>();
        AtomicBoolean committed = new AtomicBoolean(false);
        List<Boolean> autoCommitHistory = new ArrayList<>();

        PreparedStatement ps = proxy(PreparedStatement.class, (method, args) -> {
            switch (method) {
                case "setString", "setInt", "setDouble" -> currentBatch.put((Integer) args[0], args[1]);
                case "setNull" -> currentBatch.put((Integer) args[0], "NULL:" + args[1]);
                case "addBatch" -> {
                    batches.add(new HashMap<>(currentBatch));
                    currentBatch.clear();
                }
                case "executeBatch" -> {
                    return new int[] { 1, 0 };
                }
                default -> {
                    return defaultValue(method);
                }
            }
            return null;
        });
        Connection conn = proxy(Connection.class, (method, args) -> switch (method) {
            case "getAutoCommit" -> true;
            case "setAutoCommit" -> {
                autoCommitHistory.add((Boolean) args[0]);
                yield null;
            }
            case "prepareStatement" -> ps;
            case "commit" -> {
                committed.set(true);
                yield null;
            }
            default -> defaultValue(method);
        });

        PostgresListingRepository repo = new PostgresListingRepository(singleConnectionDataSource(conn), tracer);
        NewListing withCoords = new NewListing(
            "A1", "MLS_A", "123 Main St", "Springfield", "VA", "22150",
            450000, 2, 1.5, 980, 38.7893, -77.1873, "active", "Condo", "2026-08-29"
        );
        NewListing minimalDefaults = new NewListing(
            "A2", null, "456 Oak Ave", "Springfield", null, null,
            525000, 3, null, null, null, null, " ", "Home", "2026-09-02T00:00:00.000Z"
        );

        int inserted = repo.insertListingsIdempotently(List.of(withCoords, minimalDefaults));

        assertThat(inserted).isEqualTo(1);
        assertThat(committed).isTrue();
        assertThat(autoCommitHistory).containsExactly(false, true);
        assertThat(batches).hasSize(2);
        // GeoJSON/OGC axis order (longitude, latitude)
        assertThat(batches.get(0)).containsEntry(13, -77.1873);
        assertThat(batches.get(0)).containsEntry(14, 38.7893);
        assertThat(batches.get(0)).containsEntry(17, "2026-08-29T00:00:00.000Z");
        // Defaults and NULLs on second row
        assertThat(batches.get(1)).containsEntry(2, "SEED");
        assertThat(batches.get(1)).containsEntry(18, "active");
        assertThat(batches.get(1)).containsEntry(5, "NULL:" + Types.VARCHAR);
        assertThat(batches.get(1)).containsEntry(9, "NULL:" + Types.DOUBLE);
        assertThat(batches.get(1)).containsEntry(10, "NULL:" + Types.INTEGER);
    }

    @Test
    void insertListingsIdempotentlyRollsBackOnFailure() {
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        List<Boolean> autoCommitHistory = new ArrayList<>();

        PreparedStatement ps = proxy(PreparedStatement.class, (method, args) -> {
            if ("executeBatch".equals(method)) {
                throw new SQLException("Batch failed");
            }
            return defaultValue(method);
        });
        Connection conn = proxy(Connection.class, (method, args) -> switch (method) {
            case "getAutoCommit" -> true;
            case "setAutoCommit" -> {
                autoCommitHistory.add((Boolean) args[0]);
                yield null;
            }
            case "prepareStatement" -> ps;
            case "rollback" -> {
                rolledBack.set(true);
                yield null;
            }
            default -> defaultValue(method);
        });

        PostgresListingRepository repo = new PostgresListingRepository(singleConnectionDataSource(conn), tracer);
        NewListing listing = new NewListing(
            "A1", "MLS_A", "123 Main St", "Springfield", "VA", "22150",
            450000, 2, 1.5, 980, 38.7893, -77.1873, "active", "Condo", "2026-08-29"
        );

        assertThatThrownBy(() -> repo.insertListingsIdempotently(List.of(listing)))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("Failed to insert listings idempotently");

        assertThat(rolledBack).isTrue();
        assertThat(autoCommitHistory).containsExactly(false, true);
    }

    @Test
    void insertListingsIdempotentlyHandlesSuccessNoInfoAndExecuteFailed() {
        NewListing listing = new NewListing(
            "A1", "MLS_A", "123 Main St", "Springfield", "VA", "22150",
            450000, 2, 1.5, 980, 38.7893, -77.1873, "active", "Condo", "2026-08-29"
        );

        AtomicBoolean committed = new AtomicBoolean(false);
        PreparedStatement noInfoPs = proxy(PreparedStatement.class, (method, args) -> {
            if ("executeBatch".equals(method)) {
                return new int[] { Statement.SUCCESS_NO_INFO, 0 };
            }
            return defaultValue(method);
        });
        Connection noInfoConn = proxy(Connection.class, (method, args) -> switch (method) {
            case "getAutoCommit" -> true;
            case "prepareStatement" -> noInfoPs;
            case "commit" -> {
                committed.set(true);
                yield null;
            }
            default -> defaultValue(method);
        });
        PostgresListingRepository noInfoRepo = new PostgresListingRepository(singleConnectionDataSource(noInfoConn), tracer);
        assertThat(noInfoRepo.insertListingsIdempotently(List.of(listing, listing))).isEqualTo(1);
        assertThat(committed).isTrue();

        AtomicBoolean rolledBack = new AtomicBoolean(false);
        PreparedStatement failedPs = proxy(PreparedStatement.class, (method, args) -> {
            if ("executeBatch".equals(method)) {
                return new int[] { Statement.EXECUTE_FAILED };
            }
            return defaultValue(method);
        });
        Connection failedConn = proxy(Connection.class, (method, args) -> switch (method) {
            case "getAutoCommit" -> true;
            case "prepareStatement" -> failedPs;
            case "rollback" -> {
                rolledBack.set(true);
                yield null;
            }
            default -> defaultValue(method);
        });
        PostgresListingRepository failedRepo = new PostgresListingRepository(singleConnectionDataSource(failedConn), tracer);
        assertThatThrownBy(() -> failedRepo.insertListingsIdempotently(List.of(listing)))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("EXECUTE_FAILED");
        assertThat(rolledBack).isTrue();
    }

    interface CloseableDataSource extends DataSource, AutoCloseable {
    }

    @Test
    void closeClosesAutoCloseableDataSourceAndPropagatesErrors() {
        AtomicBoolean closed = new AtomicBoolean(false);
        CloseableDataSource closeableDs = proxy(CloseableDataSource.class, (method, args) -> {
            if ("close".equals(method)) {
                closed.set(true);
                return null;
            }
            return defaultValue(method);
        });
        PostgresListingRepository repo = new PostgresListingRepository(closeableDs, tracer);
        repo.close();
        assertThat(closed).isTrue();

        CloseableDataSource failingDs = proxy(CloseableDataSource.class, (method, args) -> {
            if ("close".equals(method)) {
                throw new Exception("close failed");
            }
            return defaultValue(method);
        });
        PostgresListingRepository failingRepo = new PostgresListingRepository(failingDs, tracer);
        assertThatThrownBy(failingRepo::close)
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("Failed to close data source");

        // Non-AutoCloseable DataSource is a clean no-op
        DataSource plainDs = proxy(DataSource.class, (m, a) -> defaultValue(m));
        new PostgresListingRepository(plainDs, tracer).close();
    }

    @Test
    void normalizeTimestampStringHandlesVariousFormatsAndPreservesInvalid() {
        assertThat(PostgresListingRepository.normalizeTimestampString(null)).isNull();
        assertThat(PostgresListingRepository.normalizeTimestampString("  ")).isEqualTo("  ");
        assertThat(PostgresListingRepository.normalizeTimestampString("2026-08-29")).isEqualTo("2026-08-29T00:00:00.000Z");
        assertThat(PostgresListingRepository.normalizeTimestampString("2026-08-29T02:00:00+02:00")).isEqualTo("2026-08-29T00:00:00.000Z");
        assertThat(PostgresListingRepository.normalizeTimestampString("not-a-date")).isEqualTo("not-a-date");
    }

    @Test
    void handlesRuntimeExceptionsAndInstantOrStringListedDateValues() {
        Connection failingConn = proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                throw new IllegalStateException("Unexpected runtime failure");
            }
            return defaultValue(method);
        });
        PostgresListingRepository failingRepo = new PostgresListingRepository(singleConnectionDataSource(failingConn), tracer);
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null, 1, 10);

        assertThatThrownBy(() -> failingRepo.countMatchingListings(criteria))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failingRepo.fetchListingPage(criteria, 0))
            .isInstanceOf(IllegalStateException.class);
        assertThat(OTEL.getSpans()).hasSize(2);
        assertThat(OTEL.getSpans()).allSatisfy(s ->
            assertThat(s.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR)
        );

        List<Map<String, Object>> rows = List.of(
            new HashMap<>() {{
                put("internal_id", null);
                put("id", "INST-1");
                put("source", "SEED");
                put("address", "1 Instant St");
                put("city", "Austin");
                put("state", null);
                put("zip", null);
                put("price", 400000);
                put("bedrooms", 2);
                put("bathrooms", null);
                put("sqft", null);
                put("latitude", null);
                put("longitude", null);
                put("status", "active");
                put("description", "Instant date");
                put("listed_date", Instant.parse("2026-10-01T00:00:00Z"));
                put("relevance_score", 0.5);
            }},
            new HashMap<>() {{
                put("internal_id", null);
                put("id", "STR-1");
                put("source", "SEED");
                put("address", "2 String St");
                put("city", "Austin");
                put("state", null);
                put("zip", null);
                put("price", 410000);
                put("bedrooms", 2);
                put("bathrooms", null);
                put("sqft", null);
                put("latitude", null);
                put("longitude", null);
                put("status", "active");
                put("description", "Null date");
                put("listed_date", null);
                put("relevance_score", 0.5);
            }}
        );
        final int[] idx = { -1 };
        ResultSet rs = proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> ++idx[0] < rows.size();
            case "getObject" -> rows.get(idx[0]).get((String) args[0]);
            case "getString" -> (String) rows.get(idx[0]).get((String) args[0]);
            case "getInt" -> ((Number) rows.get(idx[0]).get((String) args[0])).intValue();
            case "getDouble" -> ((Number) rows.get(idx[0]).get((String) args[0])).doubleValue();
            default -> defaultValue(method);
        });
        PreparedStatement ps = proxy(PreparedStatement.class, (m, a) -> "executeQuery".equals(m) ? rs : defaultValue(m));
        Connection okConn = proxy(Connection.class, (m, a) -> "prepareStatement".equals(m) ? ps : defaultValue(m));

        List<Listing> mapped = new PostgresListingRepository(singleConnectionDataSource(okConn))
            .fetchListingPage(criteria, 0);
        assertThat(mapped).hasSize(2);
        assertThat(mapped.get(0).internalId()).isNull();
        assertThat(mapped.get(0).listedDate()).isEqualTo("2026-10-01T00:00:00.000Z");
        assertThat(mapped.get(1).listedDate()).isNull();
    }

    @FunctionalInterface
    interface MethodHandler {
        Object invoke(String methodName, Object[] args) throws Throwable;
    }

    private static DataSource singleConnectionDataSource(Connection connection) {
        return proxy(DataSource.class, (method, args) -> {
            if ("getConnection".equals(method)) {
                return connection;
            }
            return defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, MethodHandler handler) {
        return (T) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[] { iface },
            (proxy, method, args) -> handler.invoke(method.getName(), args)
        );
    }

    private static Object defaultValue(String method) {
        return switch (method) {
            case "execute", "next", "getAutoCommit", "isWrapperFor" -> false;
            case "getInt", "executeUpdate" -> 0;
            case "getDouble" -> 0.0;
            case "executeBatch" -> new int[0];
            default -> null;
        };
    }
}
