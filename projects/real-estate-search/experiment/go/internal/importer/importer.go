package importer

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strings"
	"time"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/repository"
)

var isoDateLayouts = []string{
	time.RFC3339Nano,
	time.RFC3339,
	"2006-01-02T15:04:05.000Z",
	"2006-01-02T15:04:05Z",
	"2006-01-02",
}

func parseDate(s string) (time.Time, error) {
	for _, l := range isoDateLayouts {
		if t, err := time.Parse(l, s); err == nil {
			return t.UTC(), nil
		}
	}
	return time.Time{}, fmt.Errorf("invalid date: %s", s)
}

// ResolveSampleListingsPath searches for sample_listings.json across known candidate locations.
func ResolveSampleListingsPath(explicitPath string) (string, error) {
	if explicitPath != "" {
		if _, err := os.Stat(explicitPath); err == nil {
			return explicitPath, nil
		}
		return "", fmt.Errorf("Sample listings file not found at: %s", explicitPath)
	}

	cwd, _ := os.Getwd()
	candidates := []string{
		filepath.Join(cwd, "sample_listings.json"),
		filepath.Join(cwd, "..", "sample_listings.json"),
		filepath.Join(cwd, "..", "..", "sample_listings.json"),
		filepath.Join(cwd, "..", "..", "..", "sample_listings.json"),
		filepath.Join(cwd, "..", "..", "..", "..", "sample_listings.json"),
		"/app/sample_listings.json",
	}

	for _, c := range candidates {
		if _, err := os.Stat(c); err == nil {
			return c, nil
		}
	}

	return "", fmt.Errorf("Sample listings file not found in default search paths")
}

func parseOptionalNonEmptyString(m map[string]any, field string, index int) (*string, error) {
	val, exists := m[field]
	if !exists || val == nil {
		return nil, nil
	}
	s, ok := val.(string)
	if !ok || strings.TrimSpace(s) == "" {
		return nil, fmt.Errorf("Invalid listing record at index %d: optional field %q must be a non-empty string.", index, field)
	}
	trimmed := strings.TrimSpace(s)
	return &trimmed, nil
}

// ImportSampleListings reads, validates, and idempotently upserts sample listing records.
func ImportSampleListings(ctx context.Context, repo repository.Repository, filePath string) (int, error) {
	resolvedPath, err := ResolveSampleListingsPath(filePath)
	if err != nil {
		return 0, err
	}

	data, err := os.ReadFile(resolvedPath)
	if err != nil {
		return 0, fmt.Errorf("Failed to read sample listings file at %s: %w", resolvedPath, err)
	}

	var rawList []map[string]any
	if err := json.Unmarshal(data, &rawList); err != nil {
		return 0, fmt.Errorf("Sample listings file contains invalid JSON: %w", err)
	}

	records := make([]domain.Listing, 0, len(rawList))
	for i, item := range rawList {
		id, _ := item["id"].(string)
		address, _ := item["address"].(string)
		city, _ := item["city"].(string)
		desc, _ := item["description"].(string)
		listedDateStr, _ := item["listedDate"].(string)

		if strings.TrimSpace(id) == "" ||
			strings.TrimSpace(address) == "" ||
			strings.TrimSpace(city) == "" ||
			desc == "" ||
			strings.TrimSpace(listedDateStr) == "" {
			return 0, fmt.Errorf("Invalid listing record at index %d: required fields (id, address, city, price, bedrooms, description, listedDate) are missing or invalid.", i)
		}

		priceNum, okPrice := item["price"].(float64)
		if !okPrice || math.IsNaN(priceNum) || math.IsInf(priceNum, 0) {
			return 0, fmt.Errorf("Invalid listing record at index %d: required fields (id, address, city, price, bedrooms, description, listedDate) are missing or invalid.", i)
		}

		bedNum, okBed := item["bedrooms"].(float64)
		if !okBed || math.IsNaN(bedNum) || math.IsInf(bedNum, 0) {
			return 0, fmt.Errorf("Invalid listing record at index %d: required fields (id, address, city, price, bedrooms, description, listedDate) are missing or invalid.", i)
		}

		parsedDate, err := parseDate(strings.TrimSpace(listedDateStr))
		if err != nil {
			return 0, fmt.Errorf("Invalid listing record at index %d: required fields (id, address, city, price, bedrooms, description, listedDate) are missing or invalid.", i)
		}

		sourcePtr, err := parseOptionalNonEmptyString(item, "source", i)
		if err != nil {
			return 0, err
		}
		sourceVal := "IMPORT"
		if sourcePtr != nil {
			sourceVal = *sourcePtr
		}

		statePtr, err := parseOptionalNonEmptyString(item, "state", i)
		if err != nil {
			return 0, err
		}

		zipPtr, err := parseOptionalNonEmptyString(item, "zip", i)
		if err != nil {
			return 0, err
		}

		statusPtr, err := parseOptionalNonEmptyString(item, "status", i)
		if err != nil {
			return 0, err
		}
		statusVal := "active"
		if statusPtr != nil {
			statusVal = *statusPtr
		}

		var bathrooms *float64
		if bVal, exists := item["bathrooms"]; exists && bVal != nil {
			bNum, ok := bVal.(float64)
			if !ok || math.IsNaN(bNum) || math.IsInf(bNum, 0) || bNum < 0 {
				return 0, fmt.Errorf("Invalid listing record at index %d: optional field \"bathrooms\" must be a finite number >= 0.", i)
			}
			bathrooms = &bNum
		}

		var sqft *int
		if sVal, exists := item["sqft"]; exists && sVal != nil {
			sNum, ok := sVal.(float64)
			if !ok || math.IsNaN(sNum) || math.IsInf(sNum, 0) || sNum != math.Trunc(sNum) || sNum < 0 {
				return 0, fmt.Errorf("Invalid listing record at index %d: optional field \"sqft\" must be a non-negative integer.", i)
			}
			val := int(sNum)
			sqft = &val
		}

		latVal, hasLat := item["latitude"]
		lonVal, hasLon := item["longitude"]
		if hasLat != hasLon {
			return 0, fmt.Errorf("Invalid listing record at index %d: latitude and longitude must both be provided or both omitted.", i)
		}

		var latitude *float64
		var longitude *float64
		if hasLat && hasLon && latVal != nil && lonVal != nil {
			latF, okLat := latVal.(float64)
			lonF, okLon := lonVal.(float64)
			if !okLat || math.IsNaN(latF) || math.IsInf(latF, 0) || latF < -90 || latF > 90 {
				return 0, fmt.Errorf("Invalid listing record at index %d: latitude must be a finite number between -90 and 90.", i)
			}
			if !okLon || math.IsNaN(lonF) || math.IsInf(lonF, 0) || lonF < -180 || lonF > 180 {
				return 0, fmt.Errorf("Invalid listing record at index %d: longitude must be a finite number between -180 and 180.", i)
			}
			latitude = &latF
			longitude = &lonF
		}

		formattedAddress := strings.TrimSpace(address)
		if statePtr != nil && zipPtr != nil {
			formattedAddress = fmt.Sprintf("%s, %s, %s %s", strings.TrimSpace(address), strings.TrimSpace(city), *statePtr, *zipPtr)
		}

		records = append(records, domain.Listing{
			ID:          strings.TrimSpace(id),
			Source:      &sourceVal,
			Address:     formattedAddress,
			City:        strings.TrimSpace(city),
			State:       statePtr,
			Zip:         zipPtr,
			Price:       int(math.Round(priceNum)),
			Bedrooms:    int(math.Round(bedNum)),
			Bathrooms:   bathrooms,
			Sqft:        sqft,
			Latitude:    latitude,
			Longitude:   longitude,
			Status:      &statusVal,
			Description: desc,
			ListedDate:  parsedDate.Format("2006-01-02T15:04:05.000Z"),
		})
	}

	if len(records) == 0 {
		return 0, nil
	}

	return repo.InsertOrBackfillListings(ctx, records)
}
