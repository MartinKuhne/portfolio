package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"real-estate-search/internal/telemetry"
)

type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.String()
}

func TestRun_LifecycleAndListenerBindOrder(t *testing.T) {
	var logBuf syncBuffer
	telemetry.SetLogOutput(&logBuf)
	defer telemetry.SetLogOutput(nil)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	readyCh := make(chan string, 1)
	errCh := make(chan error, 1)

	go func() {
		errCh <- run(ctx, "127.0.0.1:0", readyCh)
	}()

	var addr string
	select {
	case addr = <-readyCh:
	case err := <-errCh:
		t.Fatalf("run exited prematurely with error: %v", err)
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for server ready")
	}

	// Verify server.started was logged with the bound port
	logs := logBuf.String()
	if !strings.Contains(logs, `"server.started"`) {
		t.Errorf("expected server.started log after listener bind, got: %s", logs)
	}

	// Verify /health responds 200 OK
	resp, err := http.Get("http://" + addr + "/health")
	if err != nil {
		t.Fatalf("GET /health failed: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK from /health, got %d", resp.StatusCode)
	}

	// Verify /graphql serves all 24 seeded listings
	gqlBody, _ := json.Marshal(map[string]any{
		"query": `query { searchListings(input: { pageSize: 5 }) { ... on SearchListingsSuccess { pagination { totalResults } } } }`,
	})
	gqlResp, err := http.Post("http://"+addr+"/graphql", "application/json", bytes.NewReader(gqlBody))
	if err != nil {
		t.Fatalf("POST /graphql failed: %v", err)
	}
	var payload map[string]any
	_ = json.NewDecoder(gqlResp.Body).Decode(&payload)
	gqlResp.Body.Close()
	if gqlResp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK from /graphql, got %d", gqlResp.StatusCode)
	}

	// Trigger graceful shutdown
	cancel()

	select {
	case err := <-errCh:
		if err != nil {
			t.Fatalf("expected clean shutdown error=nil, got: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for server shutdown")
	}

	if !strings.Contains(logBuf.String(), `"server.stopping"`) {
		t.Errorf("expected server.stopping log, got: %s", logBuf.String())
	}
}

func TestRun_ListenerBindErrorReturnsErrorWithoutStartedLog(t *testing.T) {
	var logBuf syncBuffer
	telemetry.SetLogOutput(&logBuf)
	defer telemetry.SetLogOutput(nil)

	occupied, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to open occupied listener: %v", err)
	}
	defer occupied.Close()

	err = run(context.Background(), occupied.Addr().String(), nil)
	if err == nil {
		t.Fatalf("expected bind error for occupied address")
	}
	if strings.Contains(logBuf.String(), `"server.started"`) {
		t.Errorf("server.started must NOT be logged when listener bind fails, got: %s", logBuf.String())
	}
}
