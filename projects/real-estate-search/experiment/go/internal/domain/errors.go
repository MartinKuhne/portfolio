package domain

// SearchErrorCode represents domain error codes per SPEC.md and ADR-002.
type SearchErrorCode string

const (
	ErrorCodeValidation  SearchErrorCode = "VALIDATION_ERROR"
	ErrorCodeOutOfRange  SearchErrorCode = "OUT_OF_RANGE"
	ErrorCodeInternal    SearchErrorCode = "INTERNAL_ERROR"
)

// SearchError represents a structured search failure conforming to interface SearchError.
type SearchError struct {
	Code       SearchErrorCode `json:"code"`
	Message    string          `json:"message"`
	Field      *string         `json:"field,omitempty"`
	TotalPages *int            `json:"totalPages,omitempty"`
}

func (e *SearchError) Error() string {
	return e.Message
}
