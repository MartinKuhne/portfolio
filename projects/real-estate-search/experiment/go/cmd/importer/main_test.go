package main

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"real-estate-search/internal/telemetry"
)

func TestImporterRun_SuccessAndErrorReturn(t *testing.T) {
	var logBuf bytes.Buffer
	telemetry.SetLogOutput(&logBuf)
	defer telemetry.SetLogOutput(nil)

	tmpDir := t.TempDir()
	samplePath := filepath.Join(tmpDir, "sample.json")
	content := `[{
		"id": "IMP-1",
		"address": "100 Main St",
		"city": "Austin",
		"price": 450000,
		"bedrooms": 3,
		"description": "Nice home",
		"listedDate": "2026-10-01T00:00:00.000Z"
	}]`
	if err := os.WriteFile(samplePath, []byte(content), 0644); err != nil {
		t.Fatalf("failed to write sample file: %v", err)
	}

	if err := run(context.Background(), []string{"-file", samplePath}); err != nil {
		t.Fatalf("expected clean import run, got error: %v", err)
	}

	if !strings.Contains(logBuf.String(), `"import.completed"`) || !strings.Contains(logBuf.String(), `"importedCount":1`) {
		t.Errorf("expected import.completed log with importedCount 1, got: %s", logBuf.String())
	}

	if err := run(context.Background(), []string{"-file", filepath.Join(tmpDir, "missing.json")}); err == nil {
		t.Errorf("expected error for missing sample file")
	}
}
