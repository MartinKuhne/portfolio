package repository_test

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/scoring"
)

func TestPostgresRepository_InvalidUrl(t *testing.T) {
	ctx := context.Background()
	_, err := repository.NewPostgresRepository(ctx, "invalid://user:pass@127.0.0.1:0/nonexistent?sslmode=disable")
	if err == nil {
		t.Errorf("expected error connecting to invalid postgres url")
	}

	_, errBadDSN := repository.NewPostgresRepository(ctx, "postgres://[::1]:badport/db")
	if errBadDSN == nil {
		t.Errorf("expected parse error for invalid DSN port")
	}
}

func TestPostgresSQL_SchemaAndIdempotentInsert(t *testing.T) {
	// Verify EnsureSchemaDDL matches client.ts / ADR-009 and omits legacy ALTER TABLE block
	ddl := repository.EnsureSchemaDDL
	for _, required := range []string{
		"CREATE TABLE IF NOT EXISTS listings",
		"listings_id_source_unique",
		"listings_location_gist",
		"listings_listed_date_desc",
		"listings_price_idx",
		"listings_bedrooms_idx",
		"listings_city_lower_idx",
		"pg_trgm",
		"listings_description_trgm",
	} {
		if !strings.Contains(ddl, required) {
			t.Errorf("expected EnsureSchemaDDL to contain %q", required)
		}
	}
	if strings.Contains(ddl, "ALTER TABLE") {
		t.Errorf("EnsureSchemaDDL must not contain legacy ALTER TABLE migration block")
	}

	// Verify InsertListingsIdempotentlySQL uses ON CONFLICT (id, source) DO NOTHING
	insertSQL := repository.InsertListingsIdempotentlySQL
	if !strings.Contains(insertSQL, "ON CONFLICT (id, source) DO NOTHING") {
		t.Errorf("expected ON CONFLICT (id, source) DO NOTHING in SQL, got: %s", insertSQL)
	}
	if strings.Contains(insertSQL, "DO UPDATE") {
		t.Errorf("InsertListingsIdempotentlySQL must not use DO UPDATE")
	}

	// Verify BuildInsertListingArgs defaults and explicit values
	argsDefault := repository.BuildInsertListingArgs(domain.Listing{
		ID:          "  LST-100  ",
		Address:     "  100 Main St  ",
		City:        "  Austin  ",
		Price:       400000,
		Bedrooms:    3,
		Description: "Nice house",
		ListedDate:  "2026-10-01T00:00:00.000Z",
	})
	if len(argsDefault) != 16 {
		t.Fatalf("expected 16 args, got %d", len(argsDefault))
	}
	if u, ok := argsDefault[0].(*uuid.UUID); !ok || u != nil {
		t.Errorf("expected nil *uuid.UUID for unset InternalID, got %#v", argsDefault[0])
	}
	if argsDefault[1] != "LST-100" || argsDefault[2] != "SEED" || argsDefault[3] != "100 Main St" || argsDefault[4] != "Austin" {
		t.Errorf("unexpected trimmed/default args: %#v", argsDefault[:5])
	}
	if argsDefault[14] != "active" {
		t.Errorf("expected default status 'active', got %v", argsDefault[14])
	}

	validUUID := uuid.NewString()
	src := "MLS_A"
	st := "pending"
	argsExplicit := repository.BuildInsertListingArgs(domain.Listing{
		InternalID:  &validUUID,
		ID:          "A1",
		Source:      &src,
		Address:     "123 Main",
		City:        "Reston",
		Price:       500000,
		Bedrooms:    2,
		Status:      &st,
		Description: "",
		ListedDate:  "2026-10-01T00:00:00.000Z",
	})
	if u, ok := argsExplicit[0].(*uuid.UUID); !ok || u == nil || u.String() != validUUID {
		t.Errorf("expected parsed UUID %s, got %#v", validUUID, argsExplicit[0])
	}
	if argsExplicit[2] != "MLS_A" || argsExplicit[14] != "pending" {
		t.Errorf("expected explicit source/status, got %v / %v", argsExplicit[2], argsExplicit[14])
	}
}

func TestPostgresLiveIntegration(t *testing.T) {
	dbURL := strings.TrimSpace(os.Getenv("PG_INTEGRATION_TEST_URL"))
	if dbURL == "" {
		t.Skip("PG_INTEGRATION_TEST_URL is unset; skipping live PostgreSQL integration test")
	}

	ctx := context.Background()
	repo, err := repository.NewPostgresRepository(ctx, dbURL)
	if err != nil {
		t.Fatalf("NewPostgresRepository failed: %v", err)
	}
	defer func() { _ = repo.Close() }()

	tag := uuid.NewString()[:8]
	id := "PG-" + tag + "-1"
	src := "LIVE_TEST_GO"
	city := "PgGoTest_" + tag
	state := "TX"
	zip := "78701"
	bath := 2.5
	sqft := 1800
	lat := 30.2672
	lon := -97.7431
	status := "active"

	t.Cleanup(func() {
		pool, poolErr := pgxpool.New(context.Background(), dbURL)
		if poolErr == nil {
			defer pool.Close()
			_, _ = pool.Exec(context.Background(), `DELETE FROM listings WHERE id = $1 AND source = $2`, id, src)
		}
	})

	record := domain.Listing{
		ID:          id,
		Source:      &src,
		Address:     "100 Live Go PG Way",
		City:        city,
		State:       &state,
		Zip:         &zip,
		Price:       500000,
		Bedrooms:    3,
		Bathrooms:   &bath,
		Sqft:        &sqft,
		Latitude:    &lat,
		Longitude:   &lon,
		Status:      &status,
		Description: "100% live PostgreSQL test with pool",
		ListedDate:  "2026-10-20T00:00:00.000Z",
	}

	first, err := repo.InsertListingsIdempotently(ctx, []domain.Listing{record})
	if err != nil {
		t.Fatalf("first InsertListingsIdempotently failed: %v", err)
	}
	second, err := repo.InsertListingsIdempotently(ctx, []domain.Listing{record})
	if err != nil {
		t.Fatalf("second InsertListingsIdempotently failed: %v", err)
	}
	if first != 1 || second != 0 {
		t.Fatalf("expected (1, 0) inserts, got (%d, %d)", first, second)
	}

	minPrice := 400000.5
	maxPrice := 600000.75
	minBeds := 3
	cityFilter := strings.ToLower(city)
	kw := "100%"
	budget := 500000.0
	criteria := domain.ValidatedSearchInput{
		MinPrice:     &minPrice,
		MaxPrice:     &maxPrice,
		MinBedrooms:  &minBeds,
		City:         &cityFilter,
		Keyword:      &kw,
		TargetBudget: &budget,
		Page:         1,
		PageSize:     10,
	}

	count, err := repo.CountMatchingListings(ctx, criteria)
	if err != nil {
		t.Fatalf("CountMatchingListings failed: %v", err)
	}
	if count != 1 {
		t.Fatalf("expected count=1, got %d", count)
	}

	page, err := repo.FetchListingPage(ctx, criteria, 0)
	if err != nil {
		t.Fatalf("FetchListingPage failed: %v", err)
	}
	if len(page) != 1 || page[0].ID != id {
		t.Fatalf("expected 1 fetched listing with ID %s, got %#v", id, page)
	}

	expectedScore := scoring.ComputeRelevanceScore(float64(page[0].Price), page[0].ListedDate, &budget, nil)
	if page[0].RelevanceScore != expectedScore {
		t.Fatalf("expected score parity %.2f, got %.2f", expectedScore, page[0].RelevanceScore)
	}
}
