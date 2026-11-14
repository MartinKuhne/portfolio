package com.realestate.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainModelsTest {

    @Test
    void rawSearchInputEmptyHasAllNulls() {
        RawSearchInput empty = RawSearchInput.empty();
        assertThat(empty.minPrice()).isNull();
        assertThat(empty.maxPrice()).isNull();
        assertThat(empty.minBedrooms()).isNull();
        assertThat(empty.city()).isNull();
        assertThat(empty.keyword()).isNull();
        assertThat(empty.targetBudget()).isNull();
        assertThat(empty.page()).isNull();
        assertThat(empty.pageSize()).isNull();
    }

    @Test
    void rawSearchInputFromMapHandlesNullAndPopulatesFields() {
        assertThat(RawSearchInput.fromMap(null)).isNull();

        Map<String, Object> map = new HashMap<>();
        map.put("minPrice", 100);
        map.put("maxPrice", 500);
        map.put("minBedrooms", 2);
        map.put("city", "Austin");
        map.put("keyword", "pool");
        map.put("targetBudget", 450000);
        map.put("page", 1);
        map.put("pageSize", 20);

        RawSearchInput input = RawSearchInput.fromMap(map);
        assertThat(input.minPrice()).isEqualTo(100);
        assertThat(input.maxPrice()).isEqualTo(500);
        assertThat(input.minBedrooms()).isEqualTo(2);
        assertThat(input.city()).isEqualTo("Austin");
        assertThat(input.keyword()).isEqualTo("pool");
        assertThat(input.targetBudget()).isEqualTo(450000);
        assertThat(input.page()).isEqualTo(1);
        assertThat(input.pageSize()).isEqualTo(20);
    }

    @Test
    void searchErrorFactoryMethodsBuildExpectedRecords() {
        SearchError validation = SearchError.validationError("minPrice", "Invalid");
        assertThat(validation.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(validation.field()).isEqualTo("minPrice");
        assertThat(validation.message()).isEqualTo("Invalid");
        assertThat(validation.totalPages()).isNull();

        SearchError outOfRange = SearchError.outOfRange(3, 2);
        assertThat(outOfRange.code()).isEqualTo(SearchErrorCode.OUT_OF_RANGE);
        assertThat(outOfRange.field()).isEqualTo("page");
        assertThat(outOfRange.totalPages()).isEqualTo(2);
        assertThat(outOfRange.message()).isEqualTo("Page 3 exceeds total pages 2.");

        SearchError internal = SearchError.internalError("DB down");
        assertThat(internal.code()).isEqualTo(SearchErrorCode.INTERNAL_ERROR);
        assertThat(internal.field()).isNull();
        assertThat(internal.totalPages()).isNull();
        assertThat(internal.message()).isEqualTo("DB down");
    }

    @Test
    void searchResultSuccessAndFailureEnforceInvariantsAndDefensiveCopy() {
        PaginationMeta meta = new PaginationMeta(1, 10, 0, 0);
        List<Listing> mutable = new ArrayList<>();
        SearchResult success = SearchResult.success(mutable, meta);
        assertThat(success.isOk()).isTrue();
        SearchResult.Success typedSuccess = (SearchResult.Success) success;
        assertThat(typedSuccess.listings()).isEmpty();
        assertThat(typedSuccess.pagination()).isEqualTo(meta);

        SearchResult nullListSuccess = SearchResult.success(null, meta);
        assertThat(((SearchResult.Success) nullListSuccess).listings()).isEmpty();

        SearchResult failure = SearchResult.failure(SearchError.validationError("page", "bad"));
        assertThat(failure.isOk()).isFalse();
        assertThat(((SearchResult.Failure) failure).error().code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> SearchResult.success(List.of(), null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SearchResult.failure(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void listingAndNewListingRecordsRetainAllFields() {
        Listing listing = new Listing(
            "uuid-1", "LST-001", "SEED", "101 Congress Ave", "Austin", "TX", "78701",
            450_000, 3, 2.0, 1550, 30.2638, -97.7443, "active",
            "Spacious condo", "2026-10-20T00:00:00.000Z", 0.95
        );
        assertThat(listing.internalId()).isEqualTo("uuid-1");
        assertThat(listing.id()).isEqualTo("LST-001");
        assertThat(listing.relevanceScore()).isEqualTo(0.95);

        NewListing newListing = new NewListing(
            "LST-001", "SEED", "101 Congress Ave", "Austin", "TX", "78701",
            450_000, 3, 2.0, 1550, 30.2638, -97.7443, "active",
            "Spacious condo", "2026-10-20T00:00:00.000Z"
        );
        assertThat(newListing.id()).isEqualTo("LST-001");
        assertThat(newListing.source()).isEqualTo("SEED");
    }
}
