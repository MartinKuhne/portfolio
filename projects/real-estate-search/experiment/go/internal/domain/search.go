package domain

const (
	ReferenceDateISO = "2026-11-01T00:00:00.000Z"
	BudgetWeight     = 0.60
	RecencyWeight    = 0.40
	MaxRecencyDays   = 365.0

	MaxPgInt         = 2147483647
	MaxCityLength    = 100
	MaxKeywordLength = 255
)

// RawSearchInput holds uncoerced inputs from GraphQL JSONScalar or external requests.
type RawSearchInput struct {
	MinPrice     any `json:"minPrice,omitempty"`
	MaxPrice     any `json:"maxPrice,omitempty"`
	MinBedrooms  any `json:"minBedrooms,omitempty"`
	City         any `json:"city,omitempty"`
	Keyword      any `json:"keyword,omitempty"`
	TargetBudget any `json:"targetBudget,omitempty"`
	Page         any `json:"page,omitempty"`
	PageSize     any `json:"pageSize,omitempty"`
}

// ValidatedSearchInput holds strictly typed and bounded search criteria.
type ValidatedSearchInput struct {
	MinPrice     *float64
	MaxPrice     *float64
	MinBedrooms  *int
	City         *string
	Keyword      *string
	TargetBudget *float64
	Page         int
	PageSize     int
}

// SearchResponse is the domain result of executeSearch.
type SearchResponse struct {
	OK         bool            `json:"ok"`
	Listings   []Listing       `json:"listings"`
	Pagination *PaginationMeta `json:"pagination,omitempty"`
	Error      *SearchError    `json:"error,omitempty"`
}
