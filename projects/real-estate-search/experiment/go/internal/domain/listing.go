package domain

// Listing represents a real estate listing entity per SPEC.md and ADR-009.
type Listing struct {
	InternalID     *string  `json:"internalId,omitempty"`
	ID             string   `json:"id"`
	Source         *string  `json:"source,omitempty"`
	Address        string   `json:"address"`
	City           string   `json:"city"`
	State          *string  `json:"state,omitempty"`
	Zip            *string  `json:"zip,omitempty"`
	Price          int      `json:"price"`
	Bedrooms       int      `json:"bedrooms"`
	Bathrooms      *float64 `json:"bathrooms,omitempty"`
	Sqft           *int     `json:"sqft,omitempty"`
	Latitude       *float64 `json:"latitude,omitempty"`
	Longitude      *float64 `json:"longitude,omitempty"`
	Status         *string  `json:"status,omitempty"`
	Description    string   `json:"description"`
	ListedDate     string   `json:"listedDate"`
	RelevanceScore float64  `json:"relevanceScore"`
}
