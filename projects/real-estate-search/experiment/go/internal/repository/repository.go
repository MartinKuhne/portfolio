package repository

import (
	"context"
	"fmt"
	"math"

	"real-estate-search/internal/domain"
)

// PaginationStatus indicates the resolution of pagination bounds.
type PaginationStatus string

const (
	PaginationStatusEmpty      PaginationStatus = "empty"
	PaginationStatusOutOfRange PaginationStatus = "out_of_range"
	PaginationStatusOK         PaginationStatus = "ok"
)

// PaginationPlan evaluates pagination bounds before fetching pages.
type PaginationPlan struct {
	Status     PaginationStatus
	Offset     int
	TotalPages int
	Pagination domain.PaginationMeta
	Error      *domain.SearchError
}

// PlanPagination computes pagination offsets and checks out-of-range bounds per REQ-PAG-005 and REQ-VAL-012.
func PlanPagination(page, pageSize, totalResults int) PaginationPlan {
	if totalResults == 0 {
		return PaginationPlan{
			Status: PaginationStatusEmpty,
			Pagination: domain.PaginationMeta{
				Page:         page,
				PageSize:     pageSize,
				TotalResults: 0,
				TotalPages:   0,
			},
		}
	}

	totalPages := int(math.Ceil(float64(totalResults) / float64(pageSize)))
	if page > totalPages {
		field := "page"
		return PaginationPlan{
			Status:     PaginationStatusOutOfRange,
			TotalPages: totalPages,
			Error: &domain.SearchError{
				Code:       domain.ErrorCodeOutOfRange,
				Message:    fmt.Sprintf("Page %d exceeds total pages %d.", page, totalPages),
				Field:      &field,
				TotalPages: &totalPages,
			},
		}
	}

	return PaginationPlan{
		Status:     PaginationStatusOK,
		Offset:     (page - 1) * pageSize,
		TotalPages: totalPages,
		Pagination: domain.PaginationMeta{
			Page:         page,
			PageSize:     pageSize,
			TotalResults: totalResults,
			TotalPages:   totalPages,
		},
	}
}

// Repository defines the contract for listing persistence and search queries.
type Repository interface {
	CountMatchingListings(ctx context.Context, input domain.ValidatedSearchInput) (int, error)
	FetchListingPage(ctx context.Context, input domain.ValidatedSearchInput, offset int) ([]domain.Listing, error)
	InsertListingsIdempotently(ctx context.Context, records []domain.Listing) (int, error)
	EnsureSchema(ctx context.Context) error
	Close() error
}
