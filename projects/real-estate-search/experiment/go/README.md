# Real Estate Search API — Production Go Implementation

This directory (`experiment/go`) provides an idiomatic, production-grade Go implementation of the Real Estate Search API conforming strictly to [SPEC.md](../../SPEC.md) and Architecture Decision Records [ADR-002](../../ADR.md#adr-002), [ADR-004](../../ADR.md#adr-004), and [ADR-009](../../ADR.md#adr-009).

---

## 1. Tech Stack Selection & Architectural Rationale

| Layer | Technology Choice | Rationale |
|---|---|---|
| **Language & Runtime** | Go 1.23 (`golang:1.23-alpine`) | High-performance compiled concurrency, minimal container footprint, and robust standard library (`net/http`, `sync`, `time`). |
| **GraphQL Engine** | `github.com/graphql-go/graphql` | Pure Go implementation matching GraphQL-JS semantics. Supports custom scalar AST inspection (`JSONScalar`), union resolution, interface implementations (`SearchError`), and selection complexity analysis without external code generation binaries (`gqlgen`). |
| **Database Driver** | `github.com/jackc/pgx/v5` (`pgxpool`) | Industry-standard PostgreSQL driver in Go. Provides connection pooling, parameterized query execution, idempotent batch inserts (`ON CONFLICT (id, source) DO NOTHING`), and native support for geometric `POINT` coordinates. |
| **Observability (OTel)** | `go.opentelemetry.io/otel` + `exporters/otlp/otlptrace/otlptracehttp` | W3C `TraceContext` HTTP extraction/injection, semantic span lifecycle matching the Node.js API, and structured single-line JSON logs correlated with `traceId`, `spanId`, and `traceFlags`. |
| **Validation & Scoring** | Zero-dependency pure Go domain packages | Authoritative, uncoerced validation (`internal/validation`) enforcing exact boundary limits (32-bit `INT4` 2,147,483,647 max, null-byte rejection, string bounds, and prototype pollution protection) and deterministic scoring arithmetic (`internal/scoring`). |

---

## 2. Directory Structure

```
experiment/go/
├── .dockerignore
├── Dockerfile                  # Multi-stage container build (builder + alpine runner)
├── README.md                   # This documentation
├── go.mod                      # Module real-estate-search
├── go.sum                      # Checksums
├── cmd/
│   ├── server/
│   │   └── main.go             # Production GraphQL HTTP server with graceful shutdown
│   └── importer/
│       └── main.go             # Standalone CLI sample data importer
└── internal/
    ├── domain/                 # Core entities, error codes, pagination metadata, search types
    ├── validation/             # Authoritative search criteria validator (REQ-VAL-002..012)
    ├── scoring/                # Relevance scoring formulas (BUDGET_WEIGHT=0.60, RECENCY_WEIGHT=0.40)
    ├── repository/             # Repository interface, query builder, in-memory fake, pgx repository
    ├── search/                 # SearchService orchestrator with OTel instrumentation
    ├── telemetry/              # OTel tracer provider, W3C propagator, JSON structured logger
    ├── importer/               # Sample JSON parser, validator, and idempotent import logic
    └── graphql/                # Schema, JSONScalar AST parser, complexity rules, CORS handler
```

---

## 3. Specification & Contract Compliance

### GraphQL Schema & Error Conventions (`ADR-002`)
- **Endpoint:** `POST /graphql` with CORS preflight `OPTIONS` support. Configurable `CORS_ORIGIN` allowlist (defaults to `http://localhost:8080,http://localhost:5173`) allowing headers `content-type`, `traceparent`, and `tracestate`.
- **Query:** `Query.searchListings(input: SearchListingsInput): SearchListingsResult`
- **Errors as Data Union:**
  - `SearchListingsSuccess`: `listings: [Listing!]!`, `pagination: PaginationMeta!`
  - `SearchValidationError`: implements `interface SearchError` (`code: SearchErrorCode!`, `message: String!`, `field: String`)
  - `SearchOutOfRangeError`: implements `interface SearchError` (`code: SearchErrorCode!`, `message: String!`, `field: String`, `totalPages: Int!`)
- **HTTP Status Codes:**
  - `200 OK`: Executed queries (including union errors like `SearchValidationError`, `SearchOutOfRangeError`, and sanitized top-level `INTERNAL_SERVER_ERROR` with `data.searchListings = null`).
  - `400 Bad Request`: Malformed HTTP/JSON requests and pre-execution schema/complexity validation failures.
- **Security Complexity Bounds (`SEC-06`, `SEC-07`):**
  - Query root-field alias count bounded to `10`.
  - Selection set nesting depth bounded to `15`.
  - `JSONScalar` rejects literal nesting depth exceeding `5` and forbids keys `__proto__`, `constructor`, and `prototype`.

### Database Schema & Multi-Source Deduplication (`ADR-009`)
- Table `listings` with primary key `internal_id UUID PRIMARY KEY DEFAULT gen_random_uuid()`.
- Multi-feed composite uniqueness: `UNIQUE (id, source)`.
- Native 2D geometric `location POINT` with GiST index `USING gist (location)`.
- Timestamp: `listed_date TIMESTAMPTZ NOT NULL` with `listings_listed_date_desc` index.
- Search indexes: `listings_price_idx`, `listings_bedrooms_idx`, `listings_city_lower_idx` (`LOWER(city)`), and GIN trigram `listings_description_trgm` when `pg_trgm` is available.
- Idempotent schema creation in `EnsureSchema` matching `client.ts` and `ADR-009`.

### OpenTelemetry Tracing & Structured Logging (`ADR-008`)
- W3C `TraceContext` propagation: extracts `traceparent` and `tracestate` from HTTP headers case-insensitively.
- Semantic spans emitted:
  - `POST /graphql` (`SpanKind.SERVER`, status `UNSET` on 200/4xx/domain errors, `ERROR` on 5xx / `INTERNAL_SERVER_ERROR`)
  - `graphql.searchListings` (`SpanKind.INTERNAL`)
  - `search.execute` (`SpanKind.INTERNAL`)
  - `search.validateInput` (`SpanKind.INTERNAL`)
  - `search.db.count` (`SpanKind.CLIENT`, `db.system = "postgresql"`, `db.collection.name = "listings"`)
  - `search.db.query` (`SpanKind.CLIENT`, `db.system = "postgresql"`, `db.collection.name = "listings"`)
- Single-line JSON log lines emitted with active trace correlation (`traceId`, `spanId`, `traceFlags`, `severityNumber`, `severityText`, `service`, `body`, `attributes`) and span events added via `span.AddEvent`.

---

## 4. Verification & Testing

All unit tests run inside the `golang:1.23-alpine` container without needing external daemon dependencies:

```bash
# 1. Run static analysis (go vet)
docker run --rm -v "$(pwd):/src" -w /src golang:1.23-alpine go vet ./...

# 2. Run test suite with race detector and coverage analysis
docker run --rm -v "$(pwd):/src" -w /src golang:1.23-alpine sh -c \
  "apk add --no-cache gcc musl-dev > /dev/null 2>&1 && go test -race -cover ./..."

# 3. Build standalone Docker container image
docker build -t real-estate-search-go .
```

### Statement Coverage Results

| Package | Coverage | Key Tested Scenarios |
|---|---|---|
| `internal/scoring` | **100.0%** | Inverse budget proximity, recency date parsing, weighted score arithmetic, rounding |
| `internal/search` | **100.0%** | Happy path pagination, empty results, validation rejection, out-of-range, DB errors |
| `internal/validation` | **95.9%** | All boundary checks, 32-bit INT4 overflow, null-byte rejection, string lengths, safe integers |
| `internal/importer` | **94.1%** | JSON parsing, candidate resolution, coordinate checks, idempotent imports |
| `internal/telemetry` | **92.5%** | W3C header extraction, OTLP endpoint configuration, trace-correlated JSON logs, span events |
| `internal/graphql` | **91.0%** | Schema query execution, union resolution, complexity depth/alias rejection, CORS, JSONScalar |
| `internal/repository` | **67.3%** | Complete in-memory repository, seed data, query builder with ILIKE escaping and scoring SQL |
