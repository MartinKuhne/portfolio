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

## ADR-002: Custom `JSONScalar` Pass-Through & GraphQL Union Errors as Data

- **Status:** Accepted (updated to align with GraphQL & GraphQL-over-HTTP conventions)
- **Context:** Standard GraphQL `Int`/`Float` input scalars reject or coerce malformed primitives at the GraphQL parser layer before resolvers execute, conflicting with `REQ-VAL-003` and `REQ-ERR-005`. Previously, the API also forced HTTP `400`/`500` on valid `{ data }` responses using a flat `{ ok, listings, pagination, error }` wrapper, which violates GraphQL-over-HTTP conventions and breaks standard GraphQL clients.
- **Decision:**
  1. Keep pass-through `JSONScalar` in [server.ts](./packages/api/src/graphql/server.ts) for numeric/integer GraphQL input fields so raw values reach `validateSearchInput` uncoerced (`REQ-VAL-003`).
  2. Model `Query.searchListings` as a nullable **GraphQL Union** (`SearchListingsResult = SearchListingsSuccess | SearchValidationError | SearchOutOfRangeError`) with a `SearchErrorCode` enum and shared `interface SearchError`, returned over HTTP **`200 OK`** per GraphQL-over-HTTP.
  3. Surface unexpected internal/database faults (`INTERNAL_ERROR` or unhandled resolver exceptions) as standard GraphQL top-level `errors` via `GraphQLError` (`extensions.code = "INTERNAL_SERVER_ERROR"`) with masked/sanitized messages and `data.searchListings = null`.
- **Consequences:**
  - Any GraphQL-compliant client (including browser `fetch`, GraphiQL, or Apollo Client) receives standard HTTP `200 OK` execution envelopes, discriminated union results for domain errors, and top-level `errors` for internal faults.
  - Malformed HTTP/JSON payloads and invalid GraphQL syntax/documents still return HTTP `400 Bad Request` directly from GraphQL Yoga.
  - Trade-off: Numeric input fields still use `JSONScalar` instead of `Int`/`Float` so per-field `SearchValidationError` responses preserve uncoerced field-level error reporting.

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
  4. Track committed `activeFormRef` separately from live `formState` so clicking `Next Page` / `Previous Page` paginates the active search even if unsubmitted inputs were edited (`REQ-PAG-008`).
  5. Render both `Min Bedrooms` (MUI `TextField select` with options `Any`, `1`–`10`, defaulting to `Any`) and `Page Size` (MUI `TextField select` with options `5`, `10`, `20`, `50`, `100`, defaulting to `10`) as dropdowns, and only display search results after the user presses `Search` (or `Reset Filters`) rather than auto-querying on initial page load.
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

---

## ADR-008: OpenTelemetry Tracing, Trace-Correlated Logging, and W3C Traceparent Propagation with Jaeger

- **Status:** Accepted
- **Context:** Operators need end-to-end visibility across browser search interactions, GraphQL HTTP handling, input validation, and PostgreSQL queries (`COUNT` and ranked `SELECT`), with browser-to-API trace continuity and trace-correlated logs.
- **Decision:**
  1. Instrument `@real-estate/api` ([telemetry.ts](./packages/api/src/telemetry.ts)) with `NodeTracerProvider`, `BatchSpanProcessor` (`scheduledDelayMillis: 500`), `W3CTraceContextPropagator`, and `LoggerProvider` emitting single-line JSON console logs plus span events (`addEvent`). OTLP trace export activates when `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` or `OTEL_EXPORTER_OTLP_ENDPOINT` is configured; OTLP log export only activates when `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` is explicitly set (avoiding `/v1/logs` 404s against Jaeger).
  2. Instrument GraphQL Yoga ([server.ts](./packages/api/src/graphql/server.ts)) via its `instrumentation.request` and `onResponse` hooks to extract incoming W3C `traceparent`/`tracestate` headers case-insensitively, create `SpanKind.SERVER` spans (leaving span status `UNSET` on 4xx or domain union errors and setting `SpanStatusCode.ERROR` on 5xx/GraphQL internal errors or unhandled exceptions), allow `traceparent`/`tracestate` in CORS preflight headers, and instrument search execution ([executeSearch.ts](./packages/api/src/search/executeSearch.ts)) with `SpanKind.INTERNAL` (`search.execute`, `search.validateInput`) and `SpanKind.CLIENT` (`search.db.count`, `search.db.query`) child spans.
  3. Instrument `@real-estate/ui` ([telemetry.ts](./packages/ui/src/telemetry.ts), [searchClient.ts](./packages/ui/src/api/searchClient.ts), [App.tsx](./packages/ui/src/App.tsx)) with `WebTracerProvider`, `BatchSpanProcessor` (`scheduledDelayMillis: 250`), and `LoggerProvider`, using `SpanKind.INTERNAL` on `ui.searchListings` and `SpanKind.CLIENT` on `HTTP POST /graphql`, injecting W3C `traceparent`/`tracestate` headers into `fetch('/graphql')`, and passing explicit span contexts across async boundaries so browser `StackContextManager` never loses trace correlation across `await`.
  4. Add `jaeger` (`jaegertracing/all-in-one:latest`, `COLLECTOR_OTLP_ENABLED=true`) to [docker-compose.yml](./docker-compose.yml) on ports `16686`, `4317`, and `4318`, and proxy `/v1/traces` through both [vite.config.ts](./packages/ui/vite.config.ts) and [nginx.conf](./packages/ui/nginx.conf).
- **Consequences:**
  - Every user search produces a single unified trace in Jaeger spanning `real-estate-ui` -> `real-estate-api` -> PostgreSQL queries, with matching `traceId`/`spanId` fields in both browser and server JSON logs.
  - Network OTLP export is skipped automatically in `MODE === 'test'` and when OTLP endpoints are unset, keeping unit tests fast and deterministic while still verifying real W3C `traceparent` generation and extraction.

---

## ADR-009: Surrogate `internal_id` Primary Key, Multi-Source Composite Uniqueness, and Native `POINT` Geolocation

- **Status:** Accepted
- **Context:** Real estate listings ingested from multiple MLS feeds (such as `MLS_A` and `MLS_B` in [sample_listings.json](./sample_listings.json)) can collide on external listing `id` values across feeds. Additionally, the initial schema omitted several rich attributes present in `sample_listings.json` (`source`, `state`, `zip`, `bathrooms`, `sqft`, `latitude`, `longitude`, `status`), and storing geospatial coordinates requires balancing zero-dependency compatibility across both PostgreSQL 16 (`postgres:16-alpine`) and in-memory `@electric-sql/pglite` against future spatial query needs.
- **Decision:**
  1. **Surrogate Primary Key + `(id, source)` Composite Uniqueness:** Replace `id TEXT PRIMARY KEY` with an auto-generated `internal_id UUID PRIMARY KEY DEFAULT gen_random_uuid()` in [schema.ts](./packages/api/src/db/schema.ts) and [client.ts](./packages/api/src/db/client.ts). Retain external `id` (`TEXT NOT NULL`) alongside `source` (`TEXT NOT NULL DEFAULT 'SEED'`), enforced by a composite unique index `listings_id_source_unique ON listings (id, source)`, and include an idempotent `DO $$ ... END $$;` legacy migration in `ensureSchema` to upgrade pre-existing databases safely.
  2. **First-Class Attribute Columns:** Model all attributes from [sample_listings.json](./sample_listings.json) (`source`, `state`, `zip`, `bathrooms`, `sqft`, `latitude`, `longitude`, `location`, `status`) as first-class columns in `listings`, populate them across `SEED_LISTINGS` and `importSampleListings`, expose them as optional fields on GraphQL `type Listing`, and extend deterministic tie-breaking (`score DESC, listed_date DESC, id COLLATE "C" ASC, source COLLATE "C" ASC, internal_id ASC`).
  3. **Native PostgreSQL `POINT` + WGS-84 `DOUBLE PRECISION` Coordinates:** Store exact WGS-84 `latitude` and `longitude` (`DOUBLE PRECISION`) alongside PostgreSQL's built-in 2D geometric `location POINT` in GeoJSON/OGC `(x = longitude, y = latitude)` axis order indexed with `USING gist (location)` (`listings_location_gist`).
- **Consequences:**
  - Listings with identical external `id` values from distinct `source` feeds coexist cleanly with unique `internal_id` UUIDs and deterministic ordering, while re-importing the same `(id, source)` remains idempotent via `ON CONFLICT (id, source) DO NOTHING`.
  - Native `POINT` + GiST indexing works out of the box in both `postgres:16-alpine` and PGlite without PostGIS extension/container bloat, while preserving exact coordinates and `(x=lon, y=lat)` axis order for a clean, zero-data-loss future upgrade path to PostGIS `geography(Point, 4326)` (`ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography`) when spherical distance queries are needed.
