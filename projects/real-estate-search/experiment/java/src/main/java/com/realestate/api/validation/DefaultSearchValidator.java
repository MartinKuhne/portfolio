package com.realestate.api.validation;

import com.realestate.api.domain.RawSearchInput;
import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.domain.SearchError;
import java.util.regex.Pattern;

/**
 * Default {@link SearchValidator} matching REQ-VAL-002..011 and {@code packages/shared/src/validation.ts}.
 */
public final class DefaultSearchValidator implements SearchValidator {

    public static final int MAX_PG_INT = 2_147_483_647;
    public static final int MAX_CITY_LENGTH = 100;
    public static final int MAX_KEYWORD_LENGTH = 255;
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final Pattern NUMERIC_STRING_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private record NumericParseResult(Double parsed, boolean omitted, SearchError error) {
    }

    private record StringParseResult(String value, SearchError error) {
    }

    @Override
    public ValidationOutcome validate(RawSearchInput raw) {
        if (raw == null) {
            return ValidationOutcome.invalid(
                SearchError.validationError(null, "Search input must be an object.")
            );
        }

        // 1. minPrice
        Double minPrice = null;
        NumericParseResult minPriceRes = parseNumeric(raw.minPrice(), "minPrice");
        if (minPriceRes.error() != null) {
            return ValidationOutcome.invalid(minPriceRes.error());
        }
        if (!minPriceRes.omitted()) {
            double val = minPriceRes.parsed();
            if (val < 0) {
                return ValidationOutcome.invalid(
                    SearchError.validationError("minPrice", "minPrice must be greater than or equal to 0.")
                );
            }
            if (val > MAX_PG_INT) {
                return ValidationOutcome.invalid(
                    SearchError.validationError("minPrice", "minPrice must not exceed " + MAX_PG_INT + ".")
                );
            }
            minPrice = val;
        }

        // 2. maxPrice
        Double maxPrice = null;
        NumericParseResult maxPriceRes = parseNumeric(raw.maxPrice(), "maxPrice");
        if (maxPriceRes.error() != null) {
            return ValidationOutcome.invalid(maxPriceRes.error());
        }
        if (!maxPriceRes.omitted()) {
            double val = maxPriceRes.parsed();
            if (val < 0) {
                return ValidationOutcome.invalid(
                    SearchError.validationError("maxPrice", "maxPrice must be greater than or equal to 0.")
                );
            }
            if (val > MAX_PG_INT) {
                return ValidationOutcome.invalid(
                    SearchError.validationError("maxPrice", "maxPrice must not exceed " + MAX_PG_INT + ".")
                );
            }
            maxPrice = val;
        }

        // 3. minPrice <= maxPrice
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            return ValidationOutcome.invalid(
                SearchError.validationError("minPrice", "minPrice must not exceed maxPrice.")
            );
        }

        // 4. minBedrooms
        Integer minBedrooms = null;
        NumericParseResult minBedroomsRes = parseNumeric(raw.minBedrooms(), "minBedrooms");
        if (minBedroomsRes.error() != null) {
            return ValidationOutcome.invalid(minBedroomsRes.error());
        }
        if (!minBedroomsRes.omitted()) {
            double val = minBedroomsRes.parsed();
            if (!isWholeNumber(val) || val < 0 || val > MAX_PG_INT) {
                return ValidationOutcome.invalid(
                    SearchError.validationError(
                        "minBedrooms",
                        "minBedrooms must be an integer between 0 and " + MAX_PG_INT + "."
                    )
                );
            }
            minBedrooms = (int) val;
        }

        // 5. targetBudget
        Double targetBudget = null;
        NumericParseResult targetBudgetRes = parseNumeric(raw.targetBudget(), "targetBudget");
        if (targetBudgetRes.error() != null) {
            return ValidationOutcome.invalid(targetBudgetRes.error());
        }
        if (!targetBudgetRes.omitted()) {
            double val = targetBudgetRes.parsed();
            if (val <= 0 || val > (double) MAX_SAFE_INTEGER) {
                return ValidationOutcome.invalid(
                    SearchError.validationError(
                        "targetBudget",
                        "targetBudget must be greater than 0 and within safe numeric limits."
                    )
                );
            }
            targetBudget = val;
        }

        // 6. pageSize
        int pageSize = DEFAULT_PAGE_SIZE;
        NumericParseResult pageSizeRes = parseNumeric(raw.pageSize(), "pageSize");
        if (pageSizeRes.error() != null) {
            return ValidationOutcome.invalid(pageSizeRes.error());
        }
        if (!pageSizeRes.omitted()) {
            double val = pageSizeRes.parsed();
            if (!isWholeNumber(val) || val <= 0 || val > MAX_PAGE_SIZE) {
                return ValidationOutcome.invalid(
                    SearchError.validationError(
                        "pageSize",
                        "pageSize must be an integer between 1 and " + MAX_PAGE_SIZE + "."
                    )
                );
            }
            pageSize = (int) val;
        }

        // 7. page
        int page = DEFAULT_PAGE;
        NumericParseResult pageRes = parseNumeric(raw.page(), "page");
        if (pageRes.error() != null) {
            return ValidationOutcome.invalid(pageRes.error());
        }
        if (!pageRes.omitted()) {
            double val = pageRes.parsed();
            if (!isWholeNumber(val) || val < 1 || val > MAX_PG_INT) {
                return ValidationOutcome.invalid(
                    SearchError.validationError(
                        "page",
                        "page must be an integer between 1 and " + MAX_PG_INT + "."
                    )
                );
            }
            page = (int) val;
        }

        // 8. city
        StringParseResult cityRes = parseBoundedString(raw.city(), "city", MAX_CITY_LENGTH);
        if (cityRes.error() != null) {
            return ValidationOutcome.invalid(cityRes.error());
        }

        // 9. keyword
        StringParseResult keywordRes = parseBoundedString(raw.keyword(), "keyword", MAX_KEYWORD_LENGTH);
        if (keywordRes.error() != null) {
            return ValidationOutcome.invalid(keywordRes.error());
        }

        return ValidationOutcome.valid(
            new SearchCriteria(
                minPrice,
                maxPrice,
                minBedrooms,
                cityRes.value(),
                keywordRes.value(),
                targetBudget,
                page,
                pageSize
            )
        );
    }

    private static NumericParseResult parseNumeric(Object value, String fieldName) {
        if (value == null) {
            return new NumericParseResult(null, true, null);
        }

        if (value instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
                return new NumericParseResult(null, true, null);
            }
            if (!NUMERIC_STRING_PATTERN.matcher(trimmed).matches()) {
                return new NumericParseResult(
                    null,
                    false,
                    SearchError.validationError(fieldName, fieldName + " must be a valid number.")
                );
            }
            double num = Double.parseDouble(trimmed);
            if (!Double.isFinite(num)) {
                return new NumericParseResult(
                    null,
                    false,
                    SearchError.validationError(fieldName, fieldName + " must be a finite number.")
                );
            }
            return new NumericParseResult(num, false, null);
        }

        if (value instanceof Number number) {
            double num = number.doubleValue();
            if (!Double.isFinite(num)) {
                return new NumericParseResult(
                    null,
                    false,
                    SearchError.validationError(fieldName, fieldName + " must be a finite number.")
                );
            }
            return new NumericParseResult(num, false, null);
        }

        return new NumericParseResult(
            null,
            false,
            SearchError.validationError(fieldName, fieldName + " must be a number or numeric string.")
        );
    }

    private static StringParseResult parseBoundedString(Object value, String fieldName, int maxLength) {
        if (value == null) {
            return new StringParseResult(null, null);
        }
        if (!(value instanceof String str)) {
            return new StringParseResult(
                null,
                SearchError.validationError(fieldName, fieldName + " must be a string.")
            );
        }
        if (str.indexOf('\0') >= 0) {
            return new StringParseResult(
                null,
                SearchError.validationError(fieldName, fieldName + " must not contain null characters.")
            );
        }
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            return new StringParseResult(null, null);
        }
        if (trimmed.length() > maxLength) {
            return new StringParseResult(
                null,
                SearchError.validationError(fieldName, fieldName + " must not exceed " + maxLength + " characters.")
            );
        }
        return new StringParseResult(trimmed, null);
    }

    private static boolean isWholeNumber(double value) {
        return Double.isFinite(value) && value == Math.rint(value);
    }
}
