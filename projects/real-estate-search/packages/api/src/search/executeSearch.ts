import {
  BUDGET_WEIGHT,
  MAX_RECENCY_DAYS,
  RECENCY_WEIGHT,
  REFERENCE_DATE_ISO,
  validateSearchInput,
  type Listing,
  type RawSearchInput,
  type SearchResponse,
} from '@real-estate/shared';
import { and, gte, lte, sql, type SQL } from 'drizzle-orm';
import type { AppDatabase } from '../db/client.js';
import { listingsTable } from '../db/schema.js';

function escapeLike(text: string): string {
  return text.replace(/[\\%_]/g, '\\$&');
}

export async function executeSearch(
  db: AppDatabase,
  rawInput: RawSearchInput
): Promise<SearchResponse> {
  const validation = validateSearchInput(rawInput);
  if (!validation.ok) {
    return {
      ok: false,
      listings: [],
      pagination: null,
      error: validation.error,
    };
  }

  const input = validation.value;

  try {
    const conditions: SQL[] = [];

    if (input.minPrice !== undefined) {
      conditions.push(gte(listingsTable.price, input.minPrice));
    }
    if (input.maxPrice !== undefined) {
      conditions.push(lte(listingsTable.price, input.maxPrice));
    }
    if (input.minBedrooms !== undefined) {
      conditions.push(gte(listingsTable.bedrooms, input.minBedrooms));
    }
    if (input.city !== undefined) {
      conditions.push(
        sql`${listingsTable.city} ILIKE ${escapeLike(input.city)} ESCAPE '\\'`
      );
    }
    if (input.keyword !== undefined) {
      conditions.push(
        sql`${listingsTable.description} ILIKE ${'%' + escapeLike(input.keyword) + '%'} ESCAPE '\\'`
      );
    }

    const whereClause = conditions.length > 0 ? and(...conditions) : undefined;

    // Step 1: Compute totalResults via COUNT(*)::int
    const countRows = await db
      .select({ count: sql<number>`COUNT(*)::int` })
      .from(listingsTable)
      .where(whereClause);

    const totalResults = Number(countRows[0]?.count ?? 0);
    const totalPages = totalResults === 0 ? 0 : Math.ceil(totalResults / input.pageSize);

    if (totalResults === 0) {
      return {
        ok: true,
        listings: [],
        pagination: {
          page: input.page,
          pageSize: input.pageSize,
          totalResults: 0,
          totalPages: 0,
        },
        error: null,
      };
    }

    if (input.page > totalPages) {
      return {
        ok: false,
        listings: [],
        pagination: null,
        error: {
          code: 'OUT_OF_RANGE',
          message: `Page ${input.page} exceeds total pages ${totalPages}.`,
          field: 'page',
          totalPages,
        },
      };
    }

    // Step 2: Build score SQL expression single-sourced from shared constants
    const recencyExpr = sql`GREATEST(0.0, LEAST(1.0, 1.0 - GREATEST(0.0, EXTRACT(EPOCH FROM (${REFERENCE_DATE_ISO}::timestamptz - ${listingsTable.listedDate}::timestamptz)) / 86400.0) / ${MAX_RECENCY_DAYS}::double precision))`;

    const rawExpr =
      input.targetBudget !== undefined
        ? sql`(${BUDGET_WEIGHT}::double precision * GREATEST(0.0, LEAST(1.0, 1.0 - ABS(${listingsTable.price} - ${input.targetBudget})::double precision / ${input.targetBudget}::double precision)) + ${RECENCY_WEIGHT}::double precision * ${recencyExpr})`
        : recencyExpr;

    const scoreExpr = sql<number>`ROUND(GREATEST(0.0, LEAST(1.0, ${rawExpr}))::numeric, 2)::double precision`;

    // Step 3: Fetch only the requested page slice in SQL using ORDER BY ... LIMIT ... OFFSET
    const offset = (input.page - 1) * input.pageSize;

    const rows = await db
      .select({
        id: listingsTable.id,
        address: listingsTable.address,
        city: listingsTable.city,
        price: listingsTable.price,
        bedrooms: listingsTable.bedrooms,
        description: listingsTable.description,
        listedDate: listingsTable.listedDate,
        relevanceScore: scoreExpr,
      })
      .from(listingsTable)
      .where(whereClause)
      .orderBy(
        sql`${scoreExpr} DESC`,
        sql`${listingsTable.listedDate}::timestamptz DESC`,
        sql`${listingsTable.id} COLLATE "C" ASC`
      )
      .limit(input.pageSize)
      .offset(offset);

    const listings: Listing[] = rows.map((row) => ({
      id: row.id,
      address: row.address,
      city: row.city,
      price: row.price,
      bedrooms: row.bedrooms,
      description: row.description,
      listedDate: row.listedDate,
      relevanceScore: Number(row.relevanceScore),
    }));

    return {
      ok: true,
      listings,
      pagination: {
        page: input.page,
        pageSize: input.pageSize,
        totalResults,
        totalPages,
      },
      error: null,
    };
  } catch (err) {
    return {
      ok: false,
      listings: [],
      pagination: null,
      error: {
        code: 'INTERNAL_ERROR',
        message: err instanceof Error ? err.message : 'An unexpected database error occurred.',
        field: null,
      },
    };
  }
}
