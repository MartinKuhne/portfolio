# Architecture Decision Records (ADR)

This document records the architectural decisions and implementation rulings made during the development of the Real Estate Search application against [SPEC.md](./SPEC.md).

---

## ADR-001: Drizzle ORM over Prisma

- **Status:** Accepted
- **Context:** The search engine must compute a runtime `relevanceScore` parameterized by `targetBudget`, filter across optional criteria, sort deterministically (`relevanceScore DESC, listedDate DESC, id ASC`), and paginate results (`REQ-RNK-001`–`010`, `REQ-PAG-001`–`005`), while running inside lightweight Docker containers and achieving `>90%` unit/integration test coverage.
- **Decision:** Use **Drizzle ORM** with `pg` (PostgreSQL 16) in production and `@electric-sql/pglite` (in-memory WASM PostgreSQL) for fast Vitest integration tests.
- **Consequences:**
  - Computed SQL expressions (`sql<number>`), `ORDER BY`, `LIMIT`, and `OFFSET` compose directly in Drizzle without dropping to unguarded raw SQL or pulling all rows into Node.js memory.
  - Pure TypeScript runtime requires no native query-engine binaries in `node:alpine` Docker images.
  - Trade-off: Schema DDL (`ensureSchema`) is managed explicitly in [client.ts](./packages/api/src/db/client.ts) rather than via `.prisma` migrations.

---

## ADR-002: Custom `JSONScalar` Pass-Through & GraphQL Yoga HTTP Status Mapping

- **Status:** Accepted
- **Context:** Standard GraphQL `Int`/`Float` input scalars reject or coerce malformed primitives at the GraphQL parser layer before resolvers execute. This conflicts with `REQ-VAL-003` (reject invalid inputs without coercing), `REQ-ERR-004` (explicit error status), and `REQ-ERR-005` (structured `{ code, message, field }` error payload). Additionally, `SearchListingsPayload.listings` is typed as `[Listing!]!`, which throws a GraphQL Non-Null violation if omitted on error responses.
- **Decision:**
  1. Define a pass-through `JSONScalar` in [server.ts](./packages/api/src/graphql/server.ts) for numeric/integer GraphQL input fields so raw values reach `validateSearchInput` uncoerced.
  2. Use GraphQL Yoga's `onResponse` hook to set HTTP status `400` on `VALIDATION_ERROR` / `OUT_OF_RANGE` and `500` on `INTERNAL_ERROR` while preserving the structured GraphQL JSON payload.
  3. Always default `listings` to `[]` and `pagination` to `null` in the resolver return object when an error occurs.
- **Consequences:**
  - Clients querying the full selection set (`ok`, `listings`, `pagination`, `error`) receive both HTTP 400/500 status codes and the structured field-level error object without GraphQL nullability errors.
  - Trade-off: Input schema types rely on `JSONScalar` documentation (`README.md`) and `validateSearchInput` rather than GraphQL built-in scalar rejection.

---

## ADR-003: Monorepo Packaging, Shared Domain Logic, and Custom Validation

- **Status:** Accepted
- **Context:** `SPEC.md` requires identical validation (`REQ-VAL-001`–`012`) and deterministic scoring (`REQ-RNK-001`–`010`) across UI and API, while running in Vite/Vitest (TypeScript sources) and Node.js 22 Docker containers (compiled JavaScript).
- **Decision:**
  1. Structure the project as an npm workspaces monorepo (`packages/shared`, `packages/api`, `packages/ui`).
  2. Compile `@real-estate/shared` to `dist/` via `tsc` (`main`/`types` pointing to `./dist/index.*`) for Node.js/Docker runtime, while aliasing `@real-estate/shared` to `../shared/src/index.ts` in Vite/Vitest configs for zero-lag local testing.
  3. Implement pure zero-dependency TypeScript validation (`validateSearchInput`) and scoring constants/helpers (`scoring.ts`) in `@real-estate/shared` instead of adding Zod or GraphQL Codegen.
- **Consequences:**
  - Single source of truth for validation rules, error codes, scoring weights, and domain types with 100% unit test coverage in `@real-estate/shared`.
  - Trade-off: `npm run build` must build `@real-estate/shared` before `@real-estate/api` and `@real-estate/ui` (enforced sequentially in root `package.json`).

---

## ADR-004: SQL-Side Relevance Scoring, Wildcard Escaping, and Locale-Independent Tie-Breaking

- **Status:** Accepted (supersedes an initial pre-flight decision to score filtered rows in Node.js memory)
- **Context:** Computing `relevanceScore` in JS after `SELECT *` avoids formula duplication, but loads all matching rows into memory on large datasets. In addition, `String.prototype.localeCompare` depends on host container locale (`REQ-ARCH-004`, `REQ-RNK-010`), and raw `ILIKE` treats `%` and `_` in user input as SQL wildcards.
- **Decision:**
  1. Import `BUDGET_WEIGHT`, `RECENCY_WEIGHT`, `MAX_RECENCY_DAYS`, and `REFERENCE_DATE_ISO` from `@real-estate/shared` directly into parameterized Drizzle SQL expressions in [executeSearch.ts](./packages/api/src/search/executeSearch.ts).
  2. Run a `COUNT(*)::int` query first (handling `totalResults === 0` and `OUT_OF_RANGE` early), then fetch only the requested page via `ORDER BY score DESC, listed_date::timestamptz DESC, id COLLATE "C" ASC LIMIT :pageSize OFFSET :offset`.
  3. Escape `\`, `%`, and `_` in `city` and `keyword` with `ESCAPE '\\'` before `ILIKE` matching.
- **Consequences:**
  - Formula constants remain single-sourced in `@real-estate/shared`, memory usage stays constant per page size, and tie-breaker ordering is locale-independent across hosts and containers.
  - Trade-off: Both [scoring.ts](./packages/shared/src/scoring.ts) and [executeSearch.ts](./packages/api/src/search/executeSearch.ts) express the arithmetic formula; parity is verified by unit and integration tests.

---

## ADR-005: Dual-Database Lifecycle & Idempotent Sample Data Import

- **Status:** Accepted
- **Context:** Local development and Playwright E2E tests benefit from zero-setup startup, whereas Docker Compose runs against PostgreSQL 16. `SPEC.md` also requires an import facility for [sample_listings.json](./sample_listings.json).
- **Decision:**
  1. [client.ts](./packages/api/src/db/client.ts) exports `createTestDatabase()` (PGlite), `createPostgresDatabase(url)` (closing the `pg.Pool` if `ensureSchema` fails), and `ensureSchema(db)`.
  2. [index.ts](./packages/api/src/index.ts) connects to `DATABASE_URL` when set and falls back to PGlite otherwise, running idempotent `seedDatabase(db)` (`onConflictDoNothing()`) on startup.
  3. [importSample.ts](./packages/api/src/db/importSample.ts) provides `importSampleListings(db, filePath?)` and the `npm run db:import` CLI command to ingest `sample_listings.json` idempotently.
- **Consequences:**
  - Tests and local Playwright runs start cleanly without external DB dependencies, while Docker Compose uses real PostgreSQL 16 and supports importing `sample_listings.json` via `docker compose exec api npm run db:import`.

---

## ADR-006: UI Concurrency, Validation Placement, and Form Control Design

- **Status:** Accepted
- **Context:** `REQ-UI-007` requires discarding stale out-of-order responses, `REQ-VAL-001` requires client-side validation before sending requests, `REQ-PAG-006`/`008` require resetting `page` to `1` on filter changes while preserving active criteria during pagination, and Playwright E2E tests interact with all controls via accessible labels.
- **Decision:**
  1. Use native `fetch` + `AbortController` + an incrementing `requestIdRef` in [App.tsx](./packages/ui/src/App.tsx) and [searchClient.ts](./packages/ui/src/api/searchClient.ts) rather than Apollo Client; superseded responses/rejections are ignored and do not clear `loading`.
  2. Keep the `Search` submit button enabled while loading so users can trigger a new search mid-flight and supersede stale requests.
  3. Run `validateSearchInput` in both `App.tsx` (so unit tests injecting a mock `searchFn` still exercise `REQ-VAL-001`) and `searchClient.ts`.
  4. Track committed `activeInputRef` separately from live `formValues` so clicking `Next Page` / `Previous Page` paginates the active search even if unsubmitted inputs were edited (`REQ-PAG-008`).
  5. Render `Page Size` as an MUI `TextField` rather than `Select` for direct numeric input and Playwright `.fill()` compatibility, and run an initial default search on mount.
- **Consequences:**
  - Smaller UI bundle, deterministic race-condition handling, and full alignment between unit tests and Playwright E2E selectors.

---

## ADR-007: Containerization & Nginx Reverse-Proxy Orchestration

- **Status:** Accepted
- **Context:** Both UI and API must be containerized and orchestrated together with PostgreSQL (`docker-compose.yml`).
- **Decision:**
  1. Use the project root (`.`) as Docker build context for both [packages/api/Dockerfile](./packages/api/Dockerfile) (`node:22-alpine`) and [packages/ui/Dockerfile](./packages/ui/Dockerfile) (multi-stage `node:22-alpine` builder + `nginx:alpine` runner) so npm workspace dependencies and `sample_listings.json` resolve cleanly.
  2. Configure [nginx.conf](./packages/ui/nginx.conf) to proxy `/graphql` to `http://api:4000/graphql` and serve SPA fallback routes via `try_files $uri $uri/ /index.html`.
  3. Configure `db` (`postgres:16-alpine`) with a `pg_isready` healthcheck and gate `api` startup on `condition: service_healthy`.
- **Consequences:**
  - Single-command startup (`docker compose up --build`) brings up Postgres (port 5432), API (port 4000), and UI (port 8080) with zero cross-origin issues in production.
