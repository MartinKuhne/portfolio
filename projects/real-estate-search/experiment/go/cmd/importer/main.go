package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"time"

	"real-estate-search/internal/importer"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/telemetry"
)

func run(ctx context.Context, args []string) error {
	fs := flag.NewFlagSet("importer", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	filePath := fs.String("file", "", "Path to sample_listings.json")
	if err := fs.Parse(args); err != nil {
		return fmt.Errorf("invalid flags: %w", err)
	}

	if err := telemetry.InitTelemetry(ctx); err != nil {
		return fmt.Errorf("failed to initialize OpenTelemetry: %w", err)
	}
	defer func() {
		shutdownCtx, sCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer sCancel()
		_ = telemetry.ShutdownTelemetry(shutdownCtx)
	}()

	var repo repository.Repository
	if dbURL := os.Getenv("DATABASE_URL"); dbURL != "" {
		pgRepo, err := repository.NewPostgresRepository(ctx, dbURL)
		if err != nil {
			return fmt.Errorf("failed to connect to database: %w", err)
		}
		repo = pgRepo
	} else {
		repo = repository.NewMemoryRepository()
	}
	defer func() {
		_ = repo.Close()
	}()

	count, err := importer.ImportSampleListings(ctx, repo, *filePath)
	if err != nil {
		return fmt.Errorf("sample listings import failed: %w", err)
	}

	telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "import.completed", map[string]any{
		"importedCount": count,
	})
	log.Printf("Successfully imported %d new listings.", count)
	return nil
}

func main() {
	if err := run(context.Background(), os.Args[1:]); err != nil {
		log.Fatalf("%v", err)
	}
}
