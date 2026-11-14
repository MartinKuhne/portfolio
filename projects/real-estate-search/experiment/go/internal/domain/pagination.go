package domain

const (
	DefaultPage     = 1
	DefaultPageSize = 10
	MaxPageSize     = 100
)

// PaginationMeta contains pagination counts and bounds.
type PaginationMeta struct {
	Page         int `json:"page"`
	PageSize     int `json:"pageSize"`
	TotalResults int `json:"totalResults"`
	TotalPages   int `json:"totalPages"`
}
