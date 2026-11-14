package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	gql "real-estate-search/internal/graphql"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/search"
	"real-estate-search/internal/telemetry"
)

func main() {
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
			log.Fatalf("Failed to initialize Postgres repository: %v", err)
		}
		repo = pgRepo
		defer repo.Close()
	} else {
		repo = repository.NewMemoryRepository()
	}

	if err := repository.SeedDatabase(ctx, repo); err != nil {
		log.Fatalf("Failed to seed database: %v", err)
	}

	searchService := search.NewService(repo)
	schema, err := gql.BuildSchema(searchService)
	if err != nil {
		log.Fatalf("Failed to build GraphQL schema: %v", err)
	}

	handler := gql.NewHandler(schema)

	mux := http.NewServeMux()
	mux.Handle("/graphql", handler)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	})

	port := 4000
	if pStr := os.Getenv("PORT"); pStr != "" {
		if p, err := strconv.Atoi(pStr); err == nil && p > 0 {
			port = p
		}
	}

	addr := fmt.Sprintf(":%d", port)
	server := &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	go func() {
		telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "server.started", map[string]any{
			"server.port": port,
			"url.full":    fmt.Sprintf("http://localhost:%d/graphql", port),
		})
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("HTTP server error: %v", err)
		}
	}()

	stopSignal := make(chan os.Signal, 1)
	signal.Notify(stopSignal, syscall.SIGINT, syscall.SIGTERM)
	<-stopSignal

	telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "server.stopping", nil)

	shutdownCtx, sCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer sCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("Server shutdown error: %v", err)
	}
}
