package scoring_test

import (
	"math"
	"testing"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/scoring"
)

func TestComputeBudgetProximityScore(t *testing.T) {
	// Exact match
	if s := scoring.ComputeBudgetProximityScore(500000, 500000); s != 1.0 {
		t.Errorf("expected 1.0 for exact budget match, got %f", s)
	}

	// 50% difference
	if s := scoring.ComputeBudgetProximityScore(250000, 500000); math.Abs(s-0.5) > 1e-6 {
		t.Errorf("expected 0.5 for 50%% difference, got %f", s)
	}

	// Out of range (diff >= targetBudget)
	if s := scoring.ComputeBudgetProximityScore(1200000, 500000); s != 0.0 {
		t.Errorf("expected 0.0 for price > 2x budget, got %f", s)
	}

	// Invalid inputs
	if s := scoring.ComputeBudgetProximityScore(-100, 500000); s != 0.0 {
		t.Errorf("expected 0.0 for negative price, got %f", s)
	}
	if s := scoring.ComputeBudgetProximityScore(500000, 0); s != 0.0 {
		t.Errorf("expected 0.0 for zero budget, got %f", s)
	}
	if s := scoring.ComputeBudgetProximityScore(math.NaN(), 500000); s != 0.0 {
		t.Errorf("expected 0.0 for NaN price, got %f", s)
	}
}

func TestComputeRecencyScore(t *testing.T) {
	ref := "2026-11-01T00:00:00.000Z"

	// Same day -> 1.0
	if s := scoring.ComputeRecencyScore("2026-11-01T00:00:00.000Z", ref); s != 1.0 {
		t.Errorf("expected 1.0 for same day, got %f", s)
	}

	// Date in future relative to reference -> clamp to 1.0
	if s := scoring.ComputeRecencyScore("2026-11-05T00:00:00.000Z", ref); s != 1.0 {
		t.Errorf("expected 1.0 for future date, got %f", s)
	}

	// 365 days ago -> 0.0
	if s := scoring.ComputeRecencyScore("2025-11-01T00:00:00.000Z", ref); math.Abs(s-0.0) > 1e-4 {
		t.Errorf("expected 0.0 for 365 days ago, got %f", s)
	}

	// More than 365 days ago -> clamped to 0.0
	if s := scoring.ComputeRecencyScore("2020-01-01T00:00:00.000Z", ref); s != 0.0 {
		t.Errorf("expected 0.0 for very old date, got %f", s)
	}

	// Invalid date format
	if s := scoring.ComputeRecencyScore("not-a-date", ref); s != 0.0 {
		t.Errorf("expected 0.0 for invalid date, got %f", s)
	}
}

func TestComputeRelevanceScore(t *testing.T) {
	ref := "2026-11-01T00:00:00.000Z"

	// 1. Without targetBudget: relevanceScore equals recency alone
	scoreNoBudget := scoring.ComputeRelevanceScore(500000, "2026-10-20T00:00:00.000Z", nil, &ref)
	expectedRecency := scoring.ComputeRecencyScore("2026-10-20T00:00:00.000Z", ref)
	if math.Abs(scoreNoBudget-math.Round(expectedRecency*100)/100) > 1e-6 {
		t.Errorf("expected score %f, got %f", math.Round(expectedRecency*100)/100, scoreNoBudget)
	}

	// 2. With targetBudget: weighted sum 0.60 * budget + 0.40 * recency
	budget := 500000.0
	scoreWithBudget := scoring.ComputeRelevanceScore(500000, "2026-11-01T00:00:00.000Z", &budget, &ref)
	// Price = 500k, budget = 500k -> proximity = 1.0
	// Listed = 2026-11-01 -> recency = 1.0
	// Raw = 0.6 * 1.0 + 0.4 * 1.0 = 1.00
	if scoreWithBudget != 1.0 {
		t.Errorf("expected 1.00, got %f", scoreWithBudget)
	}

	// Default reference date fallback
	scoreDefaultRef := scoring.ComputeRelevanceScore(500000, domain.ReferenceDateISO, &budget, nil)
	if scoreDefaultRef != 1.0 {
		t.Errorf("expected 1.00 with default reference date, got %f", scoreDefaultRef)
	}
}
