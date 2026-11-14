package validation_test

import (
	"encoding/json"
	"strings"
	"testing"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/validation"
)

func TestValidateSearchInput_Defaults(t *testing.T) {
	res := validation.ValidateSearchInput(domain.RawSearchInput{})
	if !res.OK {
		t.Fatalf("expected OK true, got error: %v", res.Error)
	}
	if res.Value.Page != 1 {
		t.Errorf("expected page 1, got %d", res.Value.Page)
	}
	if res.Value.PageSize != 10 {
		t.Errorf("expected pageSize 10, got %d", res.Value.PageSize)
	}
	if res.Value.MinPrice != nil || res.Value.MaxPrice != nil {
		t.Errorf("expected nil minPrice and maxPrice")
	}
}

func TestValidateSearchInput_ValidInputs(t *testing.T) {
	minP := 200000
	maxP := 500000
	minB := 2
	city := "  Austin  "
	kw := "  pool  "
	budget := 450000.5
	page := 2
	pageSize := 20

	raw := domain.RawSearchInput{
		MinPrice:     minP,
		MaxPrice:     maxP,
		MinBedrooms:  minB,
		City:         city,
		Keyword:      kw,
		TargetBudget: budget,
		Page:         page,
		PageSize:     pageSize,
	}

	res := validation.ValidateSearchInput(raw)
	if !res.OK {
		t.Fatalf("expected OK true, got error: %v", res.Error)
	}
	if *res.Value.MinPrice != 200000 {
		t.Errorf("expected minPrice 200000, got %d", *res.Value.MinPrice)
	}
	if *res.Value.MaxPrice != 500000 {
		t.Errorf("expected maxPrice 500000, got %d", *res.Value.MaxPrice)
	}
	if *res.Value.MinBedrooms != 2 {
		t.Errorf("expected minBedrooms 2, got %d", *res.Value.MinBedrooms)
	}
	if *res.Value.City != "Austin" {
		t.Errorf("expected city trimmed 'Austin', got '%s'", *res.Value.City)
	}
	if *res.Value.Keyword != "pool" {
		t.Errorf("expected keyword trimmed 'pool', got '%s'", *res.Value.Keyword)
	}
	if *res.Value.TargetBudget != 450000.5 {
		t.Errorf("expected targetBudget 450000.5, got %f", *res.Value.TargetBudget)
	}
	if res.Value.Page != 2 {
		t.Errorf("expected page 2, got %d", res.Value.Page)
	}
	if res.Value.PageSize != 20 {
		t.Errorf("expected pageSize 20, got %d", res.Value.PageSize)
	}
}

func TestValidateSearchInput_StringNumericsAndJsonNumber(t *testing.T) {
	raw := domain.RawSearchInput{
		MinPrice:     "100000",
		MaxPrice:     json.Number("500000"),
		MinBedrooms:  "3",
		TargetBudget: "350000",
		Page:         "3",
		PageSize:     "15",
	}

	res := validation.ValidateSearchInput(raw)
	if !res.OK {
		t.Fatalf("expected OK true, got error: %v", res.Error)
	}
	if *res.Value.MinPrice != 100000 || *res.Value.MaxPrice != 500000 || *res.Value.MinBedrooms != 3 {
		t.Errorf("parsed numeric mismatch: %+v", res.Value)
	}
	if res.Value.Page != 3 || res.Value.PageSize != 15 {
		t.Errorf("parsed page/pageSize mismatch: %+v", res.Value)
	}

	// Various numeric types
	resTypes := validation.ValidateSearchInput(domain.RawSearchInput{
		MinPrice:     float32(10000),
		MaxPrice:     int64(90000),
		MinBedrooms:  int32(3),
		TargetBudget: uint(50000),
		Page:         uint64(2),
		PageSize:     uint32(20),
	})
	if !resTypes.OK {
		t.Fatalf("expected OK for various integer/float types: %v", resTypes.Error)
	}

	// Invalid json.Number
	resBadJsonNum := validation.ValidateSearchInput(domain.RawSearchInput{
		MinPrice: json.Number("not-num"),
	})
	if resBadJsonNum.OK {
		t.Errorf("expected error for invalid json.Number")
	}

	// Empty string json.Number
	resEmptyJsonNum := validation.ValidateSearchInput(domain.RawSearchInput{
		MinPrice: json.Number("   "),
	})
	if !resEmptyJsonNum.OK || resEmptyJsonNum.Value.MinPrice != nil {
		t.Errorf("expected empty json.Number to be omitted, got: %+v", resEmptyJsonNum)
	}
}

func TestValidateSearchInput_KeywordErrors(t *testing.T) {
	res := validation.ValidateSearchInput(domain.RawSearchInput{
		Keyword: 12345,
	})
	if res.OK || !strings.Contains(res.Error.Message, "keyword must be a string.") {
		t.Errorf("expected keyword string error, got: %v", res.Error)
	}
}

func TestValidateSearchInput_MinPriceErrors(t *testing.T) {
	tests := []struct {
		input any
		err   string
	}{
		{"abc", "minPrice must be a valid number."},
		{-10, "minPrice must be greater than or equal to 0."},
		{domain.MaxPgInt + 1, "minPrice must not exceed 2147483647."},
		{true, "minPrice must be a number or numeric string."},
	}

	for _, tc := range tests {
		res := validation.ValidateSearchInput(domain.RawSearchInput{MinPrice: tc.input})
		if res.OK {
			t.Errorf("expected error for minPrice %v, got OK", tc.input)
		} else if !strings.Contains(res.Error.Message, tc.err) {
			t.Errorf("expected error containing '%s', got '%s'", tc.err, res.Error.Message)
		}
	}
}

func TestValidateSearchInput_MaxPriceErrors(t *testing.T) {
	tests := []struct {
		input any
		err   string
	}{
		{"not-a-number", "maxPrice must be a valid number."},
		{-1, "maxPrice must be greater than or equal to 0."},
		{domain.MaxPgInt + 1, "maxPrice must not exceed 2147483647."},
	}

	for _, tc := range tests {
		res := validation.ValidateSearchInput(domain.RawSearchInput{MaxPrice: tc.input})
		if res.OK {
			t.Errorf("expected error for maxPrice %v, got OK", tc.input)
		} else if !strings.Contains(res.Error.Message, tc.err) {
			t.Errorf("expected error containing '%s', got '%s'", tc.err, res.Error.Message)
		}
	}
}

func TestValidateSearchInput_MinPriceExceedsMaxPrice(t *testing.T) {
	res := validation.ValidateSearchInput(domain.RawSearchInput{
		MinPrice: 500000,
		MaxPrice: 400000,
	})
	if res.OK {
		t.Fatalf("expected validation error, got OK")
	}
	if *res.Error.Field != "minPrice" || !strings.Contains(res.Error.Message, "minPrice must not exceed maxPrice.") {
		t.Errorf("unexpected error: %+v", res.Error)
	}
}

func TestValidateSearchInput_MinBedroomsErrors(t *testing.T) {
	tests := []struct {
		input any
	}{
		{"1.5"},
		{2.7},
		{-1},
		{domain.MaxPgInt + 1},
		{"abc"},
	}

	for _, tc := range tests {
		res := validation.ValidateSearchInput(domain.RawSearchInput{MinBedrooms: tc.input})
		if res.OK {
			t.Errorf("expected error for minBedrooms %v, got OK", tc.input)
		}
		if *res.Error.Field != "minBedrooms" {
			t.Errorf("expected field minBedrooms, got %v", res.Error.Field)
		}
	}
}

func TestValidateSearchInput_TargetBudgetErrors(t *testing.T) {
	tests := []struct {
		input any
	}{
		{0},
		{-500},
		{"invalid"},
		{1e17}, // exceeds max safe integer
	}

	for _, tc := range tests {
		res := validation.ValidateSearchInput(domain.RawSearchInput{TargetBudget: tc.input})
		if res.OK {
			t.Errorf("expected error for targetBudget %v, got OK", tc.input)
		}
		if *res.Error.Field != "targetBudget" {
			t.Errorf("expected field targetBudget, got %v", res.Error.Field)
		}
	}
}

func TestValidateSearchInput_PaginationErrors(t *testing.T) {
	// PageSize
	psTests := []any{0, -1, 101, 10.5, "abc"}
	for _, tc := range psTests {
		res := validation.ValidateSearchInput(domain.RawSearchInput{PageSize: tc})
		if res.OK {
			t.Errorf("expected error for pageSize %v, got OK", tc)
		}
		if *res.Error.Field != "pageSize" {
			t.Errorf("expected field pageSize, got %v", res.Error.Field)
		}
	}

	// Page
	pTests := []any{0, -1, domain.MaxPgInt + 1, 1.5, "xyz"}
	for _, tc := range pTests {
		res := validation.ValidateSearchInput(domain.RawSearchInput{Page: tc})
		if res.OK {
			t.Errorf("expected error for page %v, got OK", tc)
		}
		if *res.Error.Field != "page" {
			t.Errorf("expected field page, got %v", res.Error.Field)
		}
	}
}

func TestValidateSearchInput_StringBoundsAndNullBytes(t *testing.T) {
	// Non-string
	res := validation.ValidateSearchInput(domain.RawSearchInput{City: 123})
	if res.OK || !strings.Contains(res.Error.Message, "city must be a string.") {
		t.Errorf("expected city string error, got: %v", res.Error)
	}

	// Null byte rejection
	res = validation.ValidateSearchInput(domain.RawSearchInput{City: "Austin\x00TX"})
	if res.OK || !strings.Contains(res.Error.Message, "null characters") {
		t.Errorf("expected null character error, got: %v", res.Error)
	}

	res = validation.ValidateSearchInput(domain.RawSearchInput{Keyword: "lake\x00front"})
	if res.OK || !strings.Contains(res.Error.Message, "null characters") {
		t.Errorf("expected null character error, got: %v", res.Error)
	}

	// String length limits
	longCity := strings.Repeat("A", domain.MaxCityLength+1)
	res = validation.ValidateSearchInput(domain.RawSearchInput{City: longCity})
	if res.OK || !strings.Contains(res.Error.Message, "must not exceed 100 characters.") {
		t.Errorf("expected city length error, got: %v", res.Error)
	}

	longKw := strings.Repeat("K", domain.MaxKeywordLength+1)
	res = validation.ValidateSearchInput(domain.RawSearchInput{Keyword: longKw})
	if res.OK || !strings.Contains(res.Error.Message, "must not exceed 255 characters.") {
		t.Errorf("expected keyword length error, got: %v", res.Error)
	}

	// Whitespace-only strings become nil (omitted)
	res = validation.ValidateSearchInput(domain.RawSearchInput{City: "   ", Keyword: "  "})
	if !res.OK {
		t.Fatalf("expected whitespace strings to be omitted, got error: %v", res.Error)
	}
	if res.Value.City != nil || res.Value.Keyword != nil {
		t.Errorf("expected nil city and keyword, got: city=%v, kw=%v", res.Value.City, res.Value.Keyword)
	}
}
