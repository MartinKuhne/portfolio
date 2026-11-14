package com.realestate.api.domain;

/**
 * Validated, normalized search criteria ready for repository querying and scoring.
 */
public record SearchCriteria(
    Double minPrice,
    Double maxPrice,
    Integer minBedrooms,
    String city,
    String keyword,
    Double targetBudget,
    int page,
    int pageSize
) {
}
