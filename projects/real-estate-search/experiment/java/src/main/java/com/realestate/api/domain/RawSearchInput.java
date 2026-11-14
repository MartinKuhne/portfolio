package com.realestate.api.domain;

import java.util.Map;

/**
 * Uncoerced raw search input fields received from GraphQL JSONScalar / String arguments.
 */
public record RawSearchInput(
    Object minPrice,
    Object maxPrice,
    Object minBedrooms,
    Object city,
    Object keyword,
    Object targetBudget,
    Object page,
    Object pageSize
) {
    /**
     * Returns an empty raw search input with all fields omitted.
     */
    public static RawSearchInput empty() {
        return new RawSearchInput(null, null, null, null, null, null, null, null);
    }

    /**
     * Constructs a {@link RawSearchInput} from a GraphQL argument map.
     * Returns {@code null} when {@code map} is {@code null}.
     */
    public static RawSearchInput fromMap(Map<String, ?> map) {
        if (map == null) {
            return null;
        }
        return new RawSearchInput(
            map.get("minPrice"),
            map.get("maxPrice"),
            map.get("minBedrooms"),
            map.get("city"),
            map.get("keyword"),
            map.get("targetBudget"),
            map.get("page"),
            map.get("pageSize")
        );
    }
}
