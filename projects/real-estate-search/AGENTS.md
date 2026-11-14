# Real Estate Search — Agent Instructions

All statements in this document follow ASD-STE100 Simplified Technical English rules: active voice, atomic sentences, and consistent terminology.

## 1. Repository Rules

- Read [SPEC.md](./SPEC.md) and [ADR.md](./ADR.md) before changing requirements, schemas, or API contracts.
- Keep `packages/shared` as the single source of truth for search validation and relevance scoring rules.
- Parameterize all SQL queries and use PostgreSQL-safe type casts and escaping (`ESCAPE '\'`).
- Preserve the GraphQL union error contract (`SearchListingsResult`), W3C `traceparent` context propagation, and single-line OpenTelemetry JSON logs.
- Keep unit tests deterministic without requiring external network services during test execution.

## 2. Quality Gate

Before reporting a task as complete, execute each required check in `projects/real-estate-search` and verify the pass criterion:

| Gate | Command | Pass Criterion |
|---|---|---|
| Build | `npm run build` | Builds `@real-estate/shared`, `@real-estate/api`, and `@real-estate/ui` with zero errors. |
| Unit & Integration Coverage | `npm run test:coverage` | Passes all tests with `>90%` coverage across every package. |
| Database Idempotency (DB changes) | `npm run db:import` | Imports `sample_listings.json` cleanly with zero duplicate rows. |
| End-to-End Verification (UI/API/Infra changes) | `npm run test:e2e` | Passes all Playwright browser journeys after rebuilding changed containers. |
| Spec & ADR Sync | Manual check | Updates [SPEC.md](./SPEC.md) and [ADR.md](./ADR.md) when schemas, APIs, or architecture decisions change. |

## 3. Completion Rules

- Report only file paths, symbols, and command outputs directly observed after the final edit.
- Report failing, skipped, or unrun checks explicitly.
