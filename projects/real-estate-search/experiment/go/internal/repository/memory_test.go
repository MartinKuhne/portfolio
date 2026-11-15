package repository_test

import (
	"context"
	"testing"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/telemetry"
)

func TestMemoryRepository_SeedAndQuery(t *testing.T) {
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	// Seed repository
	if err := repository.SeedDatabase(ctx, repo); err != nil {
		t.Fatalf("SeedDatabase failed: %v", err)
	}

	// Count unfiltered -> all 24 listings matching seed.ts
	count, err := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{
		Page:     1,
		PageSize: 10,
	})
	if err != nil {
		t.Fatalf("CountMatchingListings failed: %v", err)
	}
	if count != 24 {
		t.Errorf("expected 24 seeded listings, got %d", count)
	}

	// Verify 6 listings per city (Austin, Denver, Seattle, Miami)
	for _, c := range []string{"Austin", "Denver", "Seattle", "Miami"} {
		city := c
		cityCount, err := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{
			City:     &city,
			Page:     1,
			PageSize: 10,
		})
		if err != nil {
			t.Fatalf("CountMatchingListings(%s) failed: %v", c, err)
		}
		if cityCount != 6 {
			t.Errorf("expected 6 listings in %s, got %d", c, cityCount)
		}
	}

	// Verify LST-005 and LST-006 parity with seed.ts
	seed := repository.SeedListings()
	if len(seed) != 24 {
		t.Fatalf("expected 24 SeedListings, got %d", len(seed))
	}
	if seed[4].ID != "LST-005" || seed[4].Address != "505 Guadalupe St" || seed[4].Price != 720000 {
		t.Errorf("LST-005 mismatch: %+v", seed[4])
	}
	if seed[5].ID != "LST-006" || seed[5].Address != "606 East 6th St" || seed[5].Price != 480000 {
		t.Errorf("LST-006 mismatch: %+v", seed[5])
	}

	// Filter by price and bedrooms
	minP := 400000.0
	maxP := 600000.0
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

func TestMemoryRepository_InsertListingsIdempotently(t *testing.T) {
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

	// First insert -> 1
	n1, err := repo.InsertListingsIdempotently(ctx, []domain.Listing{rec1})
	if err != nil || n1 != 1 {
		t.Fatalf("first insert failed: n=%d, err=%v", n1, err)
	}

	// Duplicate insert with additional fields -> ON CONFLICT DO NOTHING returns 0 and does not mutate
	state := "TX"
	zip := "78701"
	sqft := 1100
	rec2 := rec1
	rec2.State = &state
	rec2.Zip = &zip
	rec2.Sqft = &sqft

	n2, err := repo.InsertListingsIdempotently(ctx, []domain.Listing{rec2})
	if err != nil || n2 != 0 {
		t.Fatalf("expected 0 inserted on duplicate (id, source), got n=%d, err=%v", n2, err)
	}

	page, err := repo.FetchListingPage(ctx, domain.ValidatedSearchInput{Page: 1, PageSize: 10}, 0)
	if err != nil || len(page) != 1 {
		t.Fatalf("failed to fetch listing: %v", err)
	}
	if page[0].State != nil || page[0].Sqft != nil {
		t.Errorf("duplicate insert should not backfill/overwrite existing record: %+v", page[0])
	}

	// Same ID with different source -> inserts 1
	sourceB := "MLS_B"
	rec3 := rec1
	rec3.Source = &sourceB
	n3, err := repo.InsertListingsIdempotently(ctx, []domain.Listing{rec3})
	if err != nil || n3 != 1 {
		t.Fatalf("expected 1 inserted for different source, got n=%d, err=%v", n3, err)
	}
}

func TestMemoryRepository_DecimalPricePrecisionAndDateTieBreak(t *testing.T) {
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	sourceA := "A"
	sourceB := "B"
	id1 := "uuid-1"
	id2 := "uuid-2"

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
			ListedDate:  "2026-10-01", // date-only format supported by scoring.ParseISODate
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
			ListedDate:  "2026-10-01T00:00:00.000Z",
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
			ListedDate:  "2026-10-01T00:00:00.000Z",
		},
	}

	_, _ = repo.InsertListingsIdempotently(ctx, listings)

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

	// Decimal price filter precision
	pMinFrac := 300000.5
	cMinFrac, _ := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{MinPrice: &pMinFrac})
	if cMinFrac != 0 {
		t.Errorf("expected 0 for minPrice 300000.5 against 300000 listings, got %d", cMinFrac)
	}

	pMaxFrac := 299999.9
	cMaxFrac, _ := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{MaxPrice: &pMaxFrac})
	if cMaxFrac != 0 {
		t.Errorf("expected 0 for maxPrice 299999.9 against 300000 listings, got %d", cMaxFrac)
	}

	pMinOk := 299999.5
	pMaxOk := 300000.5
	cBoth, _ := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{MinPrice: &pMinOk, MaxPrice: &pMaxOk})
	if cBoth != 3 {
		t.Errorf("expected 3 for [299999.5, 300000.5], got %d", cBoth)
	}

	bMin := 4
	cB, _ := repo.CountMatchingListings(ctx, domain.ValidatedSearchInput{MinBedrooms: &bMin})
	if cB != 0 {
		t.Errorf("expected 0 for minBedrooms 4, got %d", cB)
	}
}

func TestMemoryRepository_EmitsDatabaseSpans(t *testing.T) {
	ctx := context.Background()
	exporter := tracetest.NewInMemoryExporter()
	processor := sdktrace.NewSimpleSpanProcessor(exporter)

	_ = telemetry.ShutdownTelemetry(ctx)
	if err := telemetry.InitTelemetry(ctx, func(opts *telemetry.TelemetryOptions) {
		opts.SpanProcessors = []sdktrace.SpanProcessor{processor}
	}); err != nil {
		t.Fatalf("InitTelemetry failed: %v", err)
	}
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	repo := repository.NewMemoryRepository()
	if err := repository.SeedDatabase(ctx, repo); err != nil {
		t.Fatalf("SeedDatabase failed: %v", err)
	}

	input := domain.ValidatedSearchInput{Page: 1, PageSize: 5}
	if _, err := repo.CountMatchingListings(ctx, input); err != nil {
		t.Fatalf("CountMatchingListings failed: %v", err)
	}
	if _, err := repo.FetchListingPage(ctx, input, 0); err != nil {
		t.Fatalf("FetchListingPage failed: %v", err)
	}

	spans := exporter.GetSpans()
	var countSpan, querySpan *tracetest.SpanStub
	for _, s := range spans {
		sCopy := s
		switch s.Name {
		case "search.db.count":
			countSpan = &sCopy
		case "search.db.query":
			querySpan = &sCopy
		}
	}

	if countSpan == nil || countSpan.SpanKind != trace.SpanKindClient {
		t.Fatalf("expected SpanKindClient search.db.count span, got: %+v", countSpan)
	}
	if querySpan == nil || querySpan.SpanKind != trace.SpanKindClient {
		t.Fatalf("expected SpanKindClient search.db.query span, got: %+v", querySpan)
	}
}
