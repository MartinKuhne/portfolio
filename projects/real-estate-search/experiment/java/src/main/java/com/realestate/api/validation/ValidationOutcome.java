package com.realestate.api.validation;

import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.domain.SearchError;
import java.util.Objects;

/**
 * Outcome of validating a {@link com.realestate.api.domain.RawSearchInput}.
 */
public sealed interface ValidationOutcome permits ValidationOutcome.Valid, ValidationOutcome.Invalid {

    /**
     * Returns {@code true} when validation succeeded.
     */
    boolean isOk();

    /**
     * Creates a successful validation outcome.
     */
    static ValidationOutcome valid(SearchCriteria criteria) {
        return new Valid(criteria);
    }

    /**
     * Creates a failed validation outcome.
     */
    static ValidationOutcome invalid(SearchError error) {
        return new Invalid(error);
    }

    /**
     * Validated criteria outcome.
     */
    record Valid(SearchCriteria criteria) implements ValidationOutcome {
        public Valid {
            Objects.requireNonNull(criteria, "criteria must not be null");
        }

        @Override
        public boolean isOk() {
            return true;
        }
    }

    /**
     * Rejected input outcome with structured field-level {@link SearchError}.
     */
    record Invalid(SearchError error) implements ValidationOutcome {
        public Invalid {
            Objects.requireNonNull(error, "error must not be null");
        }

        @Override
        public boolean isOk() {
            return false;
        }
    }
}
