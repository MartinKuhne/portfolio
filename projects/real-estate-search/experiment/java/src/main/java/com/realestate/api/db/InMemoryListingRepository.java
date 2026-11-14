package com.realestate.api.db;

import com.realestate.api.domain.Listing;
import com.realestate.api.domain.ListingRepository;
import com.realestate.api.domain.NewListing;
import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.scoring.DefaultRelevanceScorer;
import com.realestate.api.scoring.RelevanceScorer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Thread-safe in-memory {@link ListingRepository} for unit and HTTP integration tests
 * without a live PostgreSQL daemon. Mirrors SQL filtering, scoring, and tie-breaking parity.
 */
public class InMemoryListingRepository implements ListingRepository {

    private static final DateTimeFormatter ISO_UTC_MILLIS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    private record CompositeKey(String id, String source) {
    }

    private record StoredListing(
        String internalId,
        String id,
        String source,
        String address,
        String city,
        String state,
        String zip,
        int price,
        int bedrooms,
        Double bathrooms,
        Integer sqft,
        Double latitude,
        Double longitude,
        String status,
        String description,
        String listedDate,
        Instant listedInstant
    ) {
        Listing toListing(double relevanceScore) {
            return new Listing(
                internalId, id, source, address, city, state, zip,
                price, bedrooms, bathrooms, sqft, latitude, longitude,
                status, description, listedDate, relevanceScore
            );
        }
    }

    private final Map<CompositeKey, StoredListing> store = new LinkedHashMap<>();
    private final RelevanceScorer scorer;
    private final Tracer tracer;

    public InMemoryListingRepository() {
        this(new DefaultRelevanceScorer(), GlobalOpenTelemetry.getTracer("real-estate-api"));
    }

    public InMemoryListingRepository(RelevanceScorer scorer) {
        this(scorer, GlobalOpenTelemetry.getTracer("real-estate-api"));
    }

    public InMemoryListingRepository(Tracer tracer) {
        this(new DefaultRelevanceScorer(), tracer);
    }

    public InMemoryListingRepository(RelevanceScorer scorer, Tracer tracer) {
        this.scorer = Objects.requireNonNull(scorer, "scorer must not be null");
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
    }

    @Override
    public void ensureSchema() {
        // No-op for in-memory storage
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
            int count;
            synchronized (store) {
                count = (int) store.values().stream()
                    .filter(row -> matches(row, criteria))
                    .count();
            }
            span.setAttribute("search.totalResults", (long) count);
            return count;
        } finally {
            span.end();
        }
    }

    @Override
    public List<Listing> fetchListingPage(SearchCriteria criteria, int offset) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }

        Span span = tracer.spanBuilder("search.db.query")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("db.system", "postgresql")
            .setAttribute("db.operation.name", "SELECT")
            .setAttribute("db.collection.name", "listings")
            .setAttribute("search.page", (long) criteria.page())
            .setAttribute("search.pageSize", (long) criteria.pageSize())
            .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            List<Listing> results;
            synchronized (store) {
                List<Listing> scored = store.values().stream()
                    .filter(row -> matches(row, criteria))
                    .map(row -> row.toListing(
                        scorer.computeScore(row.price(), row.listedDate(), criteria.targetBudget())
                    ))
                    .sorted(buildComparator(criteria.targetBudget() != null))
                    .toList();

                if (offset >= scored.size()) {
                    results = List.of();
                } else {
                    int end = Math.min(offset + criteria.pageSize(), scored.size());
                    results = List.copyOf(scored.subList(offset, end));
                }
            }
            span.setAttribute("search.returnedCount", (long) results.size());
            return results;
        } finally {
            span.end();
        }
    }

    @Override
    public int insertListingsIdempotently(List<NewListing> listings) {
        if (listings == null || listings.isEmpty()) {
            return 0;
        }

        int inserted = 0;
        synchronized (store) {
            for (NewListing listing : listings) {
                String source = listing.source() != null && !listing.source().isBlank()
                    ? listing.source()
                    : "SEED";
                String status = listing.status() != null && !listing.status().isBlank()
                    ? listing.status()
                    : "active";
                CompositeKey key = new CompositeKey(listing.id(), source);
                if (!store.containsKey(key)) {
                    Instant instant = parseInstant(listing.listedDate());
                    String normalizedDate = instant != null ? ISO_UTC_MILLIS.format(instant) : listing.listedDate();
                    store.put(key, new StoredListing(
                        UUID.randomUUID().toString(),
                        listing.id(),
                        source,
                        listing.address(),
                        listing.city(),
                        listing.state(),
                        listing.zip(),
                        listing.price(),
                        listing.bedrooms(),
                        listing.bathrooms(),
                        listing.sqft(),
                        listing.latitude(),
                        listing.longitude(),
                        status,
                        listing.description(),
                        normalizedDate,
                        instant != null ? instant : Instant.EPOCH
                    ));
                    inserted++;
                }
            }
        }
        return inserted;
    }

    private static boolean matches(StoredListing row, SearchCriteria criteria) {
        if (criteria.minPrice() != null && row.price() < criteria.minPrice()) {
            return false;
        }
        if (criteria.maxPrice() != null && row.price() > criteria.maxPrice()) {
            return false;
        }
        if (criteria.minBedrooms() != null && row.bedrooms() < criteria.minBedrooms()) {
            return false;
        }
        if (criteria.city() != null && !criteria.city().isBlank()) {
            if (!row.city().equalsIgnoreCase(criteria.city().trim())) {
                return false;
            }
        }
        if (criteria.keyword() != null && !criteria.keyword().isBlank()) {
            String lowerDesc = row.description().toLowerCase(Locale.ROOT);
            String lowerKeyword = criteria.keyword().trim().toLowerCase(Locale.ROOT);
            if (!lowerDesc.contains(lowerKeyword)) {
                return false;
            }
        }
        return true;
    }

    private static Comparator<Listing> buildComparator(boolean hasTargetBudget) {
        Comparator<Listing> tieBreakers = Comparator
            .comparing((Listing l) -> {
                Instant parsed = parseInstant(l.listedDate());
                return parsed != null ? parsed : Instant.EPOCH;
            }, Comparator.reverseOrder())
            .thenComparing(Listing::id)
            .thenComparing(Listing::source)
            .thenComparing(Listing::internalId);

        if (hasTargetBudget) {
            return Comparator.comparingDouble(Listing::relevanceScore)
                .reversed()
                .thenComparing(tieBreakers);
        }
        return tieBreakers;
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        String trimmed = iso.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant();
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException ignoredDate) {
                    return null;
                }
            }
        }
    }
}
