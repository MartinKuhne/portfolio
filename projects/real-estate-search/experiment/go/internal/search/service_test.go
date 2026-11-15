package search_test

import (
	"context"
	"errors"
	"testing"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/search"
	"real-estate-search/internal/telemetry"
)

type failingRepo struct {
	failCount bool
	failQuery bool
	repository.Repository
}

func (f *failingRepo) CountMatchingListings(ctx context.Context, input domain.ValidatedSearchInput) (int, error) {
	if f.failCount {
		return 0, errors.New("db count failed")
	}
	return f.Repository.CountMatchingListings(ctx, input)
}

func (f *failingRepo) FetchListingPage(ctx context.Context, input domain.ValidatedSearchInput, offset int) ([]domain.Listing, error) {
	if f.failQuery {
		return nil, errors.New("db query failed")
	}
	return f.Repository.FetchListingPage(ctx, input, offset)
}

func seedTestRepo(t *testing.T) repository.Repository {
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	source := "SEED"
	state := "TX"
	zip := "78701"
	listings := []domain.Listing{
		{
			ID:          "LST-001",
			Source:      &source,
			Address:     "101 Congress Ave",
			City:        "Austin",
			State:       &state,
			Zip:         &zip,
			Price:       450000,
			Bedrooms:    3,
			Description: "Downtown condo with pool and gym.",
			ListedDate:  "2026-10-20T00:00:00.000Z",
		},
		{
			ID:          "LST-002",
			Source:      &source,
			Address:     "202 Barton Springs Rd",
			City:        "Austin",
			State:       &state,
			Zip:         &zip,
			Price:       550000,
			Bedrooms:    3,
			Description: "Ranch home with heated pool.",
			ListedDate:  "2026-10-25T00:00:00.000Z",
		},
		{
			ID:          "LST-003",
			Source:      &source,
			Address:     "303 South Lamar Blvd",
			City:        "Austin",
			State:       &state,
			Zip:         &zip,
			Price:       350000,
			Bedrooms:    2,
			Description: "Modern apartment near music.",
			ListedDate:  "2026-09-15T00:00:00.000Z",
		},
	}

	if _, err := repo.InsertListingsIdempotently(ctx, listings); err != nil {
		t.Fatalf("failed to seed test repo: %v", err)
	}
	return repo
}

func TestExecuteSearch_Success(t *testing.T) {
	ctx := context.Background()
	exporter := tracetest.NewInMemoryExporter()
	processor := sdktrace.NewSimpleSpanProcessor(exporter)

	_ = telemetry.InitTelemetry(ctx, func(opts *telemetry.TelemetryOptions) {
		opts.SpanProcessors = []sdktrace.SpanProcessor{processor}
	})
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	repo := seedTestRepo(t)
	svc := search.NewService(repo)

	resp := svc.ExecuteSearch(ctx, domain.RawSearchInput{
		City:         "Austin",
		TargetBudget: 500000,
		Page:         1,
		PageSize:     2,
	})

	if !resp.OK {
		t.Fatalf("expected OK true, got error: %+v", resp.Error)
	}
	if len(resp.Listings) != 2 {
		t.Fatalf("expected 2 listings, got %d", len(resp.Listings))
	}
	if resp.Pagination == nil || resp.Pagination.TotalResults != 3 || resp.Pagination.TotalPages != 2 {
		t.Errorf("unexpected pagination: %+v", resp.Pagination)
	}

	// Verify spans: search.execute and search.validateInput
	spans := exporter.GetSpans()
	spanNames := make([]string, len(spans))
	for i, s := range spans {
		spanNames[i] = s.Name
	}

	hasExecute := false
	hasValidate := false
	hasDBCount := false
	hasDBQuery := false
	for _, name := range spanNames {
		switch name {
		case "search.execute":
			hasExecute = true
		case "search.validateInput":
			hasValidate = true
		case "search.db.count":
			hasDBCount = true
		case "search.db.query":
			hasDBQuery = true
		}
	}
	if !hasExecute || !hasValidate || !hasDBCount || !hasDBQuery {
		t.Errorf("missing spans in %v", spanNames)
	}
}

func TestExecuteSearch_EmptyResults(t *testing.T) {
	ctx := context.Background()
	repo := seedTestRepo(t)
	svc := search.NewService(repo)

	resp := svc.ExecuteSearch(ctx, domain.RawSearchInput{
		City: "Dallas",
	})

	if !resp.OK {
		t.Fatalf("expected OK true, got error: %+v", resp.Error)
	}
	if len(resp.Listings) != 0 {
		t.Errorf("expected 0 listings, got %d", len(resp.Listings))
	}
	if resp.Pagination.TotalResults != 0 || resp.Pagination.TotalPages != 0 {
		t.Errorf("expected 0 pagination totals, got %+v", resp.Pagination)
	}
}

func TestExecuteSearch_ValidationFailure(t *testing.T) {
	ctx := context.Background()
	repo := seedTestRepo(t)
	svc := search.NewService(repo)

	resp := svc.ExecuteSearch(ctx, domain.RawSearchInput{
		MinPrice: "not-a-number",
	})

	if resp.OK {
		t.Fatalf("expected validation failure, got OK")
	}
	if resp.Error == nil || resp.Error.Code != domain.ErrorCodeValidation {
		t.Errorf("expected VALIDATION_ERROR, got %+v", resp.Error)
	}
	if resp.Error.Field == nil || *resp.Error.Field != "minPrice" {
		t.Errorf("expected field minPrice, got %v", resp.Error.Field)
	}
}

func TestExecuteSearch_OutOfRange(t *testing.T) {
	ctx := context.Background()
	repo := seedTestRepo(t)
	svc := search.NewService(repo)

	resp := svc.ExecuteSearch(ctx, domain.RawSearchInput{
		City: "Austin",
		Page: 99,
	})

	if resp.OK {
		t.Fatalf("expected out of range error, got OK")
	}
	if resp.Error == nil || resp.Error.Code != domain.ErrorCodeOutOfRange {
		t.Errorf("expected OUT_OF_RANGE, got %+v", resp.Error)
	}
	if resp.Error.TotalPages == nil || *resp.Error.TotalPages != 1 {
		t.Errorf("expected totalPages 1, got %v", resp.Error.TotalPages)
	}
}

func TestExecuteSearch_DatabaseErrors(t *testing.T) {
	ctx := context.Background()
	baseRepo := seedTestRepo(t)

	// 1. Count fails
	svcCountFail := search.NewService(&failingRepo{failCount: true, Repository: baseRepo})
	resp1 := svcCountFail.ExecuteSearch(ctx, domain.RawSearchInput{})
	if resp1.OK || resp1.Error == nil || resp1.Error.Code != domain.ErrorCodeInternal {
		t.Errorf("expected INTERNAL_ERROR on count failure, got %+v", resp1)
	}

	// 2. Query fails
	svcQueryFail := search.NewService(&failingRepo{failQuery: true, Repository: baseRepo})
	resp2 := svcQueryFail.ExecuteSearch(ctx, domain.RawSearchInput{})
	if resp2.OK || resp2.Error == nil || resp2.Error.Code != domain.ErrorCodeInternal {
		t.Errorf("expected INTERNAL_ERROR on query failure, got %+v", resp2)
	}
}
