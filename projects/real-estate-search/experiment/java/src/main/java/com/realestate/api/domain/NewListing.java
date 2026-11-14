package com.realestate.api.domain;

/**
 * Input record used to insert or seed listings into persistence storage.
 */
public record NewListing(
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
    String listedDate
) {
}
