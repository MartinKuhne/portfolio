package com.realestate.api.db;

import com.realestate.api.domain.ListingRepository;
import com.realestate.api.domain.NewListing;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic 24-listing seed dataset matching {@code packages/api/src/db/seed.ts}.
 */
public final class SeedListings {

    public static final List<NewListing> LISTINGS = List.of(
        // Austin (6 listings)
        new NewListing("LST-001", "SEED", "101 Congress Ave", "Austin", "TX", "78701", 450000, 3, 2.0, 1550, 30.2638, -97.7443, "active",
            "Spacious condo downtown with access to a swimming pool and gym.", "2026-10-20T00:00:00.000Z"),
        new NewListing("LST-002", "SEED", "202 Barton Springs Rd", "Austin", "TX", "78704", 550000, 3, 2.5, 1850, 30.2586, -97.7556, "active",
            "Beautiful ranch home with heated pool and private patio.", "2026-10-25T00:00:00.000Z"),
        new NewListing("LST-003", "SEED", "303 South Lamar Blvd", "Austin", "TX", "78704", 350000, 2, 1.5, 1050, 30.2602, -97.7595, "active",
            "Modern apartment near trendy restaurants and live music.", "2026-09-15T00:00:00.000Z"),
        new NewListing("LST-004", "SEED", "404 Rainey St", "Austin", "TX", "78701", 620000, 4, 3.0, 2200, 30.2589, -97.7389, "active",
            "Luxurious townhouse featuring a community pool and rooftop deck.", "2026-10-10T00:00:00.000Z"),
        new NewListing("LST-005", "SEED", "505 Guadalupe St", "Austin", "TX", "78701", 720000, 4, 3.5, 2450, 30.2682, -97.7458, "active",
            "Stunning penthouse with panoramic skyline views.", "2026-10-05T00:00:00.000Z"),
        new NewListing("LST-006", "SEED", "606 East 6th St", "Austin", "TX", "78702", 480000, 2, 2.0, 1280, 30.2663, -97.7364, "active",
            "Charming loft in the historic district.", "2026-08-20T00:00:00.000Z"),

        // Denver (6 listings)
        new NewListing("LST-007", "SEED", "701 16th St Mall", "Denver", "CO", "80202", 500000, 3, 2.0, 1620, 39.7463, -104.9922, "active",
            "Bright loft near Union Station.", "2026-10-01T00:00:00.000Z"),
        new NewListing("LST-008", "SEED", "802 Larimer St", "Denver", "CO", "80205", 500000, 3, 2.0, 1700, 39.7518, -104.9896, "active",
            "Historic brick bungalow in LoDo.", "2026-10-28T00:00:00.000Z"),
        new NewListing("LST-009", "SEED", "903 Colfax Ave", "Denver", "CO", "80206", 420000, 2, 1.5, 1180, 39.7401, -104.9602, "active",
            "Cozy Victorian home near City Park.", "2026-09-01T00:00:00.000Z"),
        new NewListing("LST-010", "SEED", "1010 Speer Blvd", "Denver", "CO", "80204", 500000, 3, 2.5, 1750, 39.7327, -104.9899, "active",
            "Contemporary duplex near Cherry Creek trail.", "2026-10-15T00:00:00.000Z"),
        new NewListing("LST-011", "SEED", "1111 Broadway", "Denver", "CO", "80203", 500000, 3, 2.0, 1580, 39.7341, -104.9873, "active",
            "Modern condo with mountain views.", "2026-10-15T00:00:00.000Z"),
        new NewListing("LST-012", "SEED", "1212 Wynkoop St", "Denver", "CO", "80202", 680000, 4, 3.0, 2350, 39.7501, -105.0021, "active",
            "Upscale corner residence with private terrace.", "2026-10-12T00:00:00.000Z"),

        // Seattle (6 listings)
        new NewListing("LST-013", "SEED", "1301 Pike St", "Seattle", "WA", "98101", 650000, 2, 2.0, 1220, 47.6126, -122.3274, "active",
            "Urban retreat near Pike Place Market with 100% renovated kitchen and baths.", "2026-10-18T00:00:00.000Z"),
        new NewListing("LST-014", "SEED", "1402 Pine St", "Seattle", "WA", "98122", 780000, 3, 2.5, 1920, 47.6152, -122.3257, "active",
            "Classic Capitol Hill craftsman home.", "2026-09-22T00:00:00.000Z"),
        new NewListing("LST-015", "SEED", "1503 Queen Anne Ave", "Seattle", "WA", "98109", 890000, 4, 3.5, 2680, 47.6326, -122.3568, "active",
            "Magnificent Queen Anne view estate overlooking Puget Sound.", "2026-10-22T00:00:00.000Z"),
        new NewListing("LST-016", "SEED", "1604 Ballard Ave", "Seattle", "WA", "98107", 580000, 2, 1.5, 1150, 47.6659, -122.3821, "active",
            "Trendy Ballard cottage with lush private garden.", "2026-08-15T00:00:00.000Z"),
        new NewListing("LST-017", "SEED", "1705 Fremont Ave", "Seattle", "WA", "98103", 610000, 3, 2.5, 1640, 47.6512, -122.3501, "active",
            "Artistic Fremont townhouse close to the canal.", "2026-10-08T00:00:00.000Z"),
        new NewListing("LST-018", "SEED", "1806 Alki Ave SW", "Seattle", "WA", "98116", 920000, 3, 3.0, 2150, 47.5812, -122.4063, "active",
            "Waterfront beach house on Alki Beach.", "2026-09-30T00:00:00.000Z"),

        // Miami (6 listings)
        new NewListing("LST-019", "SEED", "1901 Ocean Dr", "Miami", "FL", "33139", 750000, 2, 2.0, 1320, 25.7824, -80.1301, "active",
            "Art deco condo steps from South Beach.", "2026-10-14T00:00:00.000Z"),
        new NewListing("LST-020", "SEED", "2002 Brickell Ave", "Miami", "FL", "33131", 850000, 3, 2.5, 1890, 25.7589, -80.1936, "active",
            "High-rise luxury condo in the financial district with bay views.", "2026-10-26T00:00:00.000Z"),
        new NewListing("LST-021", "SEED", "2103 Biscayne Blvd", "Miami", "FL", "33137", 490000, 2, 2.0, 1190, 25.7972, -80.1889, "active",
            "Chic Edgewater apartment with resort-style amenities.", "2026-09-18T00:00:00.000Z"),
        new NewListing("LST-022", "SEED", "2204 Coral Way", "Miami", "FL", "33145", 530000, 3, 2.0, 1680, 25.7506, -80.2302, "active",
            "Mediterranean revival villa with private pool.", "2026-10-11T00:00:00.000Z"),
        new NewListing("LST-023", "SEED", "2305 Calle Ocho", "Miami", "FL", "33135", 380000, 2, 1.5, 1090, 25.7654, -80.2325, "active",
            "Vibrant Little Havana home near cultural landmarks.", "2026-07-20T00:00:00.000Z"),
        new NewListing("LST-024", "SEED", "2406 Collins Ave", "Miami", "FL", "33140", 990000, 4, 4.0, 2850, 25.8002, -80.1259, "active",
            "Oceanfront penthouse with wraparound balcony.", "2026-10-29T00:00:00.000Z")
    );

    private SeedListings() {
    }

    /**
     * Inserts the seed listings idempotently into the given repository.
     */
    public static int seedDatabase(ListingRepository repository) {
        Objects.requireNonNull(repository, "repository must not be null");
        return repository.insertListingsIdempotently(LISTINGS);
    }
}
