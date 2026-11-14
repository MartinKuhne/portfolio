package com.realestate.api.domain;

/**
 * Structured search error returned for validation, pagination bounds, or internal faults.
 */
public record SearchError(
    SearchErrorCode code,
    String message,
    String field,
    Integer totalPages
) {
    /**
     * Creates a validation error for an optional field name and message.
     */
    public static SearchError validationError(String field, String message) {
        return new SearchError(SearchErrorCode.VALIDATION_ERROR, message, field, null);
    }

    /**
     * Creates an out-of-range pagination error.
     */
    public static SearchError outOfRange(int page, int totalPages) {
        return new SearchError(
            SearchErrorCode.OUT_OF_RANGE,
            "Page " + page + " exceeds total pages " + totalPages + ".",
            "page",
            totalPages
        );
    }

    /**
     * Creates a sanitized internal error.
     */
    public static SearchError internalError(String message) {
        return new SearchError(SearchErrorCode.INTERNAL_ERROR, message, null, null);
    }
}
