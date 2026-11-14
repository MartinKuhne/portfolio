package search

import (
	"context"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/telemetry"
	"real-estate-search/internal/validation"
)

// Service coordinates search input validation, counting, pagination, and listing retrieval.
type Service struct {
	repo repository.Repository
}

// NewService creates a new search service backed by the provided repository.
func NewService(repo repository.Repository) *Service {
	return &Service{repo: repo}
}

func validateInputWithSpan(ctx context.Context, raw domain.RawSearchInput) (context.Context, validation.Result) {
	tracer := telemetry.GetTracer()
	ctx, span := tracer.Start(ctx, "search.validateInput", trace.WithSpanKind(trace.SpanKindInternal))
	defer span.End()

	res := validation.ValidateSearchInput(raw)
	span.SetAttributes(attribute.Bool("search.validation.ok", res.OK))
	if !res.OK && res.Error != nil {
		span.SetStatus(codes.Error, res.Error.Message)
	}
	return ctx, res
}

// ExecuteSearch executes a validated, paginated property search with OpenTelemetry spans and logs.
func (s *Service) ExecuteSearch(ctx context.Context, rawInput domain.RawSearchInput) domain.SearchResponse {
	tracer := telemetry.GetTracer()
	ctx, span := tracer.Start(ctx, "search.execute", trace.WithSpanKind(trace.SpanKindInternal))
	defer span.End()

	telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "search.started", map[string]any{
		"search.hasCity":         rawInput.City != nil,
		"search.hasKeyword":      rawInput.Keyword != nil,
		"search.hasTargetBudget": rawInput.TargetBudget != nil,
	})

	ctx, valResult := validateInputWithSpan(ctx, rawInput)
	if !valResult.OK {
		span.SetAttributes(attribute.Bool("search.ok", false))
		span.SetStatus(codes.Error, valResult.Error.Message)

		errField := ""
		if valResult.Error.Field != nil {
			errField = *valResult.Error.Field
		}
		telemetry.LogApi(ctx, telemetry.SeverityWarn, "WARN", "search.validation_failed", map[string]any{
			"error.code":  string(valResult.Error.Code),
			"error.field": errField,
		})

		return domain.SearchResponse{
			OK:       false,
			Listings: []domain.Listing{},
			Error:    valResult.Error,
		}
	}

	input := valResult.Value
	span.SetAttributes(
		attribute.Int("search.page", input.Page),
		attribute.Int("search.pageSize", input.PageSize),
	)

	totalResults, err := s.repo.CountMatchingListings(ctx, input)
	if err != nil {
		span.RecordError(err)
		span.SetAttributes(attribute.Bool("search.ok", false))
		span.SetStatus(codes.Error, err.Error())
		telemetry.LogApi(ctx, telemetry.SeverityError, "ERROR", "search.internal_error", map[string]any{
			"error.message": err.Error(),
		})
		return domain.SearchResponse{
			OK:       false,
			Listings: []domain.Listing{},
			Error: &domain.SearchError{
				Code:    domain.ErrorCodeInternal,
				Message: "An unexpected database error occurred.",
			},
		}
	}

	span.SetAttributes(attribute.Int("search.totalResults", totalResults))

	plan := repository.PlanPagination(input.Page, input.PageSize, totalResults)
	if plan.Status == repository.PaginationStatusEmpty {
		span.SetAttributes(
			attribute.Bool("search.ok", true),
			attribute.Int("search.returnedCount", 0),
		)
		telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "search.completed", map[string]any{
			"search.page":          input.Page,
			"search.pageSize":      input.PageSize,
			"search.totalResults":  0,
			"search.returnedCount": 0,
		})
		return domain.SearchResponse{
			OK:         true,
			Listings:   []domain.Listing{},
			Pagination: &plan.Pagination,
		}
	}

	if plan.Status == repository.PaginationStatusOutOfRange {
		span.SetAttributes(attribute.Bool("search.ok", false))
		span.SetStatus(codes.Error, plan.Error.Message)
		telemetry.LogApi(ctx, telemetry.SeverityWarn, "WARN", "search.out_of_range", map[string]any{
			"search.page":         input.Page,
			"search.totalPages":   plan.TotalPages,
			"search.totalResults": totalResults,
		})
		return domain.SearchResponse{
			OK:       false,
			Listings: []domain.Listing{},
			Error:    plan.Error,
		}
	}

	listings, err := s.repo.FetchListingPage(ctx, input, plan.Offset)
	if err != nil {
		span.RecordError(err)
		span.SetAttributes(attribute.Bool("search.ok", false))
		span.SetStatus(codes.Error, err.Error())
		telemetry.LogApi(ctx, telemetry.SeverityError, "ERROR", "search.internal_error", map[string]any{
			"error.message": err.Error(),
		})
		return domain.SearchResponse{
			OK:       false,
			Listings: []domain.Listing{},
			Error: &domain.SearchError{
				Code:    domain.ErrorCodeInternal,
				Message: "An unexpected database error occurred.",
			},
		}
	}

	span.SetAttributes(
		attribute.Bool("search.ok", true),
		attribute.Int("search.returnedCount", len(listings)),
	)
	telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "search.completed", map[string]any{
		"search.page":          input.Page,
		"search.pageSize":      input.PageSize,
		"search.totalResults":  totalResults,
		"search.returnedCount": len(listings),
	})

	return domain.SearchResponse{
		OK:         true,
		Listings:   listings,
		Pagination: &plan.Pagination,
	}
}
