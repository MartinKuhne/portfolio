package com.realestate.api.scoring;

/**
 * Contract for deterministic listing relevance scoring.
 */
public interface RelevanceScorer {

    /**
     * Computes relevance score in {@code [0.00, 1.00]} using the default reference date.
     */
    double computeScore(int price, String listedDateIso, Double targetBudget);

    /**
     * Computes relevance score in {@code [0.00, 1.00]} against an explicit ISO-8601 reference date.
     */
    double computeScore(int price, String listedDateIso, Double targetBudget, String referenceDateIso);
}
