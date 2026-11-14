# Real Estate Search API (Java 21)

Independent Java 21 implementation of the Real Estate Search GraphQL API with full specification and ADR parity (`SPEC.md`, `ADR.md`, `ADR-002`, `ADR-004`, `ADR-008`, `ADR-009`).

## Technology Stack

- **Runtime & Build:** Java 21, Maven 3.9 (`maven-shade-plugin`, `jacoco-maven-plugin`)
- **HTTP & GraphQL:** Javalin 6 (`6.4.0`), `graphql-java` (`22.3`), Jackson (`2.18.2`)
- **Persistence:** PostgreSQL JDBC (`42.7.5`), HikariCP (`6.2.1`), plus deterministic `InMemoryListingRepository` fallback when `DATABASE_URL` is unset
- **Observability:** OpenTelemetry Java SDK (`1.46.0`), W3C TraceContext propagation (`traceparent` / `tracestate`), OTLP HTTP exporters, single-line JSON structured logs on `stdout` synchronized with span events
- **Testing:** JUnit 5, AssertJ, Mockito, `opentelemetry-sdk-testing`, JaCoCo (>=90% line coverage gate)

## Package Architecture

- `com.realestate.api.domain` — Core records (`Listing`, `NewListing`, `PaginationMeta`, `RawSearchInput`, `SearchCriteria`, `SearchError`, `SearchErrorCode`, `SearchResult`) and `ListingRepository` interface
- `com.realestate.api.validation` — `SearchValidator` and `DefaultSearchValidator` (`REQ-VAL-002..011`, INT4 bounds `2147483647`, `MAX_SAFE_INTEGER`, null-byte rejection, `city` <= 100, `keyword` <= 255)
- `com.realestate.api.scoring` — `RelevanceScorer` and `DefaultRelevanceScorer` (`0.60` budget proximity + `0.40` linear recency decay over `365` days against `2026-11-01T00:00:00.000Z`, clamped `[0.00, 1.00]`, half-up rounded to 2 decimal places)
- `com.realestate.api.db` — `PostgresDataSourceFactory`, `PostgresListingRepository`, `SearchQueryBuilder` (parameterized SQL, `LOWER(city) = LOWER(?)`, `ILIKE ... ESCAPE '\\'`, SQL scoring & tie-breakers `listed_date DESC, id COLLATE "C" ASC, source COLLATE "C" ASC, internal_id ASC`), `InMemoryListingRepository`, `SeedListings` (24 seed listings), and `SampleImporter` CLI
- `com.realestate.api.telemetry` — `TelemetryConfig`, `ApiTelemetry`, `HeaderTraceExtractor`, `JsonConsoleLogRecordExporter`
- `com.realestate.api.search` — `SearchService` (`search.execute` and `search.validateInput` spans, pagination planning, sanitized `INTERNAL_ERROR` handling)
- `com.realestate.api.graphql` — `JsonScalar` (max AST depth `5`, forbids `__proto__`/`constructor`/`prototype`), `QueryComplexityValidator` (max `10` root fields, max depth `15`), `GraphqlSchemaFactory`, `CorsConfig`, `GraphqlHttpApp`
- `com.realestate.api.Main` — HTTP server entrypoint with graceful shutdown

## Build, Test, and Coverage

Run inside `experiment/java`:

```bash
mvn clean verify
```

Or inside Docker without a local JDK/Maven installation:

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9-eclipse-temurin-21 mvn clean verify
```

The `verify` lifecycle compiles targeting Java 21, executes the JUnit 5 test suite, packages the shaded JAR (`target/real-estate-api-1.0.0.jar`), generates JaCoCo reports (`target/site/jacoco/`), and enforces the `>=90%` line coverage threshold.

## Running the Server & CLI Importer

### Local JAR

```bash
# In-memory seeded mode (defaults to PORT=4000)
java -jar target/real-estate-api-1.0.0.jar

# PostgreSQL mode
DATABASE_URL="postgres://postgres:postgres@localhost:5432/realestate" \
PORT=4000 \
CORS_ORIGIN="http://localhost:8080,http://localhost:5173" \
OTEL_SERVICE_NAME="real-estate-api" \
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://localhost:4318/v1/traces" \
java -jar target/real-estate-api-1.0.0.jar
```

### Sample Listings Importer CLI

```bash
java -cp target/real-estate-api-1.0.0.jar com.realestate.api.db.SampleImporter ../../sample_listings.json
```

### Docker

```bash
docker build -t real-estate-api-java .
docker run --rm -p 4000:4000 real-estate-api-java
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `4000` | HTTP listen port |
| `DATABASE_URL` | unset | PostgreSQL connection URI (`postgres://`, `postgresql://`, or `jdbc:postgresql://`); falls back to seeded `InMemoryListingRepository` when unset |
| `CORS_ORIGIN` | `http://localhost:8080,http://localhost:5173` | Comma-separated allowed CORS origins |
| `OTEL_SERVICE_NAME` | `real-estate-api` | OpenTelemetry service name resource attribute |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | unset | Explicit OTLP HTTP traces endpoint (e.g. `http://localhost:4318/v1/traces`) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset | Base OTLP HTTP endpoint (`/v1/traces` appended when traces endpoint is unset) |
| `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` | unset | Optional OTLP HTTP logs endpoint |

## GraphQL & Observability Contract

- **Endpoint:** `POST /graphql` (also supports `GET /graphql` and `OPTIONS /graphql` CORS preflight allowing `content-type, traceparent, tracestate`)
- **Union Errors as Data (`200 OK`):** `Query.searchListings(input: SearchListingsInput): SearchListingsResult` returns `SearchListingsSuccess | SearchValidationError | SearchOutOfRangeError` (`interface SearchError`)
- **Internal Faults (`200 OK` with top-level `errors`):** Unexpected repository/execution failures return `extensions.code = "INTERNAL_SERVER_ERROR"` with sanitized message and `data.searchListings = null`
- **Malformed Requests (`400 Bad Request`):** Invalid JSON bodies, GraphQL syntax/validation errors, exceeded query complexity bounds, or rejected `JSONScalar` literals
- **Spans:** `POST /graphql` (`SERVER`), `graphql.searchListings` (`INTERNAL`), `search.execute` (`INTERNAL`), `search.validateInput` (`INTERNAL`), `search.db.count` (`CLIENT`), `search.db.query` (`CLIENT`)
