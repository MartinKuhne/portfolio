package com.realestate.api.domain;

import java.util.List;

/**
 * Persistence contract for real estate listing storage, querying, and idempotent ingestion.
 */
public interface ListingRepository {

    /**
     * Creates required tables and indexes if they do not already exist.
     */
    void ensureSchema();

    /**
     * Counts all persisted listings matching the validated {@link SearchCriteria}.
     */
    int countMatchingListings(SearchCriteria criteria);

    /**
     * Fetches a deterministically ranked page of listings matching {@link SearchCriteria}.
     *
     * @param criteria validated search criteria including {@code targetBudget} and {@code pageSize}
     * @param offset zero-based row offset {@code (page - 1) * pageSize}
     * @return ranked listings for the requested page
     */
    List<Listing> fetchListingPage(SearchCriteria criteria, int offset);

    /**
     * Inserts listings idempotently by {@code (id, source)}, skipping duplicates.
     *
     * @return number of newly inserted rows
     */
    int insertListingsIdempotently(List<NewListing> listings);
}
