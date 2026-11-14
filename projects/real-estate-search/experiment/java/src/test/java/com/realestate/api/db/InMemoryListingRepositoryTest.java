package com.realestate.api.db;

import com.realestate.api.domain.Listing;
import com.realestate.api.domain.NewListing;
import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.scoring.DefaultRelevanceScorer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryListingRepositoryTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private InMemoryListingRepository repository;
    private DefaultRelevanceScorer scorer;

    @BeforeEach
    void setUp() {
        OTEL.clearSpans();
        scorer = new DefaultRelevanceScorer();
        repository = new InMemoryListingRepository(scorer, OTEL.getOpenTelemetry().getTracer("real-estate-api"));
        repository.ensureSchema();
    }

    @Test
    void seedDatabaseInserts24ListingsIdempotently() {
        int first = SeedListings.seedDatabase(repository);
        int second = SeedListings.seedDatabase(repository);

        assertThat(first).isEqualTo(24);
        assertThat(second).isZero();

        int count = repository.countMatchingListings(new SearchCriteria(null, null, null, null, null, null, 1, 50));
        assertThat(count).isEqualTo(24);
    }

    @Test
    void filtersByAllCriteriaWithCaseInsensitiveCityAndLiteralWildcardSafety() {
        SeedListings.seedDatabase(repository);

        SearchCriteria austinPool = new SearchCriteria(400000.0, 650000.0, 3, "  austin ", "  POOL ", null, 1, 10);
        int count = repository.countMatchingListings(austinPool);
        List<Listing> results = repository.fetchListingPage(austinPool, 0);

        assertThat(count).isEqualTo(3);
        assertThat(results).extracting(Listing::id).containsExactlyInAnyOrder("LST-001", "LST-002", "LST-004");
    }

    @Test
    void filtersRespectLiteralPercentAndUnderscoreInCityAndKeyword() {
        repository.insertListingsIdempotently(List.of(
            new NewListing("LIT-1", "SEED", "1 Literal Way", "100%_Ville", null, null,
                400000, 3, null, null, null, null, "active", "100% renovated kitchen", "2026-10-01T00:00:00.000Z"),
            new NewListing("LIT-2", "SEED", "2 Trap Rd", "100XYZ_Ville", null, null,
                410000, 3, null, null, null, null, "active", "1000 renovated kitchen", "2026-10-02T00:00:00.000Z")
        ));

        SearchCriteria literalCity = new SearchCriteria(null, null, null, "100%_vIlLe", null, null, 1, 10);
        assertThat(repository.countMatchingListings(literalCity)).isEqualTo(1);
        assertThat(repository.fetchListingPage(literalCity, 0)).extracting(Listing::id).containsExactly("LIT-1");

        SearchCriteria literalKeyword = new SearchCriteria(null, null, null, null, "100%", null, 1, 10);
        assertThat(repository.fetchListingPage(literalKeyword, 0)).extracting(Listing::id).containsExactly("LIT-1");
    }

    @Test
    void scoringAndDeterministicOrderingMatchScorerWithAndWithoutTargetBudget() {
        SeedListings.seedDatabase(repository);
        repository.insertListingsIdempotently(List.of(
            new NewListing("edge-ref-date", "SEED", "100 Ref St", "ParityCity", null, null,
                500000, 3, null, null, null, null, "active", "Ref", DefaultRelevanceScorer.REFERENCE_DATE_ISO),
            new NewListing("edge-future", "SEED", "200 Future Ave", "ParityCity", null, null,
                250000, 2, null, null, null, null, "active", "Future", "2026-12-15T12:00:00.000Z"),
            new NewListing("edge-old", "SEED", "300 Old Rd", "ParityCity", null, null,
                1200000, 4, null, null, null, null, "active", "Old", "2025-01-01T00:00:00.000Z")
        ));

        SearchCriteria withBudget = new SearchCriteria(null, null, null, null, null, 500000.0, 1, 100);
        List<Listing> budgetResults = repository.fetchListingPage(withBudget, 0);
        assertThat(budgetResults).hasSize(27);
        for (Listing item : budgetResults) {
            assertThat(item.relevanceScore())
                .isEqualTo(scorer.computeScore(item.price(), item.listedDate(), 500000.0));
        }
        for (int i = 1; i < budgetResults.size(); i++) {
            Listing prev = budgetResults.get(i - 1);
            Listing curr = budgetResults.get(i);
            assertThat(prev.relevanceScore()).isGreaterThanOrEqualTo(curr.relevanceScore());
        }

        SearchCriteria noBudget = new SearchCriteria(null, null, null, null, null, null, 1, 100);
        List<Listing> recencyResults = repository.fetchListingPage(noBudget, 0);
        assertThat(recencyResults).hasSize(27);
        for (int i = 1; i < recencyResults.size(); i++) {
            Listing prev = recencyResults.get(i - 1);
            Listing curr = recencyResults.get(i);
            assertThat(Instant.parse(prev.listedDate())).isAfterOrEqualTo(Instant.parse(curr.listedDate()));
        }
    }

    @Test
    void tieBreaksByListedDateDescIdAscSourceAscAndEnforcesCompositeUniqueness() {
        int inserted = repository.insertListingsIdempotently(List.of(
            new NewListing("DUP-001", "MLS_B", "100 Main St", "Reston", "VA", "20190",
                500000, 3, 2.5, 1800, 38.9586, -77.357, "active", "Feed B", "2026-10-15T00:00:00.000Z"),
            new NewListing("DUP-001", "MLS_A", "100 Main St", "Reston", "VA", "20190",
                500000, 3, 2.5, 1800, 38.9586, -77.357, "active", "Feed A", "2026-10-15T00:00:00.000Z"),
            new NewListing("DUP-001", "MLS_A", "100 Main St", "Reston", "VA", "20190",
                500000, 3, 2.5, 1800, 38.9586, -77.357, "active", "Duplicate ignored", "2026-10-15T00:00:00.000Z")
        ));

        assertThat(inserted).isEqualTo(2);
        List<Listing> results = repository.fetchListingPage(
            new SearchCriteria(null, null, null, "Reston", null, 500000.0, 1, 10),
            0
        );
        assertThat(results).hasSize(2);
        assertThat(results.get(0).source()).isEqualTo("MLS_A");
        assertThat(results.get(1).source()).isEqualTo("MLS_B");
        assertThat(UUID.fromString(results.get(0).internalId())).isNotEqualTo(UUID.fromString(results.get(1).internalId()));
    }

    @Test
    void handlesPaginationSlicesOffsetsAndEmitsClientSpans() {
        SeedListings.seedDatabase(repository);
        SearchCriteria pageCriteria = new SearchCriteria(null, null, null, null, null, null, 2, 5);

        assertThat(repository.countMatchingListings(pageCriteria)).isEqualTo(24);
        assertThat(repository.fetchListingPage(pageCriteria, 5)).hasSize(5);
        assertThat(repository.fetchListingPage(pageCriteria, 30)).isEmpty();
        assertThatThrownBy(() -> repository.fetchListingPage(pageCriteria, -1))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(OTEL.getSpans())
            .extracting(s -> s.getName() + ":" + s.getKind())
            .contains("search.db.count:" + SpanKind.CLIENT, "search.db.query:" + SpanKind.CLIENT);
    }

    @Test
    void coversDefaultConstructorsNullAndBlankFiltersAndDefaultFields() {
        InMemoryListingRepository defaultRepo = new InMemoryListingRepository();
        InMemoryListingRepository scorerRepo = new InMemoryListingRepository(scorer);

        assertThat(defaultRepo.insertListingsIdempotently(null)).isZero();
        assertThat(defaultRepo.insertListingsIdempotently(List.of())).isZero();

        int inserted = scorerRepo.insertListingsIdempotently(List.of(
            new NewListing("DEF-1", null, "1 A St", "Austin", null, null,
                300000, 1, null, null, null, null, " ", "Studio", "2026-09-01"),
            new NewListing("DEF-2", " ", "2 B St", "Austin", null, null,
                700000, 4, null, null, null, null, null, "Estate", "bad-date")
        ));
        assertThat(inserted).isEqualTo(2);

        SearchCriteria filterOutBoth = new SearchCriteria(350000.0, 650000.0, 2, "  ", "  ", null, 1, 10);
        assertThat(scorerRepo.countMatchingListings(filterOutBoth)).isZero();

        SearchCriteria minBedsFilter = new SearchCriteria(null, null, 2, null, null, null, 1, 10);
        assertThat(scorerRepo.fetchListingPage(minBedsFilter, 0))
            .extracting(Listing::id)
            .containsExactly("DEF-2");

        SearchCriteria maxPriceFilter = new SearchCriteria(null, 350000.0, null, null, null, null, 1, 10);
        List<Listing> def1 = scorerRepo.fetchListingPage(maxPriceFilter, 0);
        assertThat(def1).hasSize(1);
        assertThat(def1.get(0).source()).isEqualTo("SEED");
        assertThat(def1.get(0).status()).isEqualTo("active");
        assertThat(def1.get(0).listedDate()).isEqualTo("2026-09-01T00:00:00.000Z");
    }
}
