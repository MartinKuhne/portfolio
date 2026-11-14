# Real Estate Search Engine & Web Application

A full-stack, production-grade real estate search application featuring a reactive web interface, a type-safe GraphQL Yoga search engine with PostgreSQL persistence, deterministic ranking algorithms, and comprehensive end-to-end browser verification.

---

## Table of Contents

1. [Architecture & Workspace Structure](#architecture--workspace-structure)
2. [Relevance Scoring & Ranking Algorithm (REQ-RNK-011)](#relevance-scoring--ranking-algorithm-req-rnk-011)
   - [Overview & Formula](#overview--formula)
   - [Budget Proximity Score](#budget-proximity-score)
   - [Recency Score](#recency-score)
   - [Clamping, Rounding, & Deterministic Tie-Breakers](#clamping-rounding--deterministic-tie-breakers)
3. [Prerequisites](#prerequisites)
4. [Getting Started (Local Development)](#getting-started-local-development)
5. [Running Tests & Coverage](#running-tests--coverage)
   - [Unit & Integration Tests](#unit--integration-tests)
   - [Coverage Reporting](#coverage-reporting)
   - [End-to-End (E2E) Browser Tests with Playwright](#end-to-end-e2e-browser-tests-with-playwright)
6. [Containerization & Docker Compose](#containerization--docker-compose)
7. [GraphQL API Reference](#graphql-api-reference)

---

## Architecture & Workspace Structure

The repository is organized as an npm workspaces monorepo:

- **`packages/shared`**: Shared domain types, authoritative Zod validation schemas (`validateSearchInput`), and deterministic relevance scoring functions.
- **`packages/api`**: GraphQL Yoga server, Drizzle ORM schemas, PostgreSQL database client (with in-memory PGlite fallback for lightweight zero-dependency local runs and tests), and search execution pipeline.
- **`packages/ui`**: React 18 single-page application styled with Material UI (MUI), featuring responsive layout, client-side input validation, loading spinners, stale response discard via request tokens and `AbortController`, and accessible pagination.
- **`e2e`**: Playwright test suite validating real browser user journeys, happy paths, empty states, input validation errors, and network failure modes against live API and UI servers.

---

## Relevance Scoring & Ranking Algorithm (REQ-RNK-011)

In compliance with **REQ-RNK-011**, this section documents the exact relevance scoring formula, component weights, reference parameters, normalization constraints, and deterministic ordering rules.

### Overview & Formula

Each listing matching the active filters is assigned a numeric relevance score between `0.00` and `1.00`. The calculation depends on whether an optional `targetBudget` parameter is provided:

1. **When `targetBudget` is provided:**
   $$\text{relevanceScore} = \text{round}\left(\text{clamp}(0.60 \times \text{budgetProximity} + 0.40 \times \text{recency}, 0.00, 1.00), 2\right)$$
   - **Budget Proximity Weight:** `0.60` (`BUDGET_WEIGHT`)
   - **Recency Weight:** `0.40` (`RECENCY_WEIGHT`)

2. **When `targetBudget` is omitted:**
   $$\text{relevanceScore} = \text{round}\left(\text{clamp}(\text{recency}, 0.00, 1.00), 2\right)$$
   - Relevance is determined solely by the listing's recency relative to the reference date.

### Budget Proximity Score

The budget proximity score measures the closeness of the listing price to the user's target budget:

$$\text{budgetProximity} = \text{clamp}\left(1 - \frac{|\text{price} - \text{targetBudget}|}{\text{targetBudget}}, 0.00, 1.00\right)$$

- If `targetBudget <= 0`, `price < 0`, or either input is non-finite, the budget proximity score evaluates to `0.00`.
- A listing whose price exactly equals `targetBudget` achieves a budget proximity of `1.00`.
- As the absolute difference increases, the score decreases linearly, bounded at `0.00` when the deviation reaches or exceeds 100% of the target budget.

### Recency Score

The recency score evaluates how fresh a listing is relative to a fixed reference date:

$$\text{ageDays} = \max\left(0, \frac{\text{refMs} - \text{listedMs}}{86,400,000}\right)$$
$$\text{recency} = \text{clamp}\left(1 - \frac{\text{ageDays}}{\text{MAX\_RECENCY\_DAYS}}, 0.00, 1.00\right)$$

- **Fixed Reference Date (`REFERENCE_DATE_ISO`):** `2026-11-01T00:00:00.000Z`
- **Maximum Recency Span (`MAX_RECENCY_DAYS`):** `365` days (1 year)
- A listing listed on or after `2026-11-01T00:00:00.000Z` receives a recency score of `1.00`.
- A listing listed 365 or more days before the reference date receives a recency score of `0.00`.
- If date parsing produces non-finite timestamps, the recency score evaluates to `0.00`.

### Clamping, Rounding, & Deterministic Tie-Breakers

- **Bounding & Rounding:**
  - All intermediate and final component scores are clamped to `[0.00, 1.00]`.
  - The final relevance score is rounded to two decimal places: `Math.round(clamp(raw, 0, 1) * 100) / 100`.

- **Deterministic Sort Ordering:**
  When sorting matching listings, the search engine applies a 3-tier deterministic ordering hierarchy:
  1. **Primary:** `relevanceScore DESC` (higher scores appear first).
  2. **Secondary (Tie-breaker 1):** `listedDate DESC` (more recently listed properties appear first).
  3. **Tertiary (Tie-breaker 2):** `id ASC` (lexicographical comparison `a.id.localeCompare(b.id)` guarantees reproducible pagination across distributed queries).

---

## Prerequisites

- **Node.js**: `v20.x` or `v22.x` (v22 LTS recommended)
- **npm**: `v10.x` or later
- **Docker & Docker Compose**: Optional, for running containerized full-stack deployment.

---

## Getting Started (Local Development)

### 1. Install Dependencies

From the project root:

```bash
npm install
```

### 2. Build Workspaces

Build TypeScript outputs across all workspaces in dependency order:

```bash
npm run build
```

This sequentially builds `@real-estate/shared`, `@real-estate/api`, and `@real-estate/ui`.

### 3. Run Locally

You can run the API and UI independently or concurrently:

- **Run the API server:**
  ```bash
  npm run start -w @real-estate/api
  ```
  The GraphQL Yoga endpoint is served at: `http://localhost:4000/graphql` (automatically seeded with sample listings into an in-memory PGlite database if `DATABASE_URL` is unset).

- **Run the UI development server:**
  ```bash
  npm run dev -w @real-estate/ui -- --port 5173
  ```
  The Vite dev server opens at: `http://localhost:5173/` and proxies `/graphql` to `http://localhost:4000`.

- **Import sample listings (`sample_listings.json`):**
  ```bash
  npm run db:import
  ```
  Or from the API package directly:
  ```bash
  npm run db:import -w @real-estate/api
  ```
  This imports the provided 12-listing dataset (`sample_listings.json`), maps multi-part address fields, and inserts records idempotently (`ON CONFLICT DO NOTHING`). Inside Docker, `sample_listings.json` is packaged at `/app/sample_listings.json` and can be imported via `docker compose exec api npm run db:import`.

---

## Running Tests & Coverage

### Unit & Integration Tests

Run the full Vitest unit and integration test suite across all packages:

```bash
npm test
```

### Coverage Reporting

Run coverage verification across all monorepo packages:

```bash
npm run test:coverage
```

This executes per-package coverage verification:
- `@real-estate/shared`: 100% statement, branch, function, and line coverage.
- `@real-estate/api`: >94% branch, 100% statement, function, and line coverage.
- `@real-estate/ui`: 100% statement, branch, function, and line coverage.

### End-to-End (E2E) Browser Tests with Playwright

Playwright runs real Chromium browser tests against live API and UI servers launched automatically via `webServer`:

```bash
npm run test:e2e
```

Or directly:

```bash
npx playwright test
```

The E2E test suite validates:
1. **Initial Load Journey**: Page renders ranked listings, currency formatting (`$500,000`), relevance scores, and disabled Previous button on page 1.
2. **Multi-Filter & Budget Journey**: Simultaneous filtering by city (`Austin`), bedrooms (`3`), and budget (`500000`) computes live rankings.
3. **Pagination & Filter Reset Journey**: Page size adjustments, navigation to page 2, filter modification automatically resetting navigation to page 1.
4. **Empty State Journey**: Unmatched criteria displays user-friendly broadening guidance without crashing.
5. **Validation Error & Recovery**: Invalid bounds (`minPrice > maxPrice`) render alerts, clear stale listings, and recover on Reset Filters.
6. **Network Failure & Resiliency**: Intercepted GraphQL connection failures display error alerts, purge stale data, and recover seamlessly upon retry.

---

## Containerization & Docker Compose

The application includes production-ready Docker containers and an orchestrating Compose configuration.

### Services

- **`db`**: `postgres:16-alpine` database with automated healthcheck (`pg_isready -U realestate -d realestate`) on port `5432`.
- **`api`**: `node:22-alpine` container running the GraphQL Yoga server on port `4000`, connected to `db` via `DATABASE_URL`.
- **`ui`**: Multi-stage build (`node:22-alpine` builder + `nginx:alpine` runtime) serving the optimized static assets on port `8080` with Nginx reverse proxying `/graphql` to `http://api:4000/graphql`.

### Running with Docker Compose

To build and start the entire stack:

```bash
docker compose up --build
```

Access the services:
- **Web Application**: `http://localhost:8080/`
- **GraphQL Endpoint / GraphiQL**: `http://localhost:8080/graphql` or direct API at `http://localhost:4000/graphql`
- **PostgreSQL**: `localhost:5432` (`user: realestate`, `password: realestate`, `db: realestate`)

To validate Docker Compose configuration syntax without running the daemon:

```bash
docker compose config
```

---

## GraphQL API Reference

### Endpoint
`POST /graphql`

### Query: `searchListings`

```graphql
query SearchListings($input: SearchListingsInput) {
  searchListings(input: $input) {
    status
    listings {
      id
      address
      city
      price
      bedrooms
      description
      listedDate
      relevanceScore
    }
    pagination {
      page
      pageSize
      totalResults
      totalPages
    }
    error {
      code
      message
      field
    }
  }
}
```

#### Input Variables (`SearchListingsInput`)

| Field | Type | Description |
|---|---|---|
| `minPrice` | `JSONScalar` | Minimum listing price (must be $\ge 0$). |
| `maxPrice` | `JSONScalar` | Maximum listing price (must be $\ge 0$ and $\ge \text{minPrice}$). |
| `minBedrooms` | `JSONScalar` | Minimum bedrooms (must be non-negative integer). |
| `city` | `String` | Case-insensitive city filter. |
| `keyword` | `String` | Case-insensitive description substring filter. |
| `targetBudget`| `JSONScalar` | Desired budget for relevance ranking computation (must be $> 0$). |
| `page` | `JSONScalar` | 1-indexed page number (defaults to `1`). |
| `pageSize` | `JSONScalar` | Items per page (defaults to `10`, maximum `100`). |
