package graphql

import (
	"context"
	"errors"

	"github.com/graphql-go/graphql"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/search"
	"real-estate-search/internal/telemetry"
)

type contextKey string

const serverContextKey contextKey = "serverContext"

type ServerContext struct {
	HasDomainError   bool
	HasInternalError bool
}

func GetServerContext(ctx context.Context) *ServerContext {
	if sc, ok := ctx.Value(serverContextKey).(*ServerContext); ok {
		return sc
	}
	return nil
}

// SearchListingsSuccess represents the success variant of SearchListingsResult.
type SearchListingsSuccess struct {
	Listings   []domain.Listing      `json:"listings"`
	Pagination domain.PaginationMeta `json:"pagination"`
}

// SearchValidationError represents the validation error variant of SearchListingsResult.
type SearchValidationError struct {
	Code    string  `json:"code"`
	Message string  `json:"message"`
	Field   *string `json:"field,omitempty"`
}

// SearchOutOfRangeError represents the out-of-range pagination error variant.
type SearchOutOfRangeError struct {
	Code       string  `json:"code"`
	Message    string  `json:"message"`
	Field      *string `json:"field,omitempty"`
	TotalPages int     `json:"totalPages"`
}

var (
	// SearchErrorInterface defines the interface SearchError.
	SearchErrorInterface *graphql.Interface

	// SearchValidationErrorType defines the SearchValidationError type.
	SearchValidationErrorType *graphql.Object

	// SearchOutOfRangeErrorType defines the SearchOutOfRangeError type.
	SearchOutOfRangeErrorType *graphql.Object

	// SearchListingsResultUnion defines the SearchListingsResult union.
	SearchListingsResultUnion *graphql.Union
)

// ListingType defines the GraphQL Listing object.
var ListingType = graphql.NewObject(graphql.ObjectConfig{
	Name: "Listing",
	Fields: graphql.Fields{
		"internalId":     &graphql.Field{Type: graphql.ID},
		"id":             &graphql.Field{Type: graphql.NewNonNull(graphql.ID)},
		"source":         &graphql.Field{Type: graphql.String},
		"address":        &graphql.Field{Type: graphql.NewNonNull(graphql.String)},
		"city":           &graphql.Field{Type: graphql.NewNonNull(graphql.String)},
		"state":          &graphql.Field{Type: graphql.String},
		"zip":            &graphql.Field{Type: graphql.String},
		"price":          &graphql.Field{Type: graphql.NewNonNull(graphql.Int)},
		"bedrooms":       &graphql.Field{Type: graphql.NewNonNull(graphql.Int)},
		"bathrooms":      &graphql.Field{Type: graphql.Float},
		"sqft":           &graphql.Field{Type: graphql.Int},
		"latitude":       &graphql.Field{Type: graphql.Float},
		"longitude":      &graphql.Field{Type: graphql.Float},
		"status":         &graphql.Field{Type: graphql.String},
		"description":    &graphql.Field{Type: graphql.NewNonNull(graphql.String)},
		"listedDate":     &graphql.Field{Type: graphql.NewNonNull(graphql.String)},
		"relevanceScore": &graphql.Field{Type: graphql.NewNonNull(graphql.Float)},
	},
})

// PaginationMetaType defines the pagination metadata object.
var PaginationMetaType = graphql.NewObject(graphql.ObjectConfig{
	Name: "PaginationMeta",
	Fields: graphql.Fields{
		"page":         &graphql.Field{Type: graphql.NewNonNull(graphql.Int)},
		"pageSize":     &graphql.Field{Type: graphql.NewNonNull(graphql.Int)},
		"totalResults": &graphql.Field{Type: graphql.NewNonNull(graphql.Int)},
		"totalPages":   &graphql.Field{Type: graphql.NewNonNull(graphql.Int)},
	},
})

// SearchErrorCodeEnum defines the domain error code enum.
var SearchErrorCodeEnum = graphql.NewEnum(graphql.EnumConfig{
	Name: "SearchErrorCode",
	Values: graphql.EnumValueConfigMap{
		"VALIDATION_ERROR": &graphql.EnumValueConfig{Value: "VALIDATION_ERROR"},
		"OUT_OF_RANGE":     &graphql.EnumValueConfig{Value: "OUT_OF_RANGE"},
	},
})

// SearchListingsSuccessType defines the SearchListingsSuccess type.
var SearchListingsSuccessType = graphql.NewObject(graphql.ObjectConfig{
	Name: "SearchListingsSuccess",
	Fields: graphql.Fields{
		"listings": &graphql.Field{
			Type: graphql.NewNonNull(graphql.NewList(graphql.NewNonNull(ListingType))),
		},
		"pagination": &graphql.Field{
			Type: graphql.NewNonNull(PaginationMetaType),
		},
	},
})

// SearchListingsInputType defines the uncoerced input object.
var SearchListingsInputType = graphql.NewInputObject(graphql.InputObjectConfig{
	Name: "SearchListingsInput",
	Fields: graphql.InputObjectConfigFieldMap{
		"minPrice":     &graphql.InputObjectFieldConfig{Type: JSONScalar},
		"maxPrice":     &graphql.InputObjectFieldConfig{Type: JSONScalar},
		"minBedrooms":  &graphql.InputObjectFieldConfig{Type: JSONScalar},
		"city":         &graphql.InputObjectFieldConfig{Type: graphql.String},
		"keyword":      &graphql.InputObjectFieldConfig{Type: graphql.String},
		"targetBudget": &graphql.InputObjectFieldConfig{Type: JSONScalar},
		"page":         &graphql.InputObjectFieldConfig{Type: JSONScalar},
		"pageSize":     &graphql.InputObjectFieldConfig{Type: JSONScalar},
	},
})

func init() {
	SearchErrorInterface = graphql.NewInterface(graphql.InterfaceConfig{
		Name: "SearchError",
		Fields: graphql.Fields{
			"code":    &graphql.Field{Type: graphql.NewNonNull(SearchErrorCodeEnum)},
			"message": &graphql.Field{Type: graphql.NewNonNull(graphql.String)},
			"field":   &graphql.Field{Type: graphql.String},
		},
		ResolveType: func(p graphql.ResolveTypeParams) *graphql.Object {
			switch p.Value.(type) {
			case SearchOutOfRangeError, *SearchOutOfRangeError:
				return SearchOutOfRangeErrorType
			default:
				return SearchValidationErrorType
			}
		},
	})

	SearchValidationErrorType = graphql.NewObject(graphql.ObjectConfig{
		Name: "SearchValidationError",
		Interfaces: []*graphql.Interface{
			SearchErrorInterface,
		},
		Fields: graphql.Fields{
			"code":    &graphql.Field{Type: graphql.NewNonNull(SearchErrorCodeEnum)},
			"message": &graphql.Field{Type: graphql.NewNonNull(graphql.String)},
			"field":   &graphql.Field{Type: graphql.String},
		},
	})

	SearchOutOfRangeErrorType = graphql.NewObject(graphql.ObjectConfig{
		Name: "SearchOutOfRangeError",
		Interfaces: []*graphql.Interface{
			SearchErrorInterface,
		},
		Fields: graphql.Fields{
			"code":       &graphql.Field{Type: graphql.NewNonNull(SearchErrorCodeEnum)},
			"message":    &graphql.Field{Type: graphql.NewNonNull(graphql.String)},
			"field":      &graphql.Field{Type: graphql.String},
			"totalPages": &graphql.Field{Type: graphql.NewNonNull(graphql.Int)},
		},
	})

	SearchListingsResultUnion = graphql.NewUnion(graphql.UnionConfig{
		Name: "SearchListingsResult",
		Types: []*graphql.Object{
			SearchListingsSuccessType,
			SearchValidationErrorType,
			SearchOutOfRangeErrorType,
		},
		ResolveType: func(p graphql.ResolveTypeParams) *graphql.Object {
			switch p.Value.(type) {
			case SearchListingsSuccess, *SearchListingsSuccess:
				return SearchListingsSuccessType
			case SearchOutOfRangeError, *SearchOutOfRangeError:
				return SearchOutOfRangeErrorType
			case SearchValidationError, *SearchValidationError:
				return SearchValidationErrorType
			default:
				return SearchValidationErrorType
			}
		},
	})
}

// BuildSchema creates the GraphQL executable schema with searchListings query resolver.
func BuildSchema(searchService *search.Service) (graphql.Schema, error) {
	rootQuery := graphql.NewObject(graphql.ObjectConfig{
		Name: "Query",
		Fields: graphql.Fields{
			"searchListings": &graphql.Field{
				Type: SearchListingsResultUnion,
				Args: graphql.FieldConfigArgument{
					"input": &graphql.ArgumentConfig{
						Type: SearchListingsInputType,
					},
				},
				Resolve: func(p graphql.ResolveParams) (any, error) {
					ctx := p.Context
					tracer := telemetry.GetTracer()
					ctx, span := tracer.Start(ctx, "graphql.searchListings",
						trace.WithSpanKind(trace.SpanKindInternal),
						trace.WithAttributes(
							attribute.String("graphql.operation.type", "query"),
							attribute.String("graphql.operation.name", "searchListings"),
						),
					)
					defer span.End()

					serverCtx := GetServerContext(ctx)

					var raw domain.RawSearchInput
					if inputArg, ok := p.Args["input"].(map[string]any); ok {
						raw.MinPrice = inputArg["minPrice"]
						raw.MaxPrice = inputArg["maxPrice"]
						raw.MinBedrooms = inputArg["minBedrooms"]
						raw.City = inputArg["city"]
						raw.Keyword = inputArg["keyword"]
						raw.TargetBudget = inputArg["targetBudget"]
						raw.Page = inputArg["page"]
						raw.PageSize = inputArg["pageSize"]
					}

					resp := searchService.ExecuteSearch(ctx, raw)
					if !resp.OK {
						span.SetAttributes(attribute.String("error.code", string(resp.Error.Code)))
						span.SetStatus(codes.Error, resp.Error.Message)

						if resp.Error.Code == domain.ErrorCodeInternal {
							if serverCtx != nil {
								serverCtx.HasInternalError = true
							}
							telemetry.LogApi(ctx, telemetry.SeverityError, "ERROR", "graphql.resolver_error", map[string]any{
								"graphql.operation.name": "searchListings",
								"error.message":          resp.Error.Message,
							})
							return nil, errors.New("An unexpected internal error occurred.")
						}

						if serverCtx != nil {
							serverCtx.HasDomainError = true
						}

						if resp.Error.Code == domain.ErrorCodeOutOfRange {
							tp := 0
							if resp.Error.TotalPages != nil {
								tp = *resp.Error.TotalPages
							}
							field := "page"
							if resp.Error.Field != nil {
								field = *resp.Error.Field
							}
							return &SearchOutOfRangeError{
								Code:       "OUT_OF_RANGE",
								Message:    resp.Error.Message,
								Field:      &field,
								TotalPages: tp,
							}, nil
						}

						return &SearchValidationError{
							Code:    "VALIDATION_ERROR",
							Message: resp.Error.Message,
							Field:   resp.Error.Field,
						}, nil
					}

					return &SearchListingsSuccess{
						Listings:   resp.Listings,
						Pagination: *resp.Pagination,
					}, nil
				},
			},
		},
	})

	return graphql.NewSchema(graphql.SchemaConfig{
		Query: rootQuery,
		Types: []graphql.Type{
			SearchValidationErrorType,
			SearchOutOfRangeErrorType,
			SearchListingsSuccessType,
		},
	})
}
