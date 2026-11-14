package com.realestate.api.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Default {@link RelevanceScorer} matching {@code packages/shared/src/scoring.ts}.
 */
public final class DefaultRelevanceScorer implements RelevanceScorer {

    public static final String REFERENCE_DATE_ISO = "2026-11-01T00:00:00.000Z";
    public static final double BUDGET_WEIGHT = 0.6;
    public static final double RECENCY_WEIGHT = 0.4;
    public static final int MAX_RECENCY_DAYS = 365;
    private static final double MS_PER_DAY = 86_400_000.0;

    @Override
    public double computeScore(int price, String listedDateIso, Double targetBudget) {
        return computeScore(price, listedDateIso, targetBudget, REFERENCE_DATE_ISO);
    }

    @Override
    public double computeScore(int price, String listedDateIso, Double targetBudget, String referenceDateIso) {
        String effectiveRef = referenceDateIso != null ? referenceDateIso : REFERENCE_DATE_ISO;
        double recency = computeRecencyScore(listedDateIso, effectiveRef);

        double raw;
        if (targetBudget != null) {
            double budgetProximity = computeBudgetProximityScore(price, targetBudget);
            raw = BUDGET_WEIGHT * budgetProximity + RECENCY_WEIGHT * recency;
        } else {
            raw = recency;
        }

        double clamped = clamp(raw, 0.0, 1.0);
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Computes budget proximity in {@code [0.0, 1.0]} as {@code clamp(1 - |price - targetBudget| / targetBudget, 0, 1)}.
     */
    public static double computeBudgetProximityScore(double price, double targetBudget) {
        if (!Double.isFinite(price) || !Double.isFinite(targetBudget) || targetBudget <= 0 || price < 0) {
            return 0.0;
        }
        double diff = Math.abs(price - targetBudget);
        return clamp(1.0 - diff / targetBudget, 0.0, 1.0);
    }

    /**
     * Computes recency score in {@code [0.0, 1.0]} using linear decay over {@link #MAX_RECENCY_DAYS}.
     */
    public static double computeRecencyScore(String listedDateIso, String referenceDateIso) {
        Long refMs = parseIsoMillis(referenceDateIso != null ? referenceDateIso : REFERENCE_DATE_ISO);
        Long listedMs = parseIsoMillis(listedDateIso);
        if (refMs == null || listedMs == null) {
            return 0.0;
        }
        double ageDays = Math.max(0.0, (refMs - listedMs) / MS_PER_DAY);
        return clamp(1.0 - ageDays / MAX_RECENCY_DAYS, 0.0, 1.0);
    }

    private static Long parseIsoMillis(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.trim()).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
