export const REFERENCE_DATE_ISO = '2026-11-01T00:00:00.000Z';
export const BUDGET_WEIGHT = 0.6;
export const RECENCY_WEIGHT = 0.4;
export const MAX_RECENCY_DAYS = 365;

const MS_PER_DAY = 86_400_000;

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

export function computeBudgetProximityScore(price: number, targetBudget: number): number {
  if (!Number.isFinite(price) || !Number.isFinite(targetBudget) || targetBudget <= 0 || price < 0) {
    return 0;
  }
  const diff = Math.abs(price - targetBudget);
  return 1 / (1 + diff / targetBudget);
}

export function computeRecencyScore(
  listedDateIso: string,
  referenceDateIso: string = REFERENCE_DATE_ISO
): number {
  const refMs = Date.parse(referenceDateIso);
  const listedMs = Date.parse(listedDateIso);

  if (!Number.isFinite(refMs) || !Number.isFinite(listedMs)) {
    return 0;
  }

  const ageDays = Math.max(0, (refMs - listedMs) / MS_PER_DAY);
  return clamp(1 - ageDays / MAX_RECENCY_DAYS, 0, 1);
}

export interface RelevanceScoreParams {
  price: number;
  listedDate: string;
  targetBudget?: number;
  referenceDate?: string;
}

export function computeRelevanceScore(params: RelevanceScoreParams): number {
  const recency = computeRecencyScore(params.listedDate, params.referenceDate ?? REFERENCE_DATE_ISO);

  let raw: number;
  if (params.targetBudget !== undefined) {
    const budgetProximity = computeBudgetProximityScore(params.price, params.targetBudget);
    raw = BUDGET_WEIGHT * budgetProximity + RECENCY_WEIGHT * recency;
  } else {
    raw = recency;
  }

  return Math.round(clamp(raw, 0, 1) * 100) / 100;
}
