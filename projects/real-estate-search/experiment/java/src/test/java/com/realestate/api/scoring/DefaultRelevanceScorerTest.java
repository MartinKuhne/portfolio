package com.realestate.api.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefaultRelevanceScorerTest {

    private final DefaultRelevanceScorer scorer = new DefaultRelevanceScorer();

    @Test
    void exposesExpectedScoringConstants() {
        assertThat(DefaultRelevanceScorer.REFERENCE_DATE_ISO).isEqualTo("2026-11-01T00:00:00.000Z");
        assertThat(DefaultRelevanceScorer.BUDGET_WEIGHT).isEqualTo(0.6);
        assertThat(DefaultRelevanceScorer.RECENCY_WEIGHT).isEqualTo(0.4);
        assertThat(DefaultRelevanceScorer.MAX_RECENCY_DAYS).isEqualTo(365);
    }

    @Test
    void computesBudgetProximityScoreAccurately() {
        double exact = DefaultRelevanceScorer.computeBudgetProximityScore(500_000, 500_000);
        double close = DefaultRelevanceScorer.computeBudgetProximityScore(450_000, 500_000);
        double far = DefaultRelevanceScorer.computeBudgetProximityScore(250_000, 500_000);

        assertThat(exact).isEqualTo(1.0);
        assertThat(close).isGreaterThan(far);
        assertThat(far).isGreaterThanOrEqualTo(0.0);

        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(0, 500_000)).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(1_500_000, 500_000)).isEqualTo(0.0);
    }

    @Test
    void returnsZeroBudgetProximityForInvalidOrNonFiniteInputs() {
        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(500_000, 0)).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(500_000, -100_000)).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(500_000, Double.NaN)).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(500_000, Double.POSITIVE_INFINITY)).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(-1_000, 500_000)).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(Double.NaN, 500_000)).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeBudgetProximityScore(Double.POSITIVE_INFINITY, 500_000)).isEqualTo(0.0);
    }

    @Test
    void computesRecencyScoreWithLinearDecayAndClamping() {
        double recent = DefaultRelevanceScorer.computeRecencyScore(
            "2026-10-25T00:00:00.000Z",
            "2026-11-01T00:00:00.000Z"
        );
        double older = DefaultRelevanceScorer.computeRecencyScore(
            "2026-05-01T00:00:00.000Z",
            "2026-11-01T00:00:00.000Z"
        );
        assertThat(recent).isGreaterThan(older);

        assertThat(
            DefaultRelevanceScorer.computeRecencyScore("2026-11-01T00:00:00.000Z", null)
        ).isEqualTo(1.0);
        assertThat(
            DefaultRelevanceScorer.computeRecencyScore("2027-01-01T00:00:00.000Z", "2026-11-01T00:00:00.000Z")
        ).isEqualTo(1.0);
        assertThat(
            DefaultRelevanceScorer.computeRecencyScore("2025-01-01T00:00:00.000Z", "2026-11-01T00:00:00.000Z")
        ).isEqualTo(0.0);
        assertThat(
            DefaultRelevanceScorer.computeRecencyScore("2026-11-01T00:00:00+00:00", "2026-11-01T00:00:00.000Z")
        ).isEqualTo(1.0);
    }

    @Test
    void handlesInvalidAndBlankDatesSafely() {
        assertThat(DefaultRelevanceScorer.computeRecencyScore("invalid", "2026-11-01T00:00:00.000Z")).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeRecencyScore("2026-11-01T00:00:00.000Z", "invalid")).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeRecencyScore("", "2026-11-01T00:00:00.000Z")).isEqualTo(0.0);
        assertThat(DefaultRelevanceScorer.computeRecencyScore(null, "2026-11-01T00:00:00.000Z")).isEqualTo(0.0);
    }

    @Test
    void usesRecencyAloneWhenTargetBudgetIsOmitted() {
        double score = scorer.computeScore(500_000, "2026-11-01T00:00:00.000Z", null);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void combinesBudgetProximityAndRecencyWithHalfUpRounding() {
        double perfect = scorer.computeScore(
            500_000,
            "2026-11-01T00:00:00.000Z",
            500_000.0,
            "2026-11-01T00:00:00.000Z"
        );
        assertThat(perfect).isEqualTo(1.0);

        double mixed = scorer.computeScore(
            750_000,
            "2026-11-01T00:00:00.000Z",
            500_000.0,
            "2026-11-01T00:00:00.000Z"
        );
        assertThat(mixed).isEqualTo(0.7);

        // 100 days prior to 2026-11-01 -> 1 - 100/365 = 0.726027... -> 0.73
        double rounded = scorer.computeScore(
            500_000,
            "2026-07-24T00:00:00.000Z",
            null,
            "2026-11-01T00:00:00.000Z"
        );
        assertThat(rounded).isEqualTo(0.73);

        // Future date & extreme price clamped to [0.00, 1.00]
        assertThat(
            scorer.computeScore(500_000, "2027-01-01T00:00:00.000Z", 500_000.0)
        ).isEqualTo(1.0);
        assertThat(
            scorer.computeScore(500_000, "2026-11-01T00:00:00.000Z", 500_000.0, null)
        ).isEqualTo(1.0);
        assertThat(
            scorer.computeScore(5_000_000, "2020-01-01T00:00:00.000Z", 100_000.0)
        ).isEqualTo(0.0);
    }
}
