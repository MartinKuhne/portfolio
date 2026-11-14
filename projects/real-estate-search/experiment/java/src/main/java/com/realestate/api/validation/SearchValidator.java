package com.realestate.api.validation;

import com.realestate.api.domain.RawSearchInput;

/**
 * Contract for validating raw uncoerced search inputs before query execution.
 */
public interface SearchValidator {

    /**
     * Validates {@code rawInput} without coercing invalid values.
     */
    ValidationOutcome validate(RawSearchInput rawInput);
}
