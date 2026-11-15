import { describe, expect, it } from 'vitest';
import {
  BUDGET_WEIGHT,
  computeBudgetProximityScore,
  computeRecencyScore,
  computeRelevanceScore,
  MAX_RECENCY_DAYS,
  RECENCY_WEIGHT,
  REFERENCE_DATE_ISO
} from '../scoring.js';
import * as sharedIndex from '../index.js';

describe('relevance scoring (REQ-RNK-001..007)', () => {
  it('exports expected scoring constants', () => {
    expect(REFERENCE_DATE_ISO).toBe('2026-11-01T00:00:00.000Z');
    expect(BUDGET_WEIGHT).toBe(0.6);
    expect(RECENCY_WEIGHT).toBe(0.4);
    expect(MAX_RECENCY_DAYS).toBe(365);
  });

  it('re-exports scoring functions and constants from index.ts', () => {
    expect(sharedIndex.REFERENCE_DATE_ISO).toBe(REFERENCE_DATE_ISO);
    expect(sharedIndex.BUDGET_WEIGHT).toBe(BUDGET_WEIGHT);
    expect(sharedIndex.RECENCY_WEIGHT).toBe(RECENCY_WEIGHT);
    expect(sharedIndex.MAX_RECENCY_DAYS).toBe(MAX_RECENCY_DAYS);
    expect(typeof sharedIndex.computeBudgetProximityScore).toBe('function');
    expect(typeof sharedIndex.computeRecencyScore).toBe('function');
    expect(typeof sharedIndex.computeRelevanceScore).toBe('function');
  });

  describe('computeBudgetProximityScore (REQ-RNK-004)', () => {
    it('assigns higher budget proximity score when price is closer in relative difference to targetBudget (REQ-RNK-004)', () => {
      const exact = computeBudgetProximityScore(500000, 500000);
      const close = computeBudgetProximityScore(450000, 500000);
      const far = computeBudgetProximityScore(250000, 500000);
      expect(exact).toBe(1);
      expect(close).toBeGreaterThan(far);
      expect(far).toBeGreaterThanOrEqual(0);
    });

    it('returns 0.5 when price is zero (diff equals target budget)', () => {
      expect(computeBudgetProximityScore(0, 500000)).toBe(0.5);
    });

    it('returns 0 when targetBudget is <= 0 or not finite', () => {
      expect(computeBudgetProximityScore(500000, 0)).toBe(0);
      expect(computeBudgetProximityScore(500000, -100000)).toBe(0);
      expect(computeBudgetProximityScore(500000, Number.NaN)).toBe(0);
      expect(computeBudgetProximityScore(500000, Number.POSITIVE_INFINITY)).toBe(0);
    });

    it('returns 0 when price is negative or not finite', () => {
      expect(computeBudgetProximityScore(-1000, 500000)).toBe(0);
      expect(computeBudgetProximityScore(Number.NaN, 500000)).toBe(0);
      expect(computeBudgetProximityScore(Number.POSITIVE_INFINITY, 500000)).toBe(0);
    });

    it('preserves strict monotonicity without clamping when price exceeds 2x targetBudget (REQ-RNK-004)', () => {
      const tripleBudget = computeBudgetProximityScore(1500000, 500000);
      const midOver = computeBudgetProximityScore(350000, 100000);
      const farOver = computeBudgetProximityScore(990000, 100000);

      expect(tripleBudget).toBeCloseTo(1 / 3, 10);
      expect(midOver).toBeCloseTo(1 / 3.5, 10);
      expect(farOver).toBeCloseTo(1 / 9.9, 10);
      expect(midOver).toBeGreaterThan(farOver);
    });
  });

  describe('computeRecencyScore (REQ-RNK-005)', () => {
    it('assigns higher recency score to more recent listedDate (REQ-RNK-005)', () => {
      const recent = computeRecencyScore('2026-10-25T00:00:00.000Z', '2026-11-01T00:00:00.000Z');
      const older = computeRecencyScore('2026-05-01T00:00:00.000Z', '2026-11-01T00:00:00.000Z');
      expect(recent).toBeGreaterThan(older);
    });

    it('uses default REFERENCE_DATE_ISO when referenceDateIso is omitted', () => {
      const scoreWithDefault = computeRecencyScore('2026-11-01T00:00:00.000Z');
      const scoreWithExplicit = computeRecencyScore('2026-11-01T00:00:00.000Z', REFERENCE_DATE_ISO);
      expect(scoreWithDefault).toBe(scoreWithExplicit);
      expect(scoreWithDefault).toBe(1);
    });

    it('returns 1 for future dates relative to reference date', () => {
      expect(computeRecencyScore('2027-01-01T00:00:00.000Z', '2026-11-01T00:00:00.000Z')).toBe(1);
    });

    it('returns 0 for dates older than MAX_RECENCY_DAYS', () => {
      expect(computeRecencyScore('2025-01-01T00:00:00.000Z', '2026-11-01T00:00:00.000Z')).toBe(0);
    });

    it('safely handles invalid date strings by returning 0', () => {
      expect(computeRecencyScore('invalid-date', '2026-11-01T00:00:00.000Z')).toBe(0);
      expect(computeRecencyScore('2026-11-01T00:00:00.000Z', 'invalid-ref-date')).toBe(0);
    });
  });

  describe('computeRelevanceScore (REQ-RNK-001, REQ-RNK-006, REQ-RNK-007)', () => {
    it('uses recency score alone when targetBudget is omitted (REQ-RNK-006)', () => {
      const score = computeRelevanceScore({
        price: 500000,
        listedDate: '2026-11-01T00:00:00.000Z',
        referenceDate: '2026-11-01T00:00:00.000Z'
      });
      expect(score).toBe(1);
    });

    it('combines budget proximity (0.6) and recency (0.4) when targetBudget is provided', () => {
      // Exact price match (proximity = 1), listed today (recency = 1) -> 0.6 * 1 + 0.4 * 1 = 1.00
      const perfectScore = computeRelevanceScore({
        price: 500000,
        listedDate: '2026-11-01T00:00:00.000Z',
        targetBudget: 500000,
        referenceDate: '2026-11-01T00:00:00.000Z'
      });
      expect(perfectScore).toBe(1);

      // Price off by 50% (proximity = 1 / 1.5 = 2/3), listed today (recency = 1) -> 0.6 * (2/3) + 0.4 * 1 = 0.80
      const mixedScore = computeRelevanceScore({
        price: 750000,
        listedDate: '2026-11-01T00:00:00.000Z',
        targetBudget: 500000,
        referenceDate: '2026-11-01T00:00:00.000Z'
      });
      expect(mixedScore).toBe(0.8);
    });

    it('bounds and rounds relevance scores to [0.00, 1.00] even for future dates or extreme prices (REQ-RNK-007)', () => {
      const futureScore = computeRelevanceScore({
        price: 500000,
        listedDate: '2027-01-01T00:00:00.000Z',
        targetBudget: 500000,
        referenceDate: '2026-11-01T00:00:00.000Z'
      });
      const extremeScore = computeRelevanceScore({
        price: 100000000,
        listedDate: '2020-01-01T00:00:00.000Z',
        targetBudget: 100000,
        referenceDate: '2026-11-01T00:00:00.000Z'
      });
      expect(futureScore).toBe(1);
      expect(extremeScore).toBe(0);
    });

    it('rounds relevance score to 2 decimal places', () => {
      // 100 days old out of 365: ageDays = 100. recency = 1 - 100/365 = 0.726027...
      // targetBudget omitted -> raw = recency
      // round(0.726027... * 100) / 100 = 0.73
      const score = computeRelevanceScore({
        price: 500000,
        listedDate: '2026-07-24T00:00:00.000Z', // 100 days prior to 2026-11-01
        referenceDate: '2026-11-01T00:00:00.000Z'
      });
      expect(score).toBe(0.73);
    });

    it('uses default reference date when referenceDate is omitted in params', () => {
      const score = computeRelevanceScore({
        price: 500000,
        listedDate: '2026-11-01T00:00:00.000Z',
        targetBudget: 500000
      });
      expect(score).toBe(1);
    });
  });
});
