package com.realestate.api.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realestate.api.domain.RawSearchInput;
import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.domain.SearchError;
import com.realestate.api.domain.SearchErrorCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DefaultSearchValidatorTest {

    private final DefaultSearchValidator validator = new DefaultSearchValidator();

    @Test
    void appliesDefaultPageAndPageSizeWhenOmitted() {
        ValidationOutcome outcome = validator.validate(RawSearchInput.empty());
        assertThat(outcome.isOk()).isTrue();
        SearchCriteria criteria = ((ValidationOutcome.Valid) outcome).criteria();
        assertThat(criteria).isEqualTo(new SearchCriteria(null, null, null, null, null, null, 1, 10));
    }

    @Test
    void trimsCityAndKeywordAndDropsWhitespaceOnlyStrings() {
        RawSearchInput input = new RawSearchInput(null, null, null, "  Austin ", "   ", null, null, null);
        ValidationOutcome outcome = validator.validate(input);
        assertThat(outcome.isOk()).isTrue();
        SearchCriteria criteria = ((ValidationOutcome.Valid) outcome).criteria();
        assertThat(criteria.city()).isEqualTo("Austin");
        assertThat(criteria.keyword()).isNull();
    }

    @Test
    void acceptsValidNumericBoundariesAndNumericStrings() {
        RawSearchInput input = new RawSearchInput(
            0,
            "0",
            0,
            "Denver",
            "loft",
            "500000",
            1,
            100
        );
        ValidationOutcome outcome = validator.validate(input);
        assertThat(outcome.isOk()).isTrue();
        SearchCriteria criteria = ((ValidationOutcome.Valid) outcome).criteria();
        assertThat(criteria.minPrice()).isEqualTo(0.0);
        assertThat(criteria.maxPrice()).isEqualTo(0.0);
        assertThat(criteria.minBedrooms()).isEqualTo(0);
        assertThat(criteria.targetBudget()).isEqualTo(500000.0);
        assertThat(criteria.page()).isEqualTo(1);
        assertThat(criteria.pageSize()).isEqualTo(100);
        assertThat(criteria.city()).isEqualTo("Denver");
        assertThat(criteria.keyword()).isEqualTo("loft");
    }

    @Test
    void acceptsValidTrimmedNumericStringsAndEmptyStringsAsOmitted() {
        RawSearchInput input = new RawSearchInput(
            " 250000 ",
            " 500000.5 ",
            " 3 ",
            null,
            "",
            " 400000 ",
            " 2 ",
            " 20 "
        );
        ValidationOutcome outcome = validator.validate(input);
        assertThat(outcome.isOk()).isTrue();
        SearchCriteria criteria = ((ValidationOutcome.Valid) outcome).criteria();
        assertThat(criteria.minPrice()).isEqualTo(250000.0);
        assertThat(criteria.maxPrice()).isEqualTo(500000.5);
        assertThat(criteria.minBedrooms()).isEqualTo(3);
        assertThat(criteria.targetBudget()).isEqualTo(400000.0);
        assertThat(criteria.page()).isEqualTo(2);
        assertThat(criteria.pageSize()).isEqualTo(20);
        assertThat(criteria.keyword()).isNull();
    }

    @Test
    void treatsBlankNumericStringsAsOmitted() {
        RawSearchInput input = new RawSearchInput("", "   ", "\t", null, null, "  ", "", " ");
        ValidationOutcome outcome = validator.validate(input);
        assertThat(outcome.isOk()).isTrue();
        SearchCriteria criteria = ((ValidationOutcome.Valid) outcome).criteria();
        assertThat(criteria).isEqualTo(new SearchCriteria(null, null, null, null, null, null, 1, 10));
    }

    @Test
    void rejectsNullRawInputAsNonObject() {
        ValidationOutcome outcome = validator.validate(null);
        assertThat(outcome.isOk()).isFalse();
        SearchError error = ((ValidationOutcome.Invalid) outcome).error();
        assertThat(error).isEqualTo(
            new SearchError(SearchErrorCode.VALIDATION_ERROR, "Search input must be an object.", null, null)
        );
    }

    static Stream<Object> invalidPriceValues() {
        return Stream.of(
            "abc",
            "100abc",
            -1,
            -0.01,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Float.NaN,
            BigDecimal.valueOf(Double.MAX_VALUE).multiply(BigDecimal.TEN),
            true,
            false,
            Map.of(),
            List.of()
        );
    }

    @ParameterizedTest
    @MethodSource("invalidPriceValues")
    void rejectsInvalidMinAndMaxPriceWithoutCoercion(Object bad) {
        ValidationOutcome minRes = validator.validate(
            new RawSearchInput(bad, null, null, null, null, null, null, null)
        );
        assertThat(minRes.isOk()).isFalse();
        SearchError minErr = ((ValidationOutcome.Invalid) minRes).error();
        assertThat(minErr.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(minErr.field()).isEqualTo("minPrice");
        assertThat(minErr.message().toLowerCase()).contains("minprice");

        ValidationOutcome maxRes = validator.validate(
            new RawSearchInput(null, bad, null, null, null, null, null, null)
        );
        assertThat(maxRes.isOk()).isFalse();
        SearchError maxErr = ((ValidationOutcome.Invalid) maxRes).error();
        assertThat(maxErr.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(maxErr.field()).isEqualTo("maxPrice");
        assertThat(maxErr.message().toLowerCase()).contains("maxprice");
    }

    @Test
    void rejectsOverflowingNumericStringAsNotFinite() {
        String huge = "9".repeat(400);
        ValidationOutcome outcome = validator.validate(
            new RawSearchInput(huge, null, null, null, null, null, null, null)
        );
        assertThat(outcome.isOk()).isFalse();
        SearchError error = ((ValidationOutcome.Invalid) outcome).error();
        assertThat(error.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(error.field()).isEqualTo("minPrice");
        assertThat(error.message()).contains("finite");
    }

    @Test
    void rejectsMinPriceGreaterThanMaxPrice() {
        ValidationOutcome outcome = validator.validate(
            new RawSearchInput(500000, 400000, null, null, null, null, null, null)
        );
        assertThat(outcome.isOk()).isFalse();
        SearchError error = ((ValidationOutcome.Invalid) outcome).error();
        assertThat(error).isEqualTo(
            SearchError.validationError("minPrice", "minPrice must not exceed maxPrice.")
        );
    }

    static Stream<Object> invalidBedroomsValues() {
        return Stream.of(-1, 2.5, "two", "2beds", Double.NaN, Double.POSITIVE_INFINITY, true, Map.of(), List.of());
    }

    @ParameterizedTest
    @MethodSource("invalidBedroomsValues")
    void rejectsInvalidMinBedrooms(Object bad) {
        ValidationOutcome outcome = validator.validate(
            new RawSearchInput(null, null, bad, null, null, null, null, null)
        );
        assertThat(outcome.isOk()).isFalse();
        SearchError error = ((ValidationOutcome.Invalid) outcome).error();
        assertThat(error.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(error.field()).isEqualTo("minBedrooms");
    }

    static Stream<Object> invalidTargetBudgetValues() {
        return Stream.of(
            0,
            -100,
            "abc",
            "100k",
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            true,
            Map.of(),
            List.of()
        );
    }

    @ParameterizedTest
    @MethodSource("invalidTargetBudgetValues")
    void rejectsInvalidTargetBudget(Object bad) {
        ValidationOutcome outcome = validator.validate(
            new RawSearchInput(null, null, null, null, null, bad, null, null)
        );
        assertThat(outcome.isOk()).isFalse();
        SearchError error = ((ValidationOutcome.Invalid) outcome).error();
        assertThat(error.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(error.field()).isEqualTo("targetBudget");
    }

    static Stream<Object> invalidPageSizeValues() {
        return Stream.of(0, -5, 1.5, 101, "ten", "10items", Double.NaN, Double.POSITIVE_INFINITY, true, Map.of(), List.of());
    }

    @ParameterizedTest
    @MethodSource("invalidPageSizeValues")
    void rejectsInvalidPageSize(Object bad) {
        ValidationOutcome outcome = validator.validate(
            new RawSearchInput(null, null, null, null, null, null, null, bad)
        );
        assertThat(outcome.isOk()).isFalse();
        SearchError error = ((ValidationOutcome.Invalid) outcome).error();
        assertThat(error.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(error.field()).isEqualTo("pageSize");
    }

    static Stream<Object> invalidPageValues() {
        return Stream.of(0, -1, 1.2, "first", "1st", Double.NaN, Double.POSITIVE_INFINITY, true, Map.of(), List.of());
    }

    @ParameterizedTest
    @MethodSource("invalidPageValues")
    void rejectsInvalidPage(Object bad) {
        ValidationOutcome outcome = validator.validate(
            new RawSearchInput(null, null, null, null, null, null, bad, null)
        );
        assertThat(outcome.isOk()).isFalse();
        SearchError error = ((ValidationOutcome.Invalid) outcome).error();
        assertThat(error.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(error.field()).isEqualTo("page");
    }

    static Stream<Object> nonStringValues() {
        return Stream.of(123, true, Map.of(), List.of());
    }

    @ParameterizedTest
    @MethodSource("nonStringValues")
    void rejectsNonStringCityOrKeyword(Object bad) {
        ValidationOutcome cityRes = validator.validate(
            new RawSearchInput(null, null, null, bad, null, null, null, null)
        );
        assertThat(cityRes.isOk()).isFalse();
        assertThat(((ValidationOutcome.Invalid) cityRes).error().field()).isEqualTo("city");

        ValidationOutcome keywordRes = validator.validate(
            new RawSearchInput(null, null, null, null, bad, null, null, null)
        );
        assertThat(keywordRes.isOk()).isFalse();
        assertThat(((ValidationOutcome.Invalid) keywordRes).error().field()).isEqualTo("keyword");
    }

    @Test
    void rejectsPostgresInt4OverflowAndExcessiveTargetBudget() {
        long overInt = 2_147_483_648L;
        assertInvalidField(new RawSearchInput(overInt, null, null, null, null, null, null, null), "minPrice");
        assertInvalidField(new RawSearchInput(null, overInt, null, null, null, null, null, null), "maxPrice");
        assertInvalidField(new RawSearchInput(null, null, overInt, null, null, null, null, null), "minBedrooms");
        assertInvalidField(new RawSearchInput(null, null, null, null, null, null, overInt, null), "page");
        assertInvalidField(
            new RawSearchInput(null, null, null, null, null, BigInteger.valueOf(DefaultSearchValidator.MAX_SAFE_INTEGER + 1), null, null),
            "targetBudget"
        );
    }

    @Test
    void acceptsBoundaryInt4MaxAndMaxSafeInteger() {
        ValidationOutcome outcome = validator.validate(
            new RawSearchInput(
                DefaultSearchValidator.MAX_PG_INT,
                DefaultSearchValidator.MAX_PG_INT,
                DefaultSearchValidator.MAX_PG_INT,
                "a".repeat(DefaultSearchValidator.MAX_CITY_LENGTH),
                "k".repeat(DefaultSearchValidator.MAX_KEYWORD_LENGTH),
                DefaultSearchValidator.MAX_SAFE_INTEGER,
                DefaultSearchValidator.MAX_PG_INT,
                DefaultSearchValidator.MAX_PAGE_SIZE
            )
        );
        assertThat(outcome.isOk()).isTrue();
    }

    @Test
    void rejectsNullBytesAndOversizedStringsInCityAndKeyword() {
        assertInvalidField(new RawSearchInput(null, null, null, "Austin\0", null, null, null, null), "city");
        assertInvalidField(new RawSearchInput(null, null, null, "a".repeat(101), null, null, null, null), "city");
        assertInvalidField(new RawSearchInput(null, null, null, null, "pool\0", null, null, null), "keyword");
        assertInvalidField(new RawSearchInput(null, null, null, null, "k".repeat(256), null, null, null), "keyword");
    }

    @Test
    void validationOutcomeGuardsAgainstNullArguments() {
        assertThatThrownBy(() -> ValidationOutcome.valid(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ValidationOutcome.invalid(null)).isInstanceOf(NullPointerException.class);
    }

    private void assertInvalidField(RawSearchInput input, String expectedField) {
        ValidationOutcome outcome = validator.validate(input);
        assertThat(outcome.isOk()).isFalse();
        SearchError error = ((ValidationOutcome.Invalid) outcome).error();
        assertThat(error.code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(error.field()).isEqualTo(expectedField);
    }
}
