package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
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

func resolveListenAddr() string {
	port := 4000
	if pStr := os.Getenv("PORT"); pStr != "" {
		if p, err := strconv.Atoi(pStr); err == nil && p >= 0 {
			port = p
		}
	}
	return fmt.Sprintf(":%d", port)
}

func run(ctx context.Context, addr string, readyCh chan<- string) error {
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
			return fmt.Errorf("failed to initialize Postgres repository: %w", err)
		}
		repo = pgRepo
	} else {
		repo = repository.NewMemoryRepository()
	}
	defer func() {
		_ = repo.Close()
	}()

	if err := repository.SeedDatabase(ctx, repo); err != nil {
		return fmt.Errorf("failed to seed database: %w", err)
	}

	searchService := search.NewService(repo)
	schema, err := gql.BuildSchema(searchService)
	if err != nil {
		return fmt.Errorf("failed to build GraphQL schema: %w", err)
	}

	handler := gql.NewHandler(schema)

	mux := http.NewServeMux()
	mux.Handle("/graphql", handler)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("OK"))
	})

	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to bind listener on %s: %w", addr, err)
	}

	boundPort := 0
	if tcpAddr, ok := ln.Addr().(*net.TCPAddr); ok {
		boundPort = tcpAddr.Port
	}

	server := &http.Server{
		Addr:              ln.Addr().String(),
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	telemetry.LogApi(ctx, telemetry.SeverityInfo, "INFO", "server.started", map[string]any{
		"server.port": boundPort,
		"url.full":    fmt.Sprintf("http://localhost:%d/graphql", boundPort),
	})

	if readyCh != nil {
		readyCh <- ln.Addr().String()
	}

	serveErrCh := make(chan error, 1)
	go func() {
		if err := server.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serveErrCh <- err
			return
		}
		serveErrCh <- nil
	}()

	select {
	case err := <-serveErrCh:
		return err
	case <-ctx.Done():
		telemetry.LogApi(context.Background(), telemetry.SeverityInfo, "INFO", "server.stopping", nil)
		shutdownCtx, sCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer sCancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			return fmt.Errorf("server shutdown error: %w", err)
		}
		return <-serveErrCh
	}
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := run(ctx, resolveListenAddr(), nil); err != nil {
		log.Fatalf("%v", err)
	}
}
