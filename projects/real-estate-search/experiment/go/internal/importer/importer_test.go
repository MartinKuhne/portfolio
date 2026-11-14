package importer_test

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"real-estate-search/internal/importer"
	"real-estate-search/internal/repository"
)

func writeTempFile(t *testing.T, content string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "sample.json")
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("failed to write temp file: %v", err)
	}
	return path
}

func TestImportSampleListings_SuccessAndIdempotency(t *testing.T) {
	jsonContent := `[
		{
			"id": "A1",
			"source": "MLS_A",
			"address": "123 Main St, Apt 4B",
			"city": "Springfield",
			"state": "VA",
			"zip": "22150",
			"price": 450000,
			"bedrooms": 2,
			"bathrooms": 1.5,
			"sqft": 980,
			"latitude": 38.7893,
			"longitude": -77.1873,
			"listedDate": "2026-08-29",
			"status": "active",
			"description": "Bright top-floor condo near shops and transit."
		},
		{
			"id": "B7",
			"source": "MLS_B",
			"address": "123 Main Street, Unit 4B",
			"city": "Springfield",
			"price": 452000,
			"bedrooms": 2,
			"listedDate": "2026-08-27T10:00:00Z",
			"description": "Top floor condo."
		}
	]`

	path := writeTempFile(t, jsonContent)
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	// First import
	count1, err := importer.ImportSampleListings(ctx, repo, path)
	if err != nil {
		t.Fatalf("import failed: %v", err)
	}
	if count1 != 2 {
		t.Errorf("expected 2 listings imported, got %d", count1)
	}

	// Second import (idempotent)
	count2, err := importer.ImportSampleListings(ctx, repo, path)
	if err != nil {
		t.Fatalf("second import failed: %v", err)
	}
	if count2 != 2 {
		t.Errorf("expected 2 listings upserted, got %d", count2)
	}
}

func TestImportSampleListings_FileNotFound(t *testing.T) {
	repo := repository.NewMemoryRepository()
	_, err := importer.ImportSampleListings(context.Background(), repo, "non_existent_file.json")
	if err == nil || !strings.Contains(err.Error(), "Sample listings file not found") {
		t.Errorf("expected file not found error, got %v", err)
	}
}

func TestImportSampleListings_InvalidJSON(t *testing.T) {
	path := writeTempFile(t, "not-json")
	repo := repository.NewMemoryRepository()
	_, err := importer.ImportSampleListings(context.Background(), repo, path)
	if err == nil || !strings.Contains(err.Error(), "invalid JSON") {
		t.Errorf("expected invalid JSON error, got %v", err)
	}
}

func TestImportSampleListings_EmptyArray(t *testing.T) {
	path := writeTempFile(t, `[]`)
	repo := repository.NewMemoryRepository()
	count, err := importer.ImportSampleListings(context.Background(), repo, path)
	if err != nil {
		t.Fatalf("import empty failed: %v", err)
	}
	if count != 0 {
		t.Errorf("expected 0 imported, got %d", count)
	}
}

func TestResolveSampleListingsPath(t *testing.T) {
	// Explicit existing path
	tmp := writeTempFile(t, `[]`)
	resolved, err := importer.ResolveSampleListingsPath(tmp)
	if err != nil || resolved != tmp {
		t.Errorf("expected %s, got %s (err: %v)", tmp, resolved, err)
	}

	// Non-existing path
	_, err = importer.ResolveSampleListingsPath("non_existing_sample_file_123.json")
	if err == nil {
		t.Errorf("expected error for non-existing path")
	}

	// Default candidate discovery
	if candidate, err := importer.ResolveSampleListingsPath(""); err == nil {
		if !strings.HasSuffix(candidate, "sample_listings.json") {
			t.Errorf("expected sample_listings.json candidate, got %s", candidate)
		}
	}
}

func TestImportSampleListings_ValidationFailures(t *testing.T) {
	tests := []struct {
		name    string
		json    string
		errPart string
	}{
		{
			name:    "missing required id",
			json:    `[{"address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "required fields",
		},
		{
			name:    "invalid listedDate",
			json:    `[{"id": "A", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "description": "d", "listedDate": "not-a-date"}]`,
			errPart: "required fields",
		},
		{
			name:    "negative bathrooms",
			json:    `[{"id": "A", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "bathrooms": -1, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "bathrooms",
		},
		{
			name:    "latitude without longitude",
			json:    `[{"id": "A", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "latitude": 38.0, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "latitude and longitude must both be provided",
		},
		{
			name:    "out of bounds latitude",
			json:    `[{"id": "A", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "latitude": 100.0, "longitude": -77.0, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "latitude must be a finite number between -90 and 90",
		},
		{
			name:    "out of bounds longitude",
			json:    `[{"id": "A", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "latitude": 38.0, "longitude": 200.0, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "longitude must be a finite number between -180 and 180",
		},
		{
			name:    "invalid source type",
			json:    `[{"id": "A", "source": 123, "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "source",
		},
		{
			name:    "invalid status type",
			json:    `[{"id": "A", "status": 123, "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "status",
		},
		{
			name:    "string bathrooms",
			json:    `[{"id": "A", "bathrooms": "2.0", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "bathrooms",
		},
		{
			name:    "string sqft",
			json:    `[{"id": "A", "sqft": "1000", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "sqft",
		},
		{
			name:    "string latitude",
			json:    `[{"id": "A", "latitude": "38.0", "longitude": -77.0, "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "latitude",
		},
		{
			name:    "string longitude",
			json:    `[{"id": "A", "latitude": 38.0, "longitude": "-77.0", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "longitude",
		},
		{
			name:    "invalid sqft float",
			json:    `[{"id": "A", "address": "123 Main", "city": "Springfield", "price": 400000, "bedrooms": 2, "sqft": 1200.5, "description": "d", "listedDate": "2026-01-01"}]`,
			errPart: "sqft",
		},
	}

	repo := repository.NewMemoryRepository()
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			path := writeTempFile(t, tc.json)
			_, err := importer.ImportSampleListings(context.Background(), repo, path)
			if err == nil || !strings.Contains(err.Error(), tc.errPart) {
				t.Errorf("expected error containing %q, got %v", tc.errPart, err)
			}
		})
	}
}
