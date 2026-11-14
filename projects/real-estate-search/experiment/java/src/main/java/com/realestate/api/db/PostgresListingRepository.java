package com.realestate.api.db;

import com.realestate.api.domain.Listing;
import com.realestate.api.domain.ListingRepository;
import com.realestate.api.domain.NewListing;
import com.realestate.api.domain.SearchCriteria;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL JDBC implementation of {@link ListingRepository} backed by a {@link DataSource}
 * (typically HikariCP) with OpenTelemetry {@code CLIENT} spans for search queries.
 */
public class PostgresListingRepository implements ListingRepository, AutoCloseable {

    private static final DateTimeFormatter ISO_UTC_MILLIS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    private static final List<String> SCHEMA_DDL = List.of(
        """
        CREATE TABLE IF NOT EXISTS listings (
          internal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          id TEXT NOT NULL,
          source TEXT NOT NULL DEFAULT 'SEED',
          address TEXT NOT NULL,
          city TEXT NOT NULL,
          state TEXT,
          zip TEXT,
          price INTEGER NOT NULL,
          bedrooms INTEGER NOT NULL,
          bathrooms DOUBLE PRECISION,
          sqft INTEGER,
          latitude DOUBLE PRECISION,
          longitude DOUBLE PRECISION,
          location POINT,
          listed_date TIMESTAMPTZ NOT NULL,
          status TEXT NOT NULL DEFAULT 'active',
          description TEXT NOT NULL
        )
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS listings_id_source_unique ON listings (id, source)",
        "CREATE INDEX IF NOT EXISTS listings_location_gist ON listings USING gist (location)",
        "CREATE INDEX IF NOT EXISTS listings_listed_date_desc ON listings (listed_date DESC)",
        "CREATE INDEX IF NOT EXISTS listings_price_idx ON listings (price)",
        "CREATE INDEX IF NOT EXISTS listings_bedrooms_idx ON listings (bedrooms)",
        "CREATE INDEX IF NOT EXISTS listings_city_lower_idx ON listings (LOWER(city))",
        """
        DO $$
        BEGIN
          IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_trgm') THEN
            CREATE EXTENSION IF NOT EXISTS pg_trgm;
            CREATE INDEX IF NOT EXISTS listings_description_trgm
              ON listings USING gin (description gin_trgm_ops);
          END IF;
        END $$
        """
    );

    private static final String INSERT_SQL = """
        INSERT INTO listings (
          id, source, address, city, state, zip, price, bedrooms,
          bathrooms, sqft, latitude, longitude, location, listed_date, status, description
        ) VALUES (
          ?, ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?,
          CASE WHEN ?::double precision IS NOT NULL AND ?::double precision IS NOT NULL
            THEN point(?::double precision, ?::double precision)
            ELSE NULL
          END,
          ?::timestamptz, ?, ?
        )
        ON CONFLICT (id, source) DO NOTHING
        """;

    private final DataSource dataSource;
    private final Tracer tracer;

    public PostgresListingRepository(DataSource dataSource) {
        this(dataSource, GlobalOpenTelemetry.getTracer("real-estate-api"));
    }

    public PostgresListingRepository(DataSource dataSource, Tracer tracer) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
    }

    @Override
    public void ensureSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String ddl : SCHEMA_DDL) {
                stmt.execute(ddl);
            }
        } catch (SQLException ex) {
            throw new PersistenceException("Failed to ensure PostgreSQL listings schema", ex);
        }
    }

    @Override
    public int countMatchingListings(SearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Span span = tracer.spanBuilder("search.db.count")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("db.system", "postgresql")
            .setAttribute("db.operation.name", "SELECT")
            .setAttribute("db.collection.name", "listings")
            .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            SearchQueryBuilder.SqlQuery query = SearchQueryBuilder.buildCountQuery(criteria);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(query.sql())) {
                bindParameters(ps, query.parameters());
                try (ResultSet rs = ps.executeQuery()) {
                    int count = rs.next() ? rs.getInt(1) : 0;
                    span.setAttribute("search.totalResults", (long) count);
                    return count;
                }
            }
        } catch (SQLException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            throw new PersistenceException("Failed to count matching listings", ex);
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            throw ex;
        } finally {
            span.end();
        }
    }

    @Override
    public List<Listing> fetchListingPage(SearchCriteria criteria, int offset) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Span span = tracer.spanBuilder("search.db.query")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("db.system", "postgresql")
            .setAttribute("db.operation.name", "SELECT")
            .setAttribute("db.collection.name", "listings")
            .setAttribute("search.page", (long) criteria.page())
            .setAttribute("search.pageSize", (long) criteria.pageSize())
            .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            SearchQueryBuilder.SqlQuery query = SearchQueryBuilder.buildPageQuery(criteria, offset);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(query.sql())) {
                bindParameters(ps, query.parameters());
                List<Listing> results = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapListingRow(rs));
                    }
                }
                span.setAttribute("search.returnedCount", (long) results.size());
                return List.copyOf(results);
            }
        } catch (SQLException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            throw new PersistenceException("Failed to fetch listing page", ex);
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, ex.getMessage());
            throw ex;
        } finally {
            span.end();
        }
    }

    @Override
    public int insertListingsIdempotently(List<NewListing> listings) {
        if (listings == null || listings.isEmpty()) {
            return 0;
        }

        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (NewListing listing : listings) {
                    bindInsertStatement(ps, listing);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                conn.commit();

                int inserted = 0;
                for (int count : counts) {
                    if (count > 0) {
                        inserted += count;
                    }
                }
                return inserted;
            } catch (SQLException | RuntimeException ex) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    ex.addSuppressed(rollbackEx);
                }
                throw ex;
            } finally {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                    // Preserve original outcome
                }
            }
        } catch (SQLException ex) {
            throw new PersistenceException("Failed to insert listings idempotently", ex);
        }
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ex) {
                throw new PersistenceException("Failed to close data source", ex);
            }
        }
    }

    static Listing mapListingRow(ResultSet rs) throws SQLException {
        Object internalIdObj = rs.getObject("internal_id");
        String internalId = internalIdObj != null ? internalIdObj.toString() : null;

        return new Listing(
            internalId,
            rs.getString("id"),
            rs.getString("source"),
            rs.getString("address"),
            rs.getString("city"),
            rs.getString("state"),
            rs.getString("zip"),
            rs.getInt("price"),
            rs.getInt("bedrooms"),
            getNullableDouble(rs, "bathrooms"),
            getNullableInteger(rs, "sqft"),
            getNullableDouble(rs, "latitude"),
            getNullableDouble(rs, "longitude"),
            rs.getString("status"),
            rs.getString("description"),
            normalizeListedDate(rs),
            rs.getDouble("relevance_score")
        );
    }

    static String normalizeTimestampString(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String trimmed = raw.trim();
        try {
            return ISO_UTC_MILLIS.format(Instant.parse(trimmed));
        } catch (DateTimeParseException ignored) {
            try {
                return ISO_UTC_MILLIS.format(OffsetDateTime.parse(trimmed).toInstant());
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return ISO_UTC_MILLIS.format(LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant());
                } catch (DateTimeParseException ignoredDate) {
                    return raw;
                }
            }
        }
    }

    private static String normalizeListedDate(ResultSet rs) throws SQLException {
        Object raw = rs.getObject("listed_date");
        if (raw == null) {
            return null;
        }
        if (raw instanceof OffsetDateTime odt) {
            return ISO_UTC_MILLIS.format(odt.toInstant());
        }
        if (raw instanceof Instant instant) {
            return ISO_UTC_MILLIS.format(instant);
        }
        if (raw instanceof Timestamp ts) {
            return ISO_UTC_MILLIS.format(ts.toInstant());
        }
        return normalizeTimestampString(raw.toString());
    }

    private static Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value != null ? ((Number) value).doubleValue() : null;
    }

    private static Integer getNullableInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value != null ? ((Number) value).intValue() : null;
    }

    private static void bindParameters(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private static void bindInsertStatement(PreparedStatement ps, NewListing listing) throws SQLException {
        String source = listing.source() != null && !listing.source().isBlank() ? listing.source() : "SEED";
        String status = listing.status() != null && !listing.status().isBlank() ? listing.status() : "active";

        ps.setString(1, listing.id());
        ps.setString(2, source);
        ps.setString(3, listing.address());
        ps.setString(4, listing.city());
        setNullableString(ps, 5, listing.state());
        setNullableString(ps, 6, listing.zip());
        ps.setInt(7, listing.price());
        ps.setInt(8, listing.bedrooms());
        setNullableDouble(ps, 9, listing.bathrooms());
        setNullableInteger(ps, 10, listing.sqft());
        setNullableDouble(ps, 11, listing.latitude());
        setNullableDouble(ps, 12, listing.longitude());
        // GeoJSON/OGC axis order: x = longitude, y = latitude
        setNullableDouble(ps, 13, listing.longitude());
        setNullableDouble(ps, 14, listing.latitude());
        setNullableDouble(ps, 15, listing.longitude());
        setNullableDouble(ps, 16, listing.latitude());
        ps.setString(17, normalizeTimestampString(listing.listedDate()));
        ps.setString(18, status);
        ps.setString(19, listing.description());
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static void setNullableInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
