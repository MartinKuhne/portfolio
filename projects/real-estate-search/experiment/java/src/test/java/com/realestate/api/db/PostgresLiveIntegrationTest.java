package com.realestate.api.db;

import com.realestate.api.domain.Listing;
import com.realestate.api.domain.NewListing;
import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.scoring.DefaultRelevanceScorer;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional live PostgreSQL integration test activated only when {@code PG_INTEGRATION_TEST_URL} is set.
 */
@EnabledIfEnvironmentVariable(named = "PG_INTEGRATION_TEST_URL", matches = ".+")
class PostgresLiveIntegrationTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    @Test
    void executesSchemaCreationIdempotentInsertAndSearchParityAgainstLivePostgres() throws Exception {
        String dbUrl = System.getenv("PG_INTEGRATION_TEST_URL");
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("real-estate-api");
        DefaultRelevanceScorer scorer = new DefaultRelevanceScorer();

        String tag = UUID.randomUUID().toString().substring(0, 8);
        String id = "PG-" + tag + "-1";
        String source = "LIVE_TEST";
        String city = "PgTest_" + tag;

        try (PostgresListingRepository repo = PostgresDataSourceFactory.createAndInitializeRepository(dbUrl, tracer)) {
            NewListing l1 = new NewListing(
                id,
                source,
                "100 Live PG Way",
                city,
                "TX",
                "78701",
                500000,
                3,
                2.5,
                1800,
                30.2672,
                -97.7431,
                "active",
                "100% live PostgreSQL test with pool",
                "2026-10-20T00:00:00.000Z"
            );

            try {
                int firstInsert = repo.insertListingsIdempotently(List.of(l1));
                int secondInsert = repo.insertListingsIdempotently(List.of(l1));
                assertThat(firstInsert).isEqualTo(1);
                assertThat(secondInsert).isZero();

                SearchCriteria criteria = new SearchCriteria(400000.0, 600000.0, 3, city.toLowerCase(), "100%", 500000.0, 1, 10);
                assertThat(repo.countMatchingListings(criteria)).isEqualTo(1);

                List<Listing> page = repo.fetchListingPage(criteria, 0);
                assertThat(page).hasSize(1);
                Listing fetched = page.get(0);
                assertThat(fetched.id()).isEqualTo(l1.id());
                assertThat(fetched.relevanceScore())
                    .isEqualTo(scorer.computeScore(fetched.price(), fetched.listedDate(), 500000.0));
            } finally {
                DataSource ds = PostgresDataSourceFactory.createDataSource(dbUrl);
                try (AutoCloseable ignored = ds instanceof AutoCloseable c ? c : null;
                     Connection conn = ds.getConnection();
                     PreparedStatement del = conn.prepareStatement("DELETE FROM listings WHERE id = ? AND source = ?")) {
                    del.setString(1, id);
                    del.setString(2, source);
                    del.executeUpdate();
                }
            }
        }
    }
}
