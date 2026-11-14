package com.realestate.api.domain;

/**
 * Public real estate listing returned by search queries.
 */
public record Listing(
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
    double relevanceScore
) {
}
