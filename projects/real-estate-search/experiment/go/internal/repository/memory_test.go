package repository_test

import (
	"context"
	"testing"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/repository"
)

func TestMemoryRepository_SeedAndQuery(t *testing.T) {
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	// Seed repository
	if err := repository.SeedDatabase(ctx, repo); err != nil {
		t.Fatalf("SeedDatabase failed: %v", err)
	}

	// Count unfiltered
	count, err := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{
		Page:     1,
		PageSize: 10,
	})
	if err != nil {
		t.Fatalf("CountMatchingListings failed: %v", err)
	}
	if count != 6 {
		t.Errorf("expected 6 seeded listings, got %d", count)
	}

	// Filter by price and bedrooms
	minP := 400000
	maxP := 600000
	minB := 3
	city := "Austin"
	kw := "pool"

	filteredCount, err := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{
		MinPrice:    &minP,
		MaxPrice:    &maxP,
		MinBedrooms: &minB,
		City:        &city,
		Keyword:     &kw,
		Page:        1,
		PageSize:    10,
	})
	if err != nil {
		t.Fatalf("CountMatchingListings filtered failed: %v", err)
	}
	if filteredCount == 0 {
		t.Errorf("expected at least 1 match for pool condo/ranch in Austin")
	}

	// Fetch page with targetBudget
	budget := 500000.0
	page, err := repo.FetchListingPage(ctx, domain.ValidatedSearchInput{
		MinPrice:     &minP,
		MaxPrice:     &maxP,
		MinBedrooms:  &minB,
		City:         &city,
		Keyword:      &kw,
		TargetBudget: &budget,
		Page:         1,
		PageSize:     2,
	}, 0)
	if err != nil {
		t.Fatalf("FetchListingPage failed: %v", err)
	}
	if len(page) == 0 {
		t.Fatalf("expected non-empty page")
	}
	if page[0].RelevanceScore <= 0 {
		t.Errorf("expected positive relevance score, got %f", page[0].RelevanceScore)
	}

	// Fetch page with large offset returns empty slice
	emptyPage, err := repo.FetchListingPage(ctx, domain.ValidatedSearchInput{
		Page:     1,
		PageSize: 10,
	}, 100)
	if err != nil {
		t.Fatalf("FetchListingPage offset 100 failed: %v", err)
	}
	if len(emptyPage) != 0 {
		t.Errorf("expected empty page for large offset, got %d items", len(emptyPage))
	}
}

func TestMemoryRepository_Backfilling(t *testing.T) {
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	source := "MLS_A"
	rec1 := domain.Listing{
		ID:          "L-1",
		Source:      &source,
		Address:     "123 Street",
		City:        "Austin",
		Price:       300000,
		Bedrooms:    2,
		Description: "Cozy home",
		ListedDate:  "2026-10-01T00:00:00.000Z",
	}

	// First insert
	n1, err := repo.InsertOrBackfillListings(ctx, []domain.Listing{rec1})
	if err != nil || n1 != 1 {
		t.Fatalf("first insert failed: n=%d, err=%v", n1, err)
	}

	// Second insert backfilling state, zip, sqft, bathrooms, lat/lon
	state := "TX"
	zip := "78701"
	sqft := 1100
	bath := 1.5
	lat := 30.2
	lon := -97.7
	rec2 := rec1
	rec2.State = &state
	rec2.Zip = &zip
	rec2.Sqft = &sqft
	rec2.Bathrooms = &bath
	rec2.Latitude = &lat
	rec2.Longitude = &lon

	n2, err := repo.InsertOrBackfillListings(ctx, []domain.Listing{rec2})
	if err != nil || n2 != 1 {
		t.Fatalf("backfill insert failed: n=%d, err=%v", n2, err)
	}

	// Verify backfilled
	page, err := repo.FetchListingPage(ctx, domain.ValidatedSearchInput{Page: 1, PageSize: 10}, 0)
	if err != nil || len(page) != 1 {
		t.Fatalf("failed to fetch backfilled listing: %v", err)
	}
	item := page[0]
	if item.State == nil || *item.State != "TX" || item.Sqft == nil || *item.Sqft != 1100 {
		t.Errorf("item was not backfilled: %+v", item)
	}
}

func TestMemoryRepository_TieBreakingAndFilters(t *testing.T) {
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	sourceA := "A"
	sourceB := "B"
	id1 := "uuid-1"
	id2 := "uuid-2"
	sameDate := "2026-10-01T00:00:00.000Z"

	listings := []domain.Listing{
		{
			ID:          "L-01",
			Source:      &sourceA,
			InternalID:  &id1,
			Address:     "Addr 1",
			City:        "Austin",
			Price:       300000,
			Bedrooms:    2,
			Description: "desc",
			ListedDate:  sameDate,
		},
		{
			ID:          "L-01",
			Source:      &sourceB,
			InternalID:  &id2,
			Address:     "Addr 2",
			City:        "Austin",
			Price:       300000,
			Bedrooms:    2,
			Description: "desc",
			ListedDate:  sameDate,
		},
		{
			ID:          "L-02",
			Source:      &sourceA,
			InternalID:  &id1,
			Address:     "Addr 3",
			City:        "Austin",
			Price:       300000,
			Bedrooms:    2,
			Description: "desc",
			ListedDate:  sameDate,
		},
	}

	_, _ = repo.InsertOrBackfillListings(ctx, listings)

	// Fetch without target budget -> identical relevance score
	page, err := repo.FetchListingPage(ctx, domain.ValidatedSearchInput{
		Page:     1,
		PageSize: 10,
	}, 0)
	if err != nil {
		t.Fatalf("FetchListingPage failed: %v", err)
	}
	if len(page) != 3 {
		t.Fatalf("expected 3 items, got %d", len(page))
	}
	// L-01 source A should precede L-01 source B
	if page[0].ID != "L-01" || *page[0].Source != "A" {
		t.Errorf("expected L-01 source A first, got %+v", page[0])
	}
	if page[1].ID != "L-01" || *page[1].Source != "B" {
		t.Errorf("expected L-01 source B second, got %+v", page[1])
	}
	if page[2].ID != "L-02" {
		t.Errorf("expected L-02 third, got %+v", page[2])
	}

	// Filter checks: minPrice, maxPrice, minBedrooms
	pMin := 350000
	cMin, _ := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{MinPrice: &pMin})
	if cMin != 0 {
		t.Errorf("expected 0 for minPrice 350k, got %d", cMin)
	}

	pMax := 250000
	cMax, _ := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{MaxPrice: &pMax})
	if cMax != 0 {
		t.Errorf("expected 0 for maxPrice 250k, got %d", cMax)
	}

	bMin := 4
	cB, _ := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{MinBedrooms: &bMin})
	if cB != 0 {
		t.Errorf("expected 0 for minBedrooms 4, got %d", cB)
	}
}
