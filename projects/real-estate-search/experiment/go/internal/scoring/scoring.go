package scoring

import (
	"math"
	"time"

	"real-estate-search/internal/domain"
)

var isoLayouts = []string{
	time.RFC3339Nano,
	time.RFC3339,
	"2006-01-02T15:04:05.000Z",
	"2006-01-02T15:04:05Z",
	"2006-01-02",
}

// ParseISODate parses supported ISO-8601 date strings to UTC time.
func ParseISODate(iso string) (time.Time, bool) {
	for _, layout := range isoLayouts {
		if t, err := time.Parse(layout, iso); err == nil {
			return t.UTC(), true
		}
	}
	return time.Time{}, false
}

func clamp(value, min, max float64) float64 {
	return math.Min(math.Max(value, min), max)
}

// ComputeBudgetProximityScore computes strictly decreasing budget proximity in (0, 1].
func ComputeBudgetProximityScore(price, targetBudget float64) float64 {
	if math.IsNaN(price) || math.IsInf(price, 0) ||
		math.IsNaN(targetBudget) || math.IsInf(targetBudget, 0) ||
		targetBudget <= 0 || price < 0 {
		return 0.0
	}
	diff := math.Abs(price - targetBudget)
	return 1.0 / (1.0 + diff/targetBudget)
}

// ComputeRecencyScore computes recency score normalized to [0, 1].
func ComputeRecencyScore(listedDateIso, referenceDateIso string) float64 {
	refTime, okRef := ParseISODate(referenceDateIso)
	listedTime, okList := ParseISODate(listedDateIso)
	if !okRef || !okList {
		return 0.0
	}

	ageDays := math.Max(0.0, refTime.Sub(listedTime).Seconds()/86400.0)
	return clamp(1.0-ageDays/domain.MaxRecencyDays, 0.0, 1.0)
}

// ComputeRelevanceScore computes combined relevance score rounded to 2 decimal places [0.00, 1.00].
func ComputeRelevanceScore(price float64, listedDateIso string, targetBudget *float64, referenceDateIso *string) float64 {
	refDate := domain.ReferenceDateISO
	if referenceDateIso != nil && *referenceDateIso != "" {
		refDate = *referenceDateIso
	}

	recency := ComputeRecencyScore(listedDateIso, refDate)

	var raw float64
	if targetBudget != nil {
		budgetProximity := ComputeBudgetProximityScore(price, *targetBudget)
		raw = domain.BudgetWeight*budgetProximity + domain.RecencyWeight*recency
	} else {
		raw = recency
	}

	clamped := clamp(raw, 0.0, 1.0)
	return math.Round(clamped*100.0) / 100.0
}
