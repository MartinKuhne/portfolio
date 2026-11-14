package repository

import (
	"context"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"real-estate-search/internal/domain"
	"real-estate-search/internal/scoring"
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
	if input.MinPrice != nil && listing.Price < *input.MinPrice {
		return false
	}
	if input.MaxPrice != nil && listing.Price > *input.MaxPrice {
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

func (m *MemoryRepository) CountMatchingListings(_ context.Context, input domain.ValidatedSearchInput) (int, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	count := 0
	for _, l := range m.listings {
		if matchesFilter(l, input) {
			count++
		}
	}
	return count, nil
}

func (m *MemoryRepository) FetchListingPage(_ context.Context, input domain.ValidatedSearchInput, offset int) ([]domain.Listing, error) {
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

		tA, _ := time.Parse(time.RFC3339, a.ListedDate)
		tB, _ := time.Parse(time.RFC3339, b.ListedDate)
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
		return []domain.Listing{}, nil
	}

	end := offset + input.PageSize
	if end > len(matched) {
		end = len(matched)
	}

	result := make([]domain.Listing, end-offset)
	copy(result, matched[offset:end])
	return result, nil
}

func (m *MemoryRepository) InsertOrBackfillListings(_ context.Context, records []domain.Listing) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	count := 0
	for _, r := range records {
		rec := r
		if rec.InternalID == nil || *rec.InternalID == "" {
			u := uuid.NewString()
			rec.InternalID = &u
		}
		if rec.Source == nil || *rec.Source == "" {
			src := "SEED"
			rec.Source = &src
		}

		foundIndex := -1
		for i, existing := range m.listings {
			existingSource := "SEED"
			if existing.Source != nil {
				existingSource = *existing.Source
			}
			newSource := "SEED"
			if rec.Source != nil {
				newSource = *rec.Source
			}

			if existing.ID == rec.ID && existingSource == newSource {
				foundIndex = i
				break
			}
		}

		if foundIndex >= 0 {
			// Backfill only nil fields
			existing := &m.listings[foundIndex]
			if existing.State == nil && rec.State != nil {
				existing.State = rec.State
			}
			if existing.Zip == nil && rec.Zip != nil {
				existing.Zip = rec.Zip
			}
			if existing.Bathrooms == nil && rec.Bathrooms != nil {
				existing.Bathrooms = rec.Bathrooms
			}
			if existing.Sqft == nil && rec.Sqft != nil {
				existing.Sqft = rec.Sqft
			}
			if existing.Latitude == nil && rec.Latitude != nil {
				existing.Latitude = rec.Latitude
			}
			if existing.Longitude == nil && rec.Longitude != nil {
				existing.Longitude = rec.Longitude
			}
		} else {
			m.listings = append(m.listings, rec)
		}
		count++
	}
	return count, nil
}

func (m *MemoryRepository) EnsureSchema(_ context.Context) error {
	return nil
}

func (m *MemoryRepository) Close() error {
	return nil
}
