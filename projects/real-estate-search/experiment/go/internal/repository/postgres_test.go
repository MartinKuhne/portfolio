package repository_test

import (
	"context"
	"testing"

	"real-estate-search/internal/repository"
)

func TestPostgresRepository_InvalidUrl(t *testing.T) {
	ctx := context.Background()
	_, err := repository.NewPostgresRepository(ctx, "invalid://user:pass@127.0.0.1:0/nonexistent?sslmode=disable")
	if err == nil {
		t.Errorf("expected error connecting to invalid postgres url")
	}
}
