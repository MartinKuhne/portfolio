package com.realestate.api.domain;

/**
 * Pagination metadata included in successful search responses.
 */
public record PaginationMeta(
    int page,
    int pageSize,
    int totalResults,
    int totalPages
) {
}
