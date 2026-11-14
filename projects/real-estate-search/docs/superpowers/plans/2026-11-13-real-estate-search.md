# Real Estate Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a containerized full-stack Real Estate Search web application (React + TypeScript + MUI UI and Node.js + GraphQL + Drizzle ORM + PostgreSQL API) that satisfies all requirements in `SPEC.md` with `>90%` unit test coverage and full E2E user-journey coverage.

**Architecture:** An npm workspaces monorepo (`packages/shared`, `packages/api`, `packages/ui`) shares strict input validation and deterministic relevance-scoring logic between the React frontend and the GraphQL Yoga + Drizzle backend. The API executes filtering, relevance scoring, deterministic tie-breaking, and pagination against PostgreSQL (using PGlite in fast unit/integration tests and PostgreSQL 16 in Docker Compose), while the React UI manages filter state, page resets, `AbortController` cancellation of stale requests, and accessible Material Design states.

**Tech Stack:** TypeScript 5 (strict), React 18, Vite 6, MUI (`@mui/material`), Node.js 22, GraphQL Yoga, Drizzle ORM, PostgreSQL 16 / `@electric-sql/pglite`, Vitest + React Testing Library (`>90%` coverage threshold enforced), Playwright, Docker & Docker Compose.

**Spec:** [SPEC.md](file:///C:/Users/mkuhn/src/portfolio/projects/real-estate-search/SPEC.md)

## Global Constraints

- `REQ-ARCH-004`: WHEN the search engine receives identical inputs for an unchanged dataset, the search engine shall return identical outputs.
- `REQ-FLT-001`: Optional filter parameters are `minPrice`, `maxPrice`, `minBedrooms`, `city`, and `keyword`.
- `REQ-RNK-007`: Relevance scores are normalized and bounded to `[0.00, 1.00]` (rounded to 2 decimal places).
- `REQ-RNK-009` & `REQ-RNK-010`: Tie-breaking orders by `relevanceScore DESC`, then `listedDate DESC`, then `id ASC`.
- `REQ-PAG-002` & `REQ-PAG-003`: Default `page` is `1` (1-indexed); default `pageSize` is `10`; maximum configured `pageSize` is `100` (`REQ-VAL-010`).
- `REQ-VAL-003`: Invalid input parameters must be rejected without coercing or ignoring the invalid value (e.g., `"100abc"`, `NaN`, `Infinity`, booleans, or fractional bedrooms/pages must fail validation).
- `REQ-ERR-005`: Structured error objects must contain `{ code: SearchErrorCode, message: string, field: string | null }`.
- Non-functional: Vitest coverage configuration in `packages/shared`, `packages/api`, and `packages/ui` must enforce `lines: 90, functions: 90, statements: 90, branches: 90`.

## Review Focus

- Partial numeric strings, non-finite numbers, or booleans (e.g., `"100abc"`, `NaN`, `Infinity`, `true`) passed as numeric inputs must be rejected as `VALIDATION_ERROR` for that field rather than coerced via `parseInt`/`Number` (tested in Task 1).
- Future `listedDate` values after the reference date or `price` values exceeding `2 * targetBudget` must be clamped so component sub-scores and final `relevanceScore` never fall outside `[0.00, 1.00]` (tested in Task 2).
- Literal SQL wildcard characters (`%`, `_`, `\`) inside `city` or `keyword` must be escaped before `ILIKE` matching so searching `"100%"` matches literal percent signs instead of all descriptions (tested in Task 3).
- Searches matching zero listings (`totalResults = 0`, `totalPages = 0`) with `page = 1` must return an empty result list (`listings: []`) rather than triggering the `OUT_OF_RANGE` error in `REQ-VAL-012` (tested in Task 3).
- Late responses or errors from an superseded in-flight search request must be ignored and must not overwrite newer search results or clear the active loading state prematurely (tested in Task 5).

---

### Task 1: Workspace Scaffold, Shared Types, and Strict Input Validation

**Files:**
- Create: `package.json`
- Create: `tsconfig.base.json`
- Create: `packages/shared/package.json`
- Create: `packages/shared/tsconfig.json`
- Create: `packages/shared/vitest.config.ts`
- Create: `packages/shared/src/types.ts`
- Create: `packages/shared/src/validation.ts`
- Create: `packages/shared/src/index.ts`
- Test: `packages/shared/src/__tests__/validation.test.ts`

**Interfaces:**
- Consumes: none
- Produces (`packages/shared/src/index.ts`):
  - `export type SearchErrorCode = 'VALIDATION_ERROR' | 'OUT_OF_RANGE' | 'INTERNAL_ERROR'`
  - `export interface SearchError { code: SearchErrorCode; message: string; field: string | null; totalPages?: number }`
  - `export interface RawSearchInput { minPrice?: unknown; maxPrice?: unknown; minBedrooms?: unknown; city?: unknown; keyword?: unknown; targetBudget?: unknown; page?: unknown; pageSize?: unknown }`
  - `export interface ValidatedSearchInput { minPrice?: number; maxPrice?: number; minBedrooms?: number; city?: string; keyword?: string; targetBudget?: number; page: number; pageSize: number }`
  - `export interface Listing { id: string; address: string; city: string; price: number; bedrooms: number; description: string; listedDate: string; relevanceScore: number }`
  - `export interface PaginationMeta { page: number; pageSize: number; totalResults: number; totalPages: number }`
  - `export interface SearchSuccessPayload { ok: true; listings: Listing[]; pagination: PaginationMeta; error: null }`
  - `export interface SearchErrorPayload { ok: false; listings: Listing[]; pagination: PaginationMeta | null; error: SearchError }`
  - `export type SearchResponse = SearchSuccessPayload | SearchErrorPayload`
  - `export const DEFAULT_PAGE = 1`
  - `export const DEFAULT_PAGE_SIZE = 10`
  - `export const MAX_PAGE_SIZE = 100`
  - `export function validateSearchInput(raw: RawSearchInput): { ok: true; value: ValidatedSearchInput } | { ok: false; error: SearchError }`

- [ ] **Step 1: Scaffold root workspace and `packages/shared` config files**

Create root `package.json` with `"workspaces": ["packages/*"]` and devDependencies (`typescript`, `vitest`, `@vitest/coverage-v8`). Create `tsconfig.base.json` with `"strict": true` and `"moduleResolution": "bundler"`. Create `packages/shared/package.json` (`"name": "@real-estate/shared"`) and `packages/shared/vitest.config.ts` enforcing 90% coverage thresholds. Run `npm install`.

- [ ] **Step 2: Write the failing validation tests in `packages/shared/src/__tests__/validation.test.ts`**

```typescript
import { describe, expect, it } from 'vitest';
import { validateSearchInput } from '../validation.js';

describe('validateSearchInput', () => {
  it('applies defaults page=1 and pageSize=10 when omitted (REQ-PAG-002, REQ-PAG-003)', () => {
    const res = validateSearchInput({});
    expect(res).toEqual({ ok: true, value: { page: 1, pageSize: 10 } });
  });

  it('trims city and keyword and drops whitespace-only strings (REQ-FLT-005, REQ-FLT-009)', () => {
    const res = validateSearchInput({ city: '  Austin ', keyword: '   ', minPrice: '' });
    expect(res).toEqual({ ok: true, value: { city: 'Austin', page: 1, pageSize: 10 } });
  });

  it('rejects non-numeric, partial numeric strings, NaN, Infinity, or negative minPrice/maxPrice without coercion (REQ-VAL-003, REQ-VAL-004, REQ-VAL-005)', () => {
    for (const bad of ['abc', '100abc', -1, NaN, Infinity, true]) {
      const minRes = validateSearchInput({ minPrice: bad });
      expect(minRes.ok).toBe(false);
      if (!minRes.ok) expect(minRes.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'minPrice' });

      const maxRes = validateSearchInput({ maxPrice: bad });
      expect(maxRes.ok).toBe(false);
      if (!maxRes.ok) expect(maxRes.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'maxPrice' });
    }
  });

  it('rejects minPrice > maxPrice (REQ-VAL-006)', () => {
    const res = validateSearchInput({ minPrice: 500000, maxPrice: 400000 });
    expect(res).toEqual({
      ok: false,
      error: {
        code: 'VALIDATION_ERROR',
        field: 'minPrice',
        message: 'minPrice must not exceed maxPrice.'
      }
    });
  });

  it('rejects non-integer or negative minBedrooms (REQ-VAL-007)', () => {
    for (const bad of [-1, 2.5, 'two', '2beds']) {
      const res = validateSearchInput({ minBedrooms: bad });
      expect(res.ok).toBe(false);
      if (!res.ok) expect(res.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'minBedrooms' });
    }
  });

  it('rejects targetBudget <= 0 or non-numeric (REQ-VAL-008)', () => {
    for (const bad of [0, -100, 'abc']) {
      const res = validateSearchInput({ targetBudget: bad });
      expect(res.ok).toBe(false);
      if (!res.ok) expect(res.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'targetBudget' });
    }
  });

  it('rejects pageSize <= 0, non-integer, or > MAX_PAGE_SIZE (REQ-VAL-009, REQ-VAL-010)', () => {
    for (const bad of [0, -5, 1.5, 101, 'ten']) {
      const res = validateSearchInput({ pageSize: bad });
      expect(res.ok).toBe(false);
      if (!res.ok) expect(res.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'pageSize' });
    }
  });

  it('rejects page < 1 or non-integer (REQ-VAL-011)', () => {
    for (const bad of [0, -1, 1.2, 'first']) {
      const res = validateSearchInput({ page: bad });
      expect(res.ok).toBe(false);
      if (!res.ok) expect(res.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'page' });
    }
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `npx vitest run --dir packages/shared`
Expected: FAIL with module `../validation.js` not found.

- [ ] **Step 4: Implement `packages/shared/src/types.ts`, `packages/shared/src/validation.ts`, and `packages/shared/src/index.ts`**

In `validation.ts`, accept `undefined | null | ''` (or whitespace-only strings) as omitted optional inputs (`REQ-FLT-009`). For numeric fields, accept finite `number` or trimmed string matching `/^-?\d+(\.\d+)?$/` (no partial strings like `"100abc"` or booleans) before range/integer checks. Validate in deterministic field order: `minPrice`, `maxPrice`, `minPrice <= maxPrice`, `minBedrooms`, `targetBudget`, `pageSize`, `page`, then trim `city` and `keyword`.

- [ ] **Step 5: Run tests with coverage to verify they pass**

Run: `npx vitest run --coverage --dir packages/shared`
Expected: PASS with `>90%` coverage across all metrics.

- [ ] **Step 6: Commit**

```bash
git add package.json package-lock.json tsconfig.base.json packages/shared
git commit -m "feat(shared): add shared domain types and strict search input validation"
```

---

### Task 2: Deterministic Relevance Scoring Formula in `@real-estate/shared`

**Files:**
- Create: `packages/shared/src/scoring.ts`
- Modify: `packages/shared/src/index.ts`
- Test: `packages/shared/src/__tests__/scoring.test.ts`

**Interfaces:**
- Consumes: none
- Produces (`packages/shared/src/scoring.ts`, re-exported from `packages/shared/src/index.ts`):
  - `export const REFERENCE_DATE_ISO = '2026-11-01T00:00:00.000Z'`
  - `export const BUDGET_WEIGHT = 0.6`
  - `export const RECENCY_WEIGHT = 0.4`
  - `export const MAX_RECENCY_DAYS = 365`
  - `export function computeBudgetProximityScore(price: number, targetBudget: number): number`
  - `export function computeRecencyScore(listedDateIso: string, referenceDateIso?: string): number`
  - `export function computeRelevanceScore(params: { price: number; listedDate: string; targetBudget?: number; referenceDate?: string }): number`

- [ ] **Step 1: Write the failing scoring tests in `packages/shared/src/__tests__/scoring.test.ts`**

```typescript
import { describe, expect, it } from 'vitest';
import {
  computeBudgetProximityScore,
  computeRecencyScore,
  computeRelevanceScore
} from '../scoring.js';

describe('relevance scoring (REQ-RNK-001..007)', () => {
  it('assigns higher budget proximity score when price is closer in relative difference to targetBudget (REQ-RNK-004)', () => {
    const exact = computeBudgetProximityScore(500000, 500000);
    const close = computeBudgetProximityScore(450000, 500000);
    const far = computeBudgetProximityScore(250000, 500000);
    expect(exact).toBe(1);
    expect(close).toBeGreaterThan(far);
    expect(far).toBeGreaterThanOrEqual(0);
  });

  it('assigns higher recency score to more recent listedDate (REQ-RNK-005)', () => {
    const recent = computeRecencyScore('2026-10-25T00:00:00.000Z', '2026-11-01T00:00:00.000Z');
    const older = computeRecencyScore('2026-05-01T00:00:00.000Z', '2026-11-01T00:00:00.000Z');
    expect(recent).toBeGreaterThan(older);
  });

  it('uses recency score alone when targetBudget is omitted (REQ-RNK-006)', () => {
    const score = computeRelevanceScore({
      price: 500000,
      listedDate: '2026-11-01T00:00:00.000Z',
      referenceDate: '2026-11-01T00:00:00.000Z'
    });
    expect(score).toBe(1);
  });

  it('bounds and rounds relevance scores to [0.00, 1.00] even for future dates or extreme prices (REQ-RNK-007)', () => {
    const futureScore = computeRelevanceScore({
      price: 500000,
      listedDate: '2027-01-01T00:00:00.000Z',
      targetBudget: 500000,
      referenceDate: '2026-11-01T00:00:00.000Z'
    });
    const extremeScore = computeRelevanceScore({
      price: 5000000,
      listedDate: '2020-01-01T00:00:00.000Z',
      targetBudget: 100000,
      referenceDate: '2026-11-01T00:00:00.000Z'
    });
    expect(futureScore).toBe(1);
    expect(extremeScore).toBe(0);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run --dir packages/shared`
Expected: FAIL with `../scoring.js` not found.

- [ ] **Step 3: Implement `packages/shared/src/scoring.ts` and export from `packages/shared/src/index.ts`**

Use the exact formula:
- `budgetProximity = clamp(1 - Math.abs(price - targetBudget) / targetBudget, 0, 1)`
- `ageDays = Math.max(0, (Date.parse(referenceDate) - Date.parse(listedDate)) / 86_400_000)`
- `recency = clamp(1 - ageDays / MAX_RECENCY_DAYS, 0, 1)`
- Raw total: `targetBudget !== undefined ? 0.6 * budgetProximity + 0.4 * recency : recency`
- Final `relevanceScore`: `Math.round(clamp(raw, 0, 1) * 100) / 100`

- [ ] **Step 4: Run tests with coverage to verify they pass**

Run: `npx vitest run --coverage --dir packages/shared`
Expected: PASS with `>90%` coverage.

- [ ] **Step 5: Commit**

```bash
git add packages/shared
git commit -m "feat(shared): implement deterministic relevance scoring formula"
```

---

### Task 3: Drizzle Schema, Deterministic Seed Dataset, and Search Engine Domain Service

**Files:**
- Create: `packages/api/package.json`
- Create: `packages/api/tsconfig.json`
- Create: `packages/api/vitest.config.ts`
- Create: `packages/api/src/db/schema.ts`
- Create: `packages/api/src/db/client.ts`
- Create: `packages/api/src/db/seed.ts`
- Create: `packages/api/src/search/executeSearch.ts`
- Test: `packages/api/src/__tests__/executeSearch.test.ts`

**Interfaces:**
- Consumes: `validateSearchInput`, `computeRelevanceScore`, `RawSearchInput`, `SearchResponse`, `REFERENCE_DATE_ISO` from `@real-estate/shared`
- Produces:
  - `packages/api/src/db/schema.ts`: `export const listingsTable = pgTable('listings', { id: text('id').primaryKey(), address: text('address').notNull(), city: text('city').notNull(), price: integer('price').notNull(), bedrooms: integer('bedrooms').notNull(), description: text('description').notNull(), listedDate: text('listed_date').notNull() })`
  - `packages/api/src/db/client.ts`: `export type AppDatabase = PgliteDatabase<typeof schema> | NodePgDatabase<typeof schema>`, `export async function createTestDatabase(): Promise<AppDatabase>`
  - `packages/api/src/db/seed.ts`: `export const SEED_LISTINGS: Array<typeof listingsTable.$inferInsert>`, `export async function seedDatabase(db: AppDatabase): Promise<void>`
  - `packages/api/src/search/executeSearch.ts`: `export async function executeSearch(db: AppDatabase, rawInput: RawSearchInput): Promise<SearchResponse>`

- [ ] **Step 1: Scaffold `packages/api` and install dependencies**

Create `packages/api/package.json` with dependencies `@real-estate/shared`, `drizzle-orm`, `pg`, `@electric-sql/pglite`, `graphql`, `graphql-yoga`, and devDependencies `@types/pg`, `vitest`, `@vitest/coverage-v8`. Run `npm install`.

- [ ] **Step 2: Write the failing search engine tests in `packages/api/src/__tests__/executeSearch.test.ts`**

```typescript
import { beforeAll, describe, expect, it } from 'vitest';
import { createTestDatabase, type AppDatabase } from '../db/client.js';
import { seedDatabase } from '../db/seed.js';
import { executeSearch } from '../search/executeSearch.js';

describe('executeSearch domain service', () => {
  let db: AppDatabase;

  beforeAll(async () => {
    db = await createTestDatabase();
    await seedDatabase(db);
  });

  it('produces identical deterministic outputs for identical inputs (REQ-ARCH-004)', async () => {
    const first = await executeSearch(db, { city: 'Austin', targetBudget: 500000 });
    const second = await executeSearch(db, { city: 'Austin', targetBudget: 500000 });
    expect(first).toEqual(second);
  });

  it('filters by minPrice, maxPrice, minBedrooms, case-insensitive city, and case-insensitive keyword with AND semantics (REQ-FLT-002..008)', async () => {
    const res = await executeSearch(db, {
      minPrice: 400000,
      maxPrice: 650000,
      minBedrooms: 3,
      city: '  austin ',
      keyword: '  POOL '
    });
    expect(res.ok).toBe(true);
    expect(res.listings.length).toBeGreaterThan(0);
    for (const item of res.listings) {
      expect(item.price).toBeGreaterThanOrEqual(400000);
      expect(item.price).toBeLessThanOrEqual(650000);
      expect(item.bedrooms).toBeGreaterThanOrEqual(3);
      expect(item.city.toLowerCase()).toBe('austin');
      expect(item.description.toLowerCase()).toContain('pool');
    }
  });

  it('escapes SQL wildcard characters % and _ in keyword and city (Review Focus)', async () => {
    const res = await executeSearch(db, { keyword: '100%' });
    expect(res.ok).toBe(true);
    for (const item of res.listings) {
      expect(item.description).toContain('100%');
    }
  });

  it('sorts by relevanceScore DESC, then listedDate DESC, then id ASC on ties (REQ-RNK-008..010)', async () => {
    const res = await executeSearch(db, { city: 'Denver', targetBudget: 500000, pageSize: 20 });
    expect(res.ok).toBe(true);
    for (let i = 1; i < res.listings.length; i++) {
      const prev = res.listings[i - 1];
      const curr = res.listings[i];
      expect(prev.relevanceScore).toBeGreaterThanOrEqual(curr.relevanceScore);
      if (prev.relevanceScore === curr.relevanceScore) {
        if (prev.listedDate === curr.listedDate) {
          expect(prev.id.localeCompare(curr.id)).toBeLessThan(0);
        } else {
          expect(Date.parse(prev.listedDate)).toBeGreaterThan(Date.parse(curr.listedDate));
        }
      }
    }
  });

  it('slices results by page and pageSize and returns pagination metadata (REQ-PAG-004, REQ-PAG-005)', async () => {
    const p1 = await executeSearch(db, { page: 1, pageSize: 5 });
    const p2 = await executeSearch(db, { page: 2, pageSize: 5 });
    expect(p1.ok && p2.ok).toBe(true);
    expect(p1.listings).toHaveLength(5);
    expect(p1.pagination).toMatchObject({ page: 1, pageSize: 5 });
    expect(p1.listings[0].id).not.toBe(p2.listings[0].id);
  });

  it('returns empty listings without error when totalResults is 0 and page is 1 (Review Focus)', async () => {
    const res = await executeSearch(db, { city: 'NonExistentCity', page: 1 });
    expect(res).toEqual({
      ok: true,
      listings: [],
      pagination: { page: 1, pageSize: 10, totalResults: 0, totalPages: 0 },
      error: null
    });
  });

  it('returns OUT_OF_RANGE error with totalPages when totalResults > 0 and page > totalPages (REQ-VAL-012)', async () => {
    const res = await executeSearch(db, { city: 'Austin', page: 99, pageSize: 10 });
    expect(res.ok).toBe(false);
    expect(res.error?.code).toBe('OUT_OF_RANGE');
    expect(res.error?.totalPages).toBeGreaterThan(0);
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `npx vitest run --dir packages/api`
Expected: FAIL with module `../db/client.js` not found.

- [ ] **Step 4: Implement `schema.ts`, `client.ts`, `seed.ts`, and `executeSearch.ts`**

- In `seed.ts`, define `SEED_LISTINGS` with 24 deterministic listings across `Austin`, `Denver`, `Seattle`, and `Miami`, including two `Denver` listings at `$500,000` with different `listedDate` values (`REQ-RNK-009`), two `Denver` listings at `$500,000` with identical `listedDate` (`2026-10-15T00:00:00.000Z`) and IDs `LST-010` / `LST-011` (`REQ-RNK-010`), and one listing containing `"100% renovated"` in `description`.
- In `executeSearch.ts`, escape `\`, `%`, and `_` in `city` and `keyword` before building Drizzle `and(...)` filters (`gte`, `lte`, `ilike` with `ESCAPE '\\'`), compute `relevanceScore` in SQL (or via `computeRelevanceScore` on matched rows), sort by `relevanceScore DESC, listedDate DESC, id ASC`, enforce `REQ-VAL-012` when `totalResults > 0 && page > totalPages`, and catch unexpected database errors to return `{ ok: false, listings: [], pagination: null, error: { code: 'INTERNAL_ERROR', message: '...', field: null } }` (`REQ-ERR-006`).

- [ ] **Step 5: Run tests to verify they pass**

Run: `npx vitest run --dir packages/api`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add packages/api package-lock.json
git commit -m "feat(api): add Drizzle schema, deterministic seed dataset, and search engine service"
```

---

### Task 4: GraphQL API Server and Structured Error Handling

**Files:**
- Create: `packages/api/src/graphql/server.ts`
- Create: `packages/api/src/index.ts`
- Test: `packages/api/src/__tests__/graphql.test.ts`

**Interfaces:**
- Consumes: `executeSearch`, `AppDatabase`, `createTestDatabase`, `seedDatabase`
- Produces:
  - `packages/api/src/graphql/server.ts`: `export function createGraphqlApp(db: AppDatabase): YogaServerInstance<Record<string, unknown>, Record<string, unknown>>`
  - GraphQL Query:
    ```graphql
    input SearchListingsInput {
      minPrice: JSONScalar
      maxPrice: JSONScalar
      minBedrooms: JSONScalar
      city: String
      keyword: String
      targetBudget: JSONScalar
      page: JSONScalar
      pageSize: JSONScalar
    }
    ```
    Using a pass-through `JSONScalar` (or custom `AnyInput` scalar that passes raw JSON primitives without GraphQL parser coercion) ensures invalid values like `"100abc"` or `-5` reach `executeSearch` and return HTTP 400 / structured `SearchListingsPayload` with `{ ok: false, error: { code, message, field, totalPages } }` (`REQ-VAL-003`, `REQ-ERR-004`, `REQ-ERR-005`, `REQ-ERR-006`).

- [ ] **Step 1: Write the failing GraphQL integration tests in `packages/api/src/__tests__/graphql.test.ts`**

```typescript
import { beforeAll, describe, expect, it } from 'vitest';
import { createTestDatabase, type AppDatabase } from '../db/client.js';
import { seedDatabase } from '../db/seed.js';
import { createGraphqlApp } from '../graphql/server.js';

describe('GraphQL searchListings endpoint', () => {
  let yoga: ReturnType<typeof createGraphqlApp>;

  beforeAll(async () => {
    const db: AppDatabase = await createTestDatabase();
    await seedDatabase(db);
    yoga = createGraphqlApp(db);
  });

  it('returns matching listings and pagination metadata on valid query (REQ-ARCH-003, REQ-PAG-005)', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            listings { id address city price bedrooms description listedDate relevanceScore }
            pagination { page pageSize totalResults totalPages }
            error { code message field totalPages }
          }
        }`,
        variables: { input: { city: 'Austin', targetBudget: 500000, page: 1, pageSize: 5 } }
      })
    });
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.data.searchListings.ok).toBe(true);
    expect(body.data.searchListings.listings.length).toBeGreaterThan(0);
    expect(body.data.searchListings.pagination.page).toBe(1);
  });

  it('returns explicit 400 status and structured error object without crashing on invalid inputs (REQ-ERR-004..006)', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            error { code message field totalPages }
          }
        }`,
        variables: { input: { minPrice: 'not-a-number' } }
      })
    });
    expect(response.status).toBe(400);
    const body = await response.json();
    expect(body.data.searchListings).toEqual({
      ok: false,
      error: {
        code: 'VALIDATION_ERROR',
        message: expect.stringContaining('minPrice'),
        field: 'minPrice',
        totalPages: null
      }
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run --dir packages/api`
Expected: FAIL with `../graphql/server.js` not found.

- [ ] **Step 3: Implement `packages/api/src/graphql/server.ts` and `packages/api/src/index.ts`**

Implement `createGraphqlApp(db)` with GraphQL Yoga. When `executeSearch` returns `ok: false`, set the HTTP response status to `400` (for `VALIDATION_ERROR` / `OUT_OF_RANGE`) or `500` (for `INTERNAL_ERROR`) while returning the structured `SearchListingsPayload` so both HTTP status (`REQ-ERR-004`) and payload structure (`REQ-ERR-005`) are satisfied.

- [ ] **Step 4: Run API test suite with coverage**

Run: `npx vitest run --coverage --dir packages/api`
Expected: PASS with `>90%` coverage across lines, functions, branches, and statements.

- [ ] **Step 5: Commit**

```bash
git add packages/api
git commit -m "feat(api): add GraphQL Yoga server with structured error status and payload"
```

---

### Task 5: React + MUI User Interface, Stale-Response Protection, and Component Tests

**Files:**
- Create: `packages/ui/package.json`
- Create: `packages/ui/tsconfig.json`
- Create: `packages/ui/vite.config.ts`
- Create: `packages/ui/index.html`
- Create: `packages/ui/src/api/searchClient.ts`
- Create: `packages/ui/src/components/SearchForm.tsx`
- Create: `packages/ui/src/components/ListingResults.tsx`
- Create: `packages/ui/src/components/PaginationBar.tsx`
- Create: `packages/ui/src/App.tsx`
- Create: `packages/ui/src/main.tsx`
- Test: `packages/ui/src/__tests__/App.test.tsx`

**Interfaces:**
- Consumes: `validateSearchInput`, `RawSearchInput`, `SearchResponse`, `Listing`, `PaginationMeta` from `@real-estate/shared`
- Produces:
  - `packages/ui/src/api/searchClient.ts`: `export async function fetchSearchListings(rawInput: RawSearchInput, signal?: AbortSignal): Promise<SearchResponse>`
  - `packages/ui/src/App.tsx`: `export function App({ searchFn?: typeof fetchSearchListings }): JSX.Element`

- [ ] **Step 1: Scaffold `packages/ui` and install React, MUI, and Testing Library dependencies**

Create `packages/ui/package.json` with `react`, `react-dom`, `@mui/material`, `@emotion/react`, `@emotion/styled`, `@real-estate/shared`, and devDependencies `@vitejs/plugin-react`, `vite`, `vitest`, `@vitest/coverage-v8`, `jsdom`, `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`. Run `npm install`.

- [ ] **Step 2: Write the failing UI unit/integration tests in `packages/ui/src/__tests__/App.test.tsx`**

```typescript
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { SearchResponse } from '@real-estate/shared';
import { App } from '../App.js';

const samplePage1: SearchResponse = {
  ok: true,
  listings: [
    {
      id: 'LST-001',
      address: '101 Congress Ave, Austin, TX',
      city: 'Austin',
      price: 485000,
      bedrooms: 3,
      description: 'Modern downtown condo with pool',
      listedDate: '2026-10-20T00:00:00.000Z',
      relevanceScore: 0.94
    }
  ],
  pagination: { page: 1, pageSize: 10, totalResults: 15, totalPages: 2 },
  error: null
};

describe('Real Estate Search UI (REQ-UI-001..012, REQ-PAG-006..010, REQ-ERR-001..003)', () => {
  it('renders listings with currency price, bedrooms, and relevance score, plus pagination metadata (REQ-UI-003..005, REQ-UI-012)', async () => {
    const searchFn = vi.fn().mockResolvedValue(samplePage1);
    render(<App searchFn={searchFn} />);

    expect(await screen.findByText('101 Congress Ave, Austin, TX')).toBeInTheDocument();
    expect(screen.getByText('$485,000')).toBeInTheDocument();
    expect(screen.getByText(/3\s*bedrooms/i)).toBeInTheDocument();
    expect(screen.getByText(/0\.94/)).toBeInTheDocument();
    expect(screen.getByText(/Page 1 of 2 \(15 total listings\)/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /previous page/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next page/i })).toBeEnabled();
  });

  it('resets page to 1 when any filter, targetBudget, or pageSize changes after navigating to page 2 (REQ-PAG-006..008)', async () => {
    const user = userEvent.setup();
    const searchFn = vi.fn().mockImplementation(async (input) => ({
      ...samplePage1,
      pagination: { page: Number(input.page ?? 1), pageSize: 10, totalResults: 15, totalPages: 2 }
    }));
    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.click(screen.getByRole('button', { name: /next page/i }));
    await waitFor(() => expect(searchFn).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }), expect.any(AbortSignal)));

    await user.type(screen.getByLabelText(/city/i), 'Austin');
    await user.click(screen.getByRole('button', { name: /search/i }));
    await waitFor(() =>
      expect(searchFn).toHaveBeenLastCalledWith(
        expect.objectContaining({ city: 'Austin', page: 1 }),
        expect.any(AbortSignal)
      )
    );
  });

  it('displays loading indicator and discards stale out-of-order responses (REQ-UI-006, REQ-UI-007, Review Focus)', async () => {
    const user = userEvent.setup();
    let resolveSlow!: (value: SearchResponse) => void;
    const searchFn = vi
      .fn()
      .mockResolvedValueOnce(samplePage1)
      .mockImplementationOnce(
        () =>
          new Promise<SearchResponse>((r) => {
            resolveSlow = r;
          })
      )
      .mockResolvedValueOnce({
        ok: true,
        listings: [{ ...samplePage1.listings[0], id: 'LST-NEW', address: '999 Newer St, Seattle, WA' }],
        pagination: { page: 1, pageSize: 10, totalResults: 1, totalPages: 1 },
        error: null
      });

    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.type(screen.getByLabelText(/city/i), 'Denver');
    await user.click(screen.getByRole('button', { name: /search/i }));
    expect(screen.getByRole('progressbar')).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/city/i));
    await user.type(screen.getByLabelText(/city/i), 'Seattle');
    await user.click(screen.getByRole('button', { name: /search/i }));

    expect(await screen.findByText('999 Newer St, Seattle, WA')).toBeInTheDocument();

    resolveSlow({
      ok: true,
      listings: [{ ...samplePage1.listings[0], id: 'LST-STALE', address: '000 Stale Rd, Denver, CO' }],
      pagination: { page: 1, pageSize: 10, totalResults: 1, totalPages: 1 },
      error: null
    });

    await waitFor(() => expect(screen.queryByText('000 Stale Rd, Denver, CO')).not.toBeInTheDocument());
    expect(screen.getByText('999 Newer St, Seattle, WA')).toBeInTheDocument();
  });

  it('displays empty-results message and broadening guidance when 0 listings match (REQ-UI-008, REQ-UI-009)', async () => {
    const searchFn = vi.fn().mockResolvedValue({
      ok: true,
      listings: [],
      pagination: { page: 1, pageSize: 10, totalResults: 0, totalPages: 0 },
      error: null
    });
    render(<App searchFn={searchFn} />);
    expect(await screen.findByText(/no listings matched/i)).toBeInTheDocument();
    expect(screen.getByText(/broaden/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /next page/i })).toBeDisabled();
  });

  it('validates inputs, clears previous listings on error, and recovers when corrected or reset (REQ-VAL-001, REQ-ERR-001..003, REQ-UI-010..011)', async () => {
    const user = userEvent.setup();
    const searchFn = vi.fn().mockResolvedValue(samplePage1);
    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.type(screen.getByLabelText(/min price/i), '600000');
    await user.type(screen.getByLabelText(/max price/i), '400000');
    await user.click(screen.getByRole('button', { name: /search/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/minPrice must not exceed maxPrice/i);
    expect(screen.queryByText('101 Congress Ave, Austin, TX')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /reset filters/i }));
    expect(await screen.findByText('101 Congress Ave, Austin, TX')).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `npx vitest run --dir packages/ui`
Expected: FAIL with `../App.js` not found.

- [ ] **Step 4: Implement `searchClient.ts`, `SearchForm.tsx`, `ListingResults.tsx`, `PaginationBar.tsx`, `App.tsx`, and `main.tsx`**

- In `searchClient.ts`: run `validateSearchInput(rawInput)` before sending (`REQ-VAL-001`); if invalid, return `{ ok: false, listings: [], pagination: null, error: validationResult.error }` without making a network call. Otherwise `POST` to `/graphql` with `signal`.
- In `App.tsx`: maintain an incrementing `requestIdRef` and `AbortController` per request so only the latest request updates state (`REQ-UI-007`). Whenever filter fields, `targetBudget`, or `pageSize` change, reset `page` to `1` (`REQ-PAG-006`). On any validation or network error, set `listings = []`, `pagination = null`, and show an MUI `Alert` (`role="alert"`) (`REQ-ERR-001`..`003`).

- [ ] **Step 5: Run UI unit tests with coverage**

Run: `npx vitest run --coverage --dir packages/ui`
Expected: PASS with `>90%` coverage across all metrics.

- [ ] **Step 6: Commit**

```bash
git add packages/ui package-lock.json
git commit -m "feat(ui): implement React MUI search interface with validation, pagination, and stale-response cancellation"
```

---

### Task 6: Containerization, Documentation, and End-to-End Playwright Suite

**Files:**
- Create: `packages/api/Dockerfile`
- Create: `packages/ui/Dockerfile`
- Create: `packages/ui/nginx.conf`
- Create: `docker-compose.yml`
- Create: `playwright.config.ts`
- Create: `e2e/search.spec.ts`
- Create: `README.md`
- Modify: `package.json`

**Interfaces:**
- Consumes: `packages/api`, `packages/ui`, `packages/shared`
- Produces:
  - `README.md` documenting relevance scoring formula, weights (`0.6` budget proximity, `0.4` recency), and normalization rules (`REQ-RNK-011`)
  - `docker-compose.yml` orchestrating `db` (Postgres 16), `api` (Node 22 on port 4000), and `ui` (Nginx on port 8080 proxying `/graphql` to `api:4000`)
  - `e2e/search.spec.ts` verifying all user journeys, happy paths, and failure modes in a real browser

- [ ] **Step 1: Write the failing Playwright E2E test suite in `e2e/search.spec.ts` and `playwright.config.ts`**

```typescript
import { expect, test } from '@playwright/test';

test.describe('Real Estate Search E2E Journeys', () => {
  test('initial load displays ranked listings, currency prices, and pagination metadata', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /previous page/i })).toBeDisabled();
  });

  test('filtering by city, price range, bedrooms, keyword, and targetBudget updates ranked results', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel(/city/i).fill('Austin');
    await page.getByLabel(/min bedrooms/i).fill('3');
    await page.getByLabel(/target budget/i).fill('500000');
    await page.getByRole('button', { name: /search/i }).click();

    await expect(page.getByText(/Austin/i).first()).toBeVisible();
  });

  test('pagination navigation preserves filters and resets to page 1 on filter modification', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel(/page size/i).fill('5');
    await page.getByRole('button', { name: /search/i }).click();
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();

    await page.getByRole('button', { name: /next page/i }).click();
    await expect(page.getByText(/Page 2 of/i)).toBeVisible();

    await page.getByLabel(/city/i).fill('Denver');
    await page.getByRole('button', { name: /search/i }).click();
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();
  });

  test('empty results state shows broadening guidance without crashing', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel(/city/i).fill('Atlantis');
    await page.getByRole('button', { name: /search/i }).click();

    await expect(page.getByText(/no listings matched/i)).toBeVisible();
    await expect(page.getByText(/broaden/i)).toBeVisible();
  });

  test('validation error clears stale results and recovers on Reset Filters', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel(/min price/i).fill('800000');
    await page.getByLabel(/max price/i).fill('200000');
    await page.getByRole('button', { name: /search/i }).click();

    await expect(page.getByRole('alert')).toContainText(/minPrice must not exceed maxPrice/i);

    await page.getByRole('button', { name: /reset filters/i }).click();
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();
  });
});
```

- [ ] **Step 2: Configure `playwright.config.ts` and root scripts in `package.json`**

Configure `playwright.config.ts` with `webServer` launching the API and UI dev servers (or testing against `http://localhost:5173` with Vite proxy `/graphql -> http://localhost:4000/graphql`).

- [ ] **Step 3: Create `packages/api/Dockerfile`, `packages/ui/Dockerfile`, `packages/ui/nginx.conf`, `docker-compose.yml`, and `README.md`**

In `README.md`, document the relevance scoring formula, component weights (`0.60` budget proximity, `0.40` recency), reference date (`2026-11-01T00:00:00.000Z`), normalization rules (`[0.00, 1.00]`), tie-breakers (`listedDate DESC`, `id ASC`), and commands for unit tests, E2E tests, and `docker compose up --build` (`REQ-RNK-011`).

- [ ] **Step 4: Run full unit test coverage and Playwright E2E suite**

Run: `npm run test:coverage && npx playwright test`
Expected: PASS (`>90%` unit test coverage across all packages and 5/5 Playwright E2E user journeys passing).

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "feat: add Docker Compose setup, scoring documentation, and Playwright E2E suite"
```
