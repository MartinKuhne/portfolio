package validation

import (
	"encoding/json"
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

	"real-estate-search/internal/domain"
)

const maxSafeInteger = 9007199254740991.0

var numericPattern = regexp.MustCompile(`^-?\d+(\.\d+)?$`)

// Result represents the outcome of validating raw search input.
type Result struct {
	OK    bool
	Value domain.ValidatedSearchInput
	Error *domain.SearchError
}

func validationError(field *string, message string) Result {
	return Result{
		OK: false,
		Error: &domain.SearchError{
			Code:    domain.ErrorCodeValidation,
			Field:   field,
			Message: message,
		},
	}
}

type numericParseResult struct {
	parsed  float64
	omitted bool
	err     *domain.SearchError
}

func parseNumeric(value any, fieldName string) numericParseResult {
	if value == nil {
		return numericParseResult{omitted: true}
	}

	fieldPtr := &fieldName

	switch v := value.(type) {
	case string:
		trimmed := strings.TrimSpace(v)
		if trimmed == "" {
			return numericParseResult{omitted: true}
		}
		if !numericPattern.MatchString(trimmed) {
			return numericParseResult{
				err: &domain.SearchError{
					Code:    domain.ErrorCodeValidation,
					Field:   fieldPtr,
					Message: fmt.Sprintf("%s must be a valid number.", fieldName),
				},
			}
		}
		num, err := strconv.ParseFloat(trimmed, 64)
		if err != nil || math.IsNaN(num) || math.IsInf(num, 0) {
			return numericParseResult{
				err: &domain.SearchError{
					Code:    domain.ErrorCodeValidation,
					Field:   fieldPtr,
					Message: fmt.Sprintf("%s must be a finite number.", fieldName),
				},
			}
		}
		return numericParseResult{parsed: num}

	case float64:
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return numericParseResult{
				err: &domain.SearchError{
					Code:    domain.ErrorCodeValidation,
					Field:   fieldPtr,
					Message: fmt.Sprintf("%s must be a finite number.", fieldName),
				},
			}
		}
		return numericParseResult{parsed: v}

	case float32:
		f64 := float64(v)
		if math.IsNaN(f64) || math.IsInf(f64, 0) {
			return numericParseResult{
				err: &domain.SearchError{
					Code:    domain.ErrorCodeValidation,
					Field:   fieldPtr,
					Message: fmt.Sprintf("%s must be a finite number.", fieldName),
				},
			}
		}
		return numericParseResult{parsed: f64}

	case int:
		return numericParseResult{parsed: float64(v)}
	case int64:
		return numericParseResult{parsed: float64(v)}
	case int32:
		return numericParseResult{parsed: float64(v)}
	case uint:
		return numericParseResult{parsed: float64(v)}
	case uint64:
		return numericParseResult{parsed: float64(v)}
	case uint32:
		return numericParseResult{parsed: float64(v)}

	case json.Number:
		trimmed := strings.TrimSpace(v.String())
		if trimmed == "" {
			return numericParseResult{omitted: true}
		}
		if !numericPattern.MatchString(trimmed) {
			return numericParseResult{
				err: &domain.SearchError{
					Code:    domain.ErrorCodeValidation,
					Field:   fieldPtr,
					Message: fmt.Sprintf("%s must be a valid number.", fieldName),
				},
			}
		}
		num, err := v.Float64()
		if err != nil || math.IsNaN(num) || math.IsInf(num, 0) {
			return numericParseResult{
				err: &domain.SearchError{
					Code:    domain.ErrorCodeValidation,
					Field:   fieldPtr,
					Message: fmt.Sprintf("%s must be a finite number.", fieldName),
				},
			}
		}
		return numericParseResult{parsed: num}

	default:
		return numericParseResult{
			err: &domain.SearchError{
				Code:    domain.ErrorCodeValidation,
				Field:   fieldPtr,
				Message: fmt.Sprintf("%s must be a number or numeric string.", fieldName),
			},
		}
	}
}

type boundedStringResult struct {
	value   *string
	omitted bool
	err     *domain.SearchError
}

func parseBoundedString(value any, fieldName string, maxLength int) boundedStringResult {
	if value == nil {
		return boundedStringResult{omitted: true}
	}

	fieldPtr := &fieldName

	str, ok := value.(string)
	if !ok {
		return boundedStringResult{
			err: &domain.SearchError{
				Code:    domain.ErrorCodeValidation,
				Field:   fieldPtr,
				Message: fmt.Sprintf("%s must be a string.", fieldName),
			},
		}
	}

	if strings.ContainsRune(str, '\x00') {
		return boundedStringResult{
			err: &domain.SearchError{
				Code:    domain.ErrorCodeValidation,
				Field:   fieldPtr,
				Message: fmt.Sprintf("%s must not contain null characters.", fieldName),
			},
		}
	}

	trimmed := strings.TrimSpace(str)
	if trimmed == "" {
		return boundedStringResult{omitted: true}
	}

	if utf8.RuneCountInString(trimmed) > maxLength {
		return boundedStringResult{
			err: &domain.SearchError{
				Code:    domain.ErrorCodeValidation,
				Field:   fieldPtr,
				Message: fmt.Sprintf("%s must not exceed %d characters.", fieldName, maxLength),
			},
		}
	}

	return boundedStringResult{value: &trimmed}
}

// ValidateSearchInput validates uncoerced search input per SPEC.md (REQ-VAL-002..012).
func ValidateSearchInput(raw domain.RawSearchInput) Result {
	// 1. minPrice
	var minPrice *float64
	minRes := parseNumeric(raw.MinPrice, "minPrice")
	if minRes.err != nil {
		return Result{OK: false, Error: minRes.err}
	}
	if !minRes.omitted {
		f := "minPrice"
		if minRes.parsed < 0 {
			return validationError(&f, "minPrice must be greater than or equal to 0.")
		}
		if minRes.parsed > domain.MaxPgInt {
			return validationError(&f, fmt.Sprintf("minPrice must not exceed %d.", domain.MaxPgInt))
		}
		val := minRes.parsed
		minPrice = &val
	}

	// 2. maxPrice
	var maxPrice *float64
	maxRes := parseNumeric(raw.MaxPrice, "maxPrice")
	if maxRes.err != nil {
		return Result{OK: false, Error: maxRes.err}
	}
	if !maxRes.omitted {
		f := "maxPrice"
		if maxRes.parsed < 0 {
			return validationError(&f, "maxPrice must be greater than or equal to 0.")
		}
		if maxRes.parsed > domain.MaxPgInt {
			return validationError(&f, fmt.Sprintf("maxPrice must not exceed %d.", domain.MaxPgInt))
		}
		val := maxRes.parsed
		maxPrice = &val
	}

	// 3. minPrice <= maxPrice
	if minPrice != nil && maxPrice != nil && *minPrice > *maxPrice {
		f := "minPrice"
		return validationError(&f, "minPrice must not exceed maxPrice.")
	}

	// 4. minBedrooms
	var minBedrooms *int
	bedRes := parseNumeric(raw.MinBedrooms, "minBedrooms")
	if bedRes.err != nil {
		return Result{OK: false, Error: bedRes.err}
	}
	if !bedRes.omitted {
		f := "minBedrooms"
		if bedRes.parsed != math.Trunc(bedRes.parsed) || bedRes.parsed < 0 || bedRes.parsed > domain.MaxPgInt {
			return validationError(&f, fmt.Sprintf("minBedrooms must be an integer between 0 and %d.", domain.MaxPgInt))
		}
		val := int(bedRes.parsed)
		minBedrooms = &val
	}

	// 5. targetBudget
	var targetBudget *float64
	tbRes := parseNumeric(raw.TargetBudget, "targetBudget")
	if tbRes.err != nil {
		return Result{OK: false, Error: tbRes.err}
	}
	if !tbRes.omitted {
		f := "targetBudget"
		if tbRes.parsed <= 0 || tbRes.parsed > maxSafeInteger {
			return validationError(&f, "targetBudget must be greater than 0 and within safe numeric limits.")
		}
		val := tbRes.parsed
		targetBudget = &val
	}

	// 6. pageSize
	pageSize := domain.DefaultPageSize
	psRes := parseNumeric(raw.PageSize, "pageSize")
	if psRes.err != nil {
		return Result{OK: false, Error: psRes.err}
	}
	if !psRes.omitted {
		f := "pageSize"
		if psRes.parsed != math.Trunc(psRes.parsed) || psRes.parsed <= 0 || psRes.parsed > domain.MaxPageSize {
			return validationError(&f, fmt.Sprintf("pageSize must be an integer between 1 and %d.", domain.MaxPageSize))
		}
		pageSize = int(psRes.parsed)
	}

	// 7. page
	page := domain.DefaultPage
	pRes := parseNumeric(raw.Page, "page")
	if pRes.err != nil {
		return Result{OK: false, Error: pRes.err}
	}
	if !pRes.omitted {
		f := "page"
		if pRes.parsed != math.Trunc(pRes.parsed) || pRes.parsed < 1 || pRes.parsed > domain.MaxPgInt {
			return validationError(&f, fmt.Sprintf("page must be an integer between 1 and %d.", domain.MaxPgInt))
		}
		page = int(pRes.parsed)
	}

	// 8. city
	cityRes := parseBoundedString(raw.City, "city", domain.MaxCityLength)
	if cityRes.err != nil {
		return Result{OK: false, Error: cityRes.err}
	}

	// 9. keyword
	kwRes := parseBoundedString(raw.Keyword, "keyword", domain.MaxKeywordLength)
	if kwRes.err != nil {
		return Result{OK: false, Error: kwRes.err}
	}

	return Result{
		OK: true,
		Value: domain.ValidatedSearchInput{
			MinPrice:     minPrice,
			MaxPrice:     maxPrice,
			MinBedrooms:  minBedrooms,
			City:         cityRes.value,
			Keyword:      kwRes.value,
			TargetBudget: targetBudget,
			Page:         page,
			PageSize:     pageSize,
		},
	}
}
