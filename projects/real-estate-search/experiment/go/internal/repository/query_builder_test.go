package repository_test

import (
	"strings"
	"testing"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/repository"
)

func TestEscapeLike(t *testing.T) {
	tests := []struct {
		input    string
		expected string
	}{
		{"Austin", "Austin"},
		{"100% Cotton", "100\\% Cotton"},
		{"under_score", "under\\_score"},
		{"back\\slash", "back\\\\slash"},
		{"%_\\mix", "\\%\\_\\\\mix"},
	}

	for _, tc := range tests {
		got := repository.EscapeLike(tc.input)
		if got != tc.expected {
			t.Errorf("EscapeLike(%q) = %q, expected %q", tc.input, got, tc.expected)
		}
	}
}

func TestBuildCountQuery_Unfiltered(t *testing.T) {
	input := domain.ValidatedSearchInput{
		Page:     1,
		PageSize: 10,
	}

	query, args := repository.BuildCountQuery(input)
	if query != "SELECT COUNT(*)::int FROM listings" {
		t.Errorf("unexpected count query: %s", query)
	}
	if len(args) != 0 {
		t.Errorf("expected 0 args, got %d", len(args))
	}
}

func TestBuildCountQuery_Filtered(t *testing.T) {
	minP := 200000
	maxP := 500000
	minB := 2
	city := "Austin"
	kw := "pool"

	input := domain.ValidatedSearchInput{
		MinPrice:    &minP,
		MaxPrice:    &maxP,
		MinBedrooms: &minB,
		City:        &city,
		Keyword:     &kw,
		Page:        1,
		PageSize:    10,
	}

	query, args := repository.BuildCountQuery(input)
	if !strings.Contains(query, "WHERE price >= $1::numeric AND price <= $2::numeric AND bedrooms >= $3 AND LOWER(city) = LOWER($4) AND description ILIKE $5 ESCAPE '\\\\'") {
		t.Errorf("unexpected filtered count query: %s", query)
	}
	if len(args) != 5 {
		t.Fatalf("expected 5 args, got %d", len(args))
	}
	if args[0] != 200000 || args[1] != 500000 || args[2] != 2 || args[3] != "Austin" || args[4] != "%pool%" {
		t.Errorf("unexpected args: %+v", args)
	}
}

func TestBuildSelectQuery_WithTargetBudgetAndPagination(t *testing.T) {
	budget := 450000.0
	city := "Austin"
	input := domain.ValidatedSearchInput{
		City:         &city,
		TargetBudget: &budget,
		Page:         2,
		PageSize:     10,
	}

	query, args := repository.BuildSelectQuery(input, 10)
	if !strings.Contains(query, "SELECT internal_id, id, source") {
		t.Errorf("missing select columns: %s", query)
	}
	if !strings.Contains(query, "ABS(price - $1)::double precision / $1::double precision") {
		t.Errorf("missing parameterized budget scoring expression: %s", query)
	}
	if !strings.Contains(query, "WHERE LOWER(city) = LOWER($2)") {
		t.Errorf("missing where clause: %s", query)
	}
	if !strings.Contains(query, "ORDER BY score DESC, listed_date DESC, id COLLATE \"C\" ASC, source COLLATE \"C\" ASC, internal_id ASC LIMIT $3 OFFSET $4") {
		t.Errorf("missing deterministic order by and pagination: %s", query)
	}

	if len(args) != 4 {
		t.Fatalf("expected 4 args, got %d", len(args))
	}
	if args[0] != 450000.0 || args[1] != "Austin" || args[2] != 10 || args[3] != 10 {
		t.Errorf("unexpected args: %+v", args)
	}
}

func TestBuildSelectQuery_WithoutTargetBudget(t *testing.T) {
	city := "Austin"
	input := domain.ValidatedSearchInput{
		City:     &city,
		Page:     1,
		PageSize: 10,
	}

	query, args := repository.BuildSelectQuery(input, 0)
	if !strings.Contains(query, "ORDER BY listed_date DESC, id COLLATE \"C\" ASC, source COLLATE \"C\" ASC, internal_id ASC") {
		t.Errorf("missing direct listed_date DESC order by without targetBudget: %s", query)
	}
	if len(args) != 3 {
		t.Fatalf("expected 3 args, got %d", len(args))
	}
}

func TestPlanPagination(t *testing.T) {
	// Empty results
	pEmpty := repository.PlanPagination(1, 10, 0)
	if pEmpty.Status != repository.PaginationStatusEmpty {
		t.Errorf("expected empty status, got %s", pEmpty.Status)
	}
	if pEmpty.Pagination.TotalResults != 0 || pEmpty.Pagination.TotalPages != 0 {
		t.Errorf("expected 0 totals, got %+v", pEmpty.Pagination)
	}

	// Normal page 1 of 25 (pageSize 10)
	p1 := repository.PlanPagination(1, 10, 25)
	if p1.Status != repository.PaginationStatusOK || p1.Offset != 0 || p1.TotalPages != 3 {
		t.Errorf("expected status ok, offset 0, totalPages 3, got: %+v", p1)
	}

	// Page 2
	p2 := repository.PlanPagination(2, 10, 25)
	if p2.Status != repository.PaginationStatusOK || p2.Offset != 10 || p2.TotalPages != 3 {
		t.Errorf("expected offset 10, got: %+v", p2)
	}

	// Out of range (page 4 of 3 total pages)
	pOut := repository.PlanPagination(4, 10, 25)
	if pOut.Status != repository.PaginationStatusOutOfRange {
		t.Errorf("expected out_of_range, got %s", pOut.Status)
	}
	if pOut.Error == nil || pOut.Error.Code != domain.ErrorCodeOutOfRange || *pOut.Error.TotalPages != 3 {
		t.Errorf("unexpected error payload: %+v", pOut.Error)
	}
}
