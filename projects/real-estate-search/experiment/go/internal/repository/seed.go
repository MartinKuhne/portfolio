package repository

import (
	"context"

	"real-estate-search/internal/domain"
)

// SeedListings provides standard initial listings matching SEED_LISTINGS in TypeScript.
func SeedListings() []domain.Listing {
	source := "SEED"
	stateTX := "TX"
	zip78701 := "78701"
	zip78704 := "78704"
	statusActive := "active"

	b20 := 2.0
	b25 := 2.5
	b15 := 1.5
	b30 := 3.0

	s1550 := 1550
	s1850 := 1850
	s1050 := 1050
	s2200 := 2200
	s2800 := 2800
	s1200 := 1200

	lat1 := 30.2638
	lon1 := -97.7443

	lat2 := 30.2586
	lon2 := -97.7556

	lat3 := 30.2602
	lon3 := -97.7595

	lat4 := 30.2589
	lon4 := -97.7389

	lat5 := 30.2701
	lon5 := -97.7482

	lat6 := 30.2655
	lon6 := -97.7501

	return []domain.Listing{
		{
			ID:          "LST-001",
			Source:      &source,
			Address:     "101 Congress Ave",
			City:        "Austin",
			State:       &stateTX,
			Zip:         &zip78701,
			Price:       450000,
			Bedrooms:    3,
			Bathrooms:   &b20,
			Sqft:        &s1550,
			Latitude:    &lat1,
			Longitude:   &lon1,
			Status:      &statusActive,
			Description: "Spacious condo downtown with access to a swimming pool and gym.",
			ListedDate:  "2026-10-20T00:00:00.000Z",
		},
		{
			ID:          "LST-002",
			Source:      &source,
			Address:     "202 Barton Springs Rd",
			City:        "Austin",
			State:       &stateTX,
			Zip:         &zip78704,
			Price:       550000,
			Bedrooms:    3,
			Bathrooms:   &b25,
			Sqft:        &s1850,
			Latitude:    &lat2,
			Longitude:   &lon2,
			Status:      &statusActive,
			Description: "Beautiful ranch home with heated pool and private patio.",
			ListedDate:  "2026-10-25T00:00:00.000Z",
		},
		{
			ID:          "LST-003",
			Source:      &source,
			Address:     "303 South Lamar Blvd",
			City:        "Austin",
			State:       &stateTX,
			Zip:         &zip78704,
			Price:       350000,
			Bedrooms:    2,
			Bathrooms:   &b15,
			Sqft:        &s1050,
			Latitude:    &lat3,
			Longitude:   &lon3,
			Status:      &statusActive,
			Description: "Modern apartment near trendy restaurants and live music.",
			ListedDate:  "2026-09-15T00:00:00.000Z",
		},
		{
			ID:          "LST-004",
			Source:      &source,
			Address:     "404 Rainey St",
			City:        "Austin",
			State:       &stateTX,
			Zip:         &zip78701,
			Price:       620000,
			Bedrooms:    4,
			Bathrooms:   &b30,
			Sqft:        &s2200,
			Latitude:    &lat4,
			Longitude:   &lon4,
			Status:      &statusActive,
			Description: "Luxurious townhouse featuring a community pool and rooftop deck.",
			ListedDate:  "2026-10-10T00:00:00.000Z",
		},
		{
			ID:          "LST-005",
			Source:      &source,
			Address:     "505 West 6th St",
			City:        "Austin",
			State:       &stateTX,
			Zip:         &zip78701,
			Price:       750000,
			Bedrooms:    4,
			Bathrooms:   &b30,
			Sqft:        &s2800,
			Latitude:    &lat5,
			Longitude:   &lon5,
			Status:      &statusActive,
			Description: "Executive penthouse with panoramic city views and private pool.",
			ListedDate:  "2026-10-28T00:00:00.000Z",
		},
		{
			ID:          "LST-006",
			Source:      &source,
			Address:     "606 Guadalupe St",
			City:        "Austin",
			State:       &stateTX,
			Zip:         &zip78701,
			Price:       420000,
			Bedrooms:    2,
			Bathrooms:   &b15,
			Sqft:        &s1200,
			Latitude:    &lat6,
			Longitude:   &lon6,
			Status:      &statusActive,
			Description: "Charming historic cottage with fenced backyard and garden.",
			ListedDate:  "2026-09-01T00:00:00.000Z",
		},
	}
}

// SeedDatabase seeds the initial dataset idempotently into the repository.
func SeedDatabase(ctx context.Context, repo Repository) error {
	_, err := repo.InsertOrBackfillListings(ctx, SeedListings())
	return err
}
