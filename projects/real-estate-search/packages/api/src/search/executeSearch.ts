import { SpanKind, SpanStatusCode, type Span } from '@opentelemetry/api';
import {
  validateSearchInput,
  type Listing,
  type PaginationMeta,
  type RawSearchInput,
  type SearchError,
  type SearchResponse,
  type ValidatedSearchInput,
} from '@real-estate/shared';
import { sql, type SQL } from 'drizzle-orm';
import type { AppDatabase } from '../db/client.js';
import { listingsTable } from '../db/schema.js';
import { getApiTracer, logApi, SeverityNumber, withApiSpan } from '../telemetry.js';
import {
  buildRelevanceScoreExpr,
  buildSearchWhereClause,
  mapListingRow,
  planPagination,
} from './searchQuery.js';

interface FailureTelemetry {
  eventName: string;
  severity: SeverityNumber;
  severityText: string;
  logAttributes: Record<string, string | number>;
  spanStatusMessage?: string;
}

async function validateInputWithSpan(rawInput: RawSearchInput) {
  return withApiSpan('search.validateInput', {}, (validateSpan) => {
    const result = validateSearchInput(rawInput);
    validateSpan.setAttribute('search.validation.ok', result.ok);
    if (!result.ok) {
      validateSpan.setStatus({
        code: SpanStatusCode.ERROR,
        message: result.error.message,
      });
    }
    return result;
  });
}

async function countMatchingListings(
  db: AppDatabase,
  whereClause: SQL | undefined
): Promise<number> {
  return withApiSpan(
    'search.db.count',
    {
      'db.system': 'postgresql',
      'db.operation.name': 'SELECT',
      'db.collection.name': 'listings',
    },
    async (countSpan) => {
      const countRows = await db
        .select({ count: sql<number>`COUNT(*)::int` })
        .from(listingsTable)
        .where(whereClause);
      const count = Number(countRows[0]?.count ?? 0);
      countSpan.setAttribute('search.totalResults', count);
      return count;
    },
    SpanKind.CLIENT
  );
}

async function fetchListingPage(
  db: AppDatabase,
  input: ValidatedSearchInput,
  whereClause: SQL | undefined,
  offset: number
): Promise<Listing[]> {
  const scoreExpr = buildRelevanceScoreExpr(input.targetBudget);

  const rows = await withApiSpan(
    'search.db.query',
    {
      'db.system': 'postgresql',
      'db.operation.name': 'SELECT',
      'db.collection.name': 'listings',
      'search.page': input.page,
      'search.pageSize': input.pageSize,
    },
    async (querySpan) => {
      const resultRows = await db
        .select({
          internalId: listingsTable.internalId,
          id: listingsTable.id,
          source: listingsTable.source,
          address: listingsTable.address,
          city: listingsTable.city,
          state: listingsTable.state,
          zip: listingsTable.zip,
          price: listingsTable.price,
          bedrooms: listingsTable.bedrooms,
          bathrooms: listingsTable.bathrooms,
          sqft: listingsTable.sqft,
          latitude: listingsTable.latitude,
          longitude: listingsTable.longitude,
          status: listingsTable.status,
          description: listingsTable.description,
          listedDate: listingsTable.listedDate,
          relevanceScore: scoreExpr,
        })
        .from(listingsTable)
        .where(whereClause)
        .orderBy(
          sql`${scoreExpr} DESC`,
          sql`${listingsTable.listedDate}::timestamptz DESC`,
          sql`${listingsTable.id} COLLATE "C" ASC`,
          sql`${listingsTable.source} COLLATE "C" ASC`,
          sql`${listingsTable.internalId} ASC`
        )
        .limit(input.pageSize)
        .offset(offset);
      querySpan.setAttribute('search.returnedCount', resultRows.length);
      return resultRows;
    },
    SpanKind.CLIENT
  );

  return rows.map(mapListingRow);
}

function completeSearch(
  executeSpan: Span,
  input: ValidatedSearchInput,
  listings: Listing[],
  pagination: PaginationMeta
): SearchResponse {
  executeSpan.setAttributes({
    'search.ok': true,
    'search.returnedCount': listings.length,
  });
  logApi(SeverityNumber.INFO, 'INFO', 'search.completed', {
    'search.page': input.page,
    'search.pageSize': input.pageSize,
    'search.totalResults': pagination.totalResults,
    'search.returnedCount': listings.length,
  });
  return {
    ok: true,
    listings,
    pagination,
    error: null,
  };
}

function recordSearchFailure(
  executeSpan: Span,
  error: SearchError,
  telemetry: FailureTelemetry
): SearchResponse {
  executeSpan.setAttribute('search.ok', false);
  executeSpan.setStatus({
    code: SpanStatusCode.ERROR,
    message: telemetry.spanStatusMessage ?? error.message,
  });
  logApi(telemetry.severity, telemetry.severityText, telemetry.eventName, telemetry.logAttributes);
  return {
    ok: false,
    listings: [],
    pagination: null,
    error,
  };
}

/** Executes a validated, paginated property search with SQL scoring and OpenTelemetry instrumentation. */
export async function executeSearch(
  db: AppDatabase,
  rawInput: RawSearchInput
): Promise<SearchResponse> {
  return getApiTracer().startActiveSpan('search.execute', async (executeSpan): Promise<SearchResponse> => {
    try {
      logApi(SeverityNumber.INFO, 'INFO', 'search.started', {
        'search.hasCity': rawInput.city !== undefined,
        'search.hasKeyword': rawInput.keyword !== undefined,
        'search.hasTargetBudget': rawInput.targetBudget !== undefined,
      });

      const validation = await validateInputWithSpan(rawInput);
      if (!validation.ok) {
        return recordSearchFailure(executeSpan, validation.error, {
          eventName: 'search.validation_failed',
          severity: SeverityNumber.WARN,
          severityText: 'WARN',
          logAttributes: {
            'error.code': validation.error.code,
            'error.field': validation.error.field ?? '',
          },
        });
      }

      const input = validation.value;
      executeSpan.setAttributes({
        'search.page': input.page,
        'search.pageSize': input.pageSize,
      });

      const whereClause = buildSearchWhereClause(input);
      const totalResults = await countMatchingListings(db, whereClause);
      executeSpan.setAttribute('search.totalResults', totalResults);

      const plan = planPagination(input.page, input.pageSize, totalResults);
      if (plan.status === 'empty') {
        return completeSearch(executeSpan, input, [], plan.pagination);
      }
      if (plan.status === 'out_of_range') {
        return recordSearchFailure(executeSpan, plan.error, {
          eventName: 'search.out_of_range',
          severity: SeverityNumber.WARN,
          severityText: 'WARN',
          logAttributes: {
            'search.page': input.page,
            'search.totalPages': plan.totalPages,
            'search.totalResults': totalResults,
          },
        });
      }

      const listings = await fetchListingPage(db, input, whereClause, plan.offset);
      return completeSearch(executeSpan, input, listings, plan.pagination);
    } catch (err) {
      console.error('executeSearch internal error:', err);
      const error = err instanceof Error ? err : new Error(String(err));
      executeSpan.recordException(error);
      return recordSearchFailure(
        executeSpan,
        {
          code: 'INTERNAL_ERROR',
          message: 'An unexpected database error occurred.',
          field: null,
        },
        {
          eventName: 'search.internal_error',
          severity: SeverityNumber.ERROR,
          severityText: 'ERROR',
          logAttributes: { 'error.message': error.message },
          spanStatusMessage: error.message,
        }
      );
    } finally {
      executeSpan.end();
    }
  });
}
