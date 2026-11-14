package main

import (
	"context"
	"flag"
	"log"
	"os"
	"time"

	"real-estate-search/internal/importer"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/telemetry"
)

func main() {
	filePath := flag.String("file", "", "Path to sample_listings.json")
	flag.Parse()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := telemetry.InitTelemetry(ctx); err != nil {
		log.Fatalf("Failed to initialize OpenTelemetry: %v", err)
	}
	defer func() {
		shutdownCtx, sCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer sCancel()
		_ = telemetry.ShutdownTelemetry(shutdownCtx)
	}()

	var repo repository.Repository
	dbURL := os.Getenv("DATABASE_URL")
	if dbURL != "" {
		pgRepo, err := repository.NewPostgresRepository(ctx, dbURL)
		if err != nil {
			log.Fatalf("Failed to connect to database: %v", err)
		}
		repo = pgRepo
		defer repo.Close()
	} else {
		repo = repository.NewMemoryRepository()
	}

	count, err := importer.ImportSampleListings(ctx, repo, *filePath)
	if err != nil {
		log.Fatalf("Sample listings import failed: %v", err)
	}

	telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "import.completed", map[string]any{
		"importedCount": count,
	})
	log.Printf("Successfully imported/backfilled %d listings.", count)
}
