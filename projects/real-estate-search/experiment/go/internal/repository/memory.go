package repository

import (
	"context"
	"sort"
	"strings"
	"sync"

	"github.com/google/uuid"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/scoring"
	"real-estate-search/internal/telemetry"
)

// MemoryRepository is an in-memory thread-safe implementation of Repository.
type MemoryRepository struct {
	mu       sync.RWMutex
	listings []domain.Listing
}

// NewMemoryRepository initializes an empty in-memory repository.
func NewMemoryRepository() *MemoryRepository {
	return &MemoryRepository{
		listings: make([]domain.Listing, 0),
	}
}

func matchesFilter(listing domain.Listing, input domain.ValidatedSearchInput) bool {
	if input.MinPrice != nil && float64(listing.Price) < *input.MinPrice {
		return false
	}
	if input.MaxPrice != nil && float64(listing.Price) > *input.MaxPrice {
		return false
	}
	if input.MinBedrooms != nil && listing.Bedrooms < *input.MinBedrooms {
		return false
	}
	if input.City != nil && *input.City != "" {
		if !strings.EqualFold(listing.City, *input.City) {
			return false
		}
	}
	if input.Keyword != nil && *input.Keyword != "" {
		if !strings.Contains(strings.ToLower(listing.Description), strings.ToLower(*input.Keyword)) {
			return false
		}
	}
	return true
}

func (m *MemoryRepository) CountMatchingListings(ctx context.Context, input domain.ValidatedSearchInput) (int, error) {
	tracer := telemetry.GetTracer()
	_, span := tracer.Start(ctx, "search.db.count",
		trace.WithSpanKind(trace.SpanKindClient),
		trace.WithAttributes(
			attribute.String("db.system", "postgresql"),
			attribute.String("db.operation.name", "SELECT"),
			attribute.String("db.collection.name", "listings"),
		),
	)
	defer span.End()

	m.mu.RLock()
	defer m.mu.RUnlock()

	count := 0
	for _, l := range m.listings {
		if matchesFilter(l, input) {
			count++
		}
	}
	span.SetAttributes(attribute.Int("search.totalResults", count))
	return count, nil
}

func (m *MemoryRepository) FetchListingPage(ctx context.Context, input domain.ValidatedSearchInput, offset int) ([]domain.Listing, error) {
	tracer := telemetry.GetTracer()
	_, span := tracer.Start(ctx, "search.db.query",
		trace.WithSpanKind(trace.SpanKindClient),
		trace.WithAttributes(
			attribute.String("db.system", "postgresql"),
			attribute.String("db.operation.name", "SELECT"),
			attribute.String("db.collection.name", "listings"),
			attribute.Int("search.page", input.Page),
			attribute.Int("search.pageSize", input.PageSize),
		),
	)
	defer span.End()

	m.mu.RLock()
	defer m.mu.RUnlock()

	var matched []domain.Listing
	for _, l := range m.listings {
		if matchesFilter(l, input) {
			item := l
			item.RelevanceScore = scoring.ComputeRelevanceScore(
				float64(l.Price),
				l.ListedDate,
				input.TargetBudget,
				nil,
			)
			matched = append(matched, item)
		}
	}

	// Deterministic sorting: score DESC, listedDate DESC, id ASC, source ASC, internalID ASC
	sort.Slice(matched, func(i, j int) bool {
		a, b := matched[i], matched[j]
		if a.RelevanceScore != b.RelevanceScore {
			return a.RelevanceScore > b.RelevanceScore
		}

		tA, _ := scoring.ParseISODate(a.ListedDate)
		tB, _ := scoring.ParseISODate(b.ListedDate)
		if !tA.Equal(tB) {
			return tA.After(tB)
		}

		if a.ID != b.ID {
			return a.ID < b.ID
		}

		srcA := ""
		if a.Source != nil {
			srcA = *a.Source
		}
		srcB := ""
		if b.Source != nil {
			srcB = *b.Source
		}
		if srcA != srcB {
			return srcA < srcB
		}

		idA := ""
		if a.InternalID != nil {
			idA = *a.InternalID
		}
		idB := ""
		if b.InternalID != nil {
			idB = *b.InternalID
		}
		return idA < idB
	})

	if offset >= len(matched) {
		span.SetAttributes(attribute.Int("search.returnedCount", 0))
		return []domain.Listing{}, nil
	}

	end := offset + input.PageSize
	if end > len(matched) {
		end = len(matched)
	}

	result := make([]domain.Listing, end-offset)
	copy(result, matched[offset:end])
	span.SetAttributes(attribute.Int("search.returnedCount", len(result)))
	return result, nil
}

// InsertListingsIdempotently inserts listings by (id, source), skipping rows that already exist.
func (m *MemoryRepository) InsertListingsIdempotently(_ context.Context, records []domain.Listing) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	inserted := 0
	for _, r := range records {
		rec := r
		rec.ID = strings.TrimSpace(rec.ID)
		if rec.InternalID == nil || *rec.InternalID == "" {
			u := uuid.NewString()
			rec.InternalID = &u
		}
		if rec.Source == nil || *rec.Source == "" {
			src := "SEED"
			rec.Source = &src
		}

		newSource := *rec.Source
		exists := false
		for _, existing := range m.listings {
			existingSource := "SEED"
			if existing.Source != nil && *existing.Source != "" {
				existingSource = *existing.Source
			}
			if existing.ID == rec.ID && existingSource == newSource {
				exists = true
				break
			}
		}

		if exists {
			continue
		}

		m.listings = append(m.listings, rec)
		inserted++
	}
	return inserted, nil
}

func (m *MemoryRepository) EnsureSchema(_ context.Context) error {
	return nil
}

func (m *MemoryRepository) Close() error {
	return nil
}
