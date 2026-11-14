package com.realestate.api.domain;

import java.util.List;
import java.util.Objects;

/**
 * Result of executing a property search: either {@link Success} or {@link Failure}.
 */
public sealed interface SearchResult permits SearchResult.Success, SearchResult.Failure {

    /**
     * Returns {@code true} when the search succeeded.
     */
    boolean isOk();

    /**
     * Creates a successful search result.
     */
    static SearchResult success(List<Listing> listings, PaginationMeta pagination) {
        return new Success(listings, pagination);
    }

    /**
     * Creates a failed search result.
     */
    static SearchResult failure(SearchError error) {
        return new Failure(error);
    }

    /**
     * Successful search payload with ranked listings and pagination metadata.
     */
    record Success(List<Listing> listings, PaginationMeta pagination) implements SearchResult {
        public Success {
            listings = listings == null ? List.of() : List.copyOf(listings);
            Objects.requireNonNull(pagination, "pagination must not be null");
        }

        @Override
        public boolean isOk() {
            return true;
        }
    }

    /**
     * Failed search payload carrying a structured {@link SearchError}.
     */
    record Failure(SearchError error) implements SearchResult {
        public Failure {
            Objects.requireNonNull(error, "error must not be null");
        }

        @Override
        public boolean isOk() {
            return false;
        }
    }
}
