import {
  BUDGET_WEIGHT,
  MAX_RECENCY_DAYS,
  RECENCY_WEIGHT,
  REFERENCE_DATE_ISO,
  type Listing,
  type PaginationMeta,
  type SearchError,
  type ValidatedSearchInput,
} from '@real-estate/shared';
import { and, gte, sql, type SQL } from 'drizzle-orm';
import { listingsTable, type ListingRecord } from '../db/schema.js';

export type ListingSelectRow = Omit<ListingRecord, 'location'> & {
  relevanceScore: number;
};

export type PaginationPlan =
  | { status: 'empty'; pagination: PaginationMeta }
  | { status: 'out_of_range'; totalPages: number; error: SearchError }
  | { status: 'ok'; offset: number; pagination: PaginationMeta };

/** Escapes LIKE pattern metacharacters (`\`, `%`, `_`) for `ESCAPE '\'`. */
export function escapeLike(text: string): string {
  return text.replace(/[\\%_]/g, '\\$&');
}

/** Builds a combined Drizzle `WHERE` clause from validated search filters, or `undefined` when unfiltered. */
export function buildSearchWhereClause(input: ValidatedSearchInput): SQL | undefined {
  const conditions: SQL[] = [];

  if (input.minPrice !== undefined) {
    conditions.push(sql`${listingsTable.price} >= ${input.minPrice}::numeric`);
  }
  if (input.maxPrice !== undefined) {
    conditions.push(sql`${listingsTable.price} <= ${input.maxPrice}::numeric`);
  }
  if (input.minBedrooms !== undefined) {
    conditions.push(gte(listingsTable.bedrooms, input.minBedrooms));
  }
  if (input.city !== undefined) {
    conditions.push(sql`LOWER(${listingsTable.city}) = LOWER(${input.city})`);
  }
  if (input.keyword !== undefined) {
    conditions.push(
      sql`${listingsTable.description} ILIKE ${'%' + escapeLike(input.keyword) + '%'} ESCAPE '\\'`
    );
  }

  return conditions.length > 0 ? and(...conditions) : undefined;
}

/** Builds the SQL relevance score expression normalized to [0, 1] and rounded to 2 decimal places. */
export function buildRelevanceScoreExpr(targetBudget?: number): SQL<number> {
  const recencyExpr = sql`GREATEST(0.0, LEAST(1.0, 1.0 - GREATEST(0.0, EXTRACT(EPOCH FROM (${REFERENCE_DATE_ISO}::timestamptz - ${listingsTable.listedDate})) / 86400.0) / ${MAX_RECENCY_DAYS}::double precision))`;

  const rawExpr =
    targetBudget !== undefined
      ? sql`(${BUDGET_WEIGHT}::double precision * (1.0 / (1.0 + ABS(${listingsTable.price} - ${targetBudget})::double precision / ${targetBudget}::double precision)) + ${RECENCY_WEIGHT}::double precision * ${recencyExpr})`
      : recencyExpr;

  return sql<number>`ROUND(GREATEST(0.0, LEAST(1.0, ${rawExpr}))::numeric, 2)::double precision`;
}

/** Evaluates pagination outcome and SQL offset purely from page parameters and total match count. */
export function planPagination(page: number, pageSize: number, totalResults: number): PaginationPlan {
  if (totalResults === 0) {
    return {
      status: 'empty',
      pagination: { page, pageSize, totalResults: 0, totalPages: 0 },
    };
  }

  const totalPages = Math.ceil(totalResults / pageSize);
  if (page > totalPages) {
    return {
      status: 'out_of_range',
      totalPages,
      error: {
        code: 'OUT_OF_RANGE',
        message: `Page ${page} exceeds total pages ${totalPages}.`,
        field: 'page',
        totalPages,
      },
    };
  }

  return {
    status: 'ok',
    offset: (page - 1) * pageSize,
    pagination: { page, pageSize, totalResults, totalPages },
  };
}

/** Maps a projected database row into a public `Listing` domain object. */
export function mapListingRow(row: ListingSelectRow): Listing {
  const parsedListedDate = new Date(row.listedDate);
  const listedDate = Number.isNaN(parsedListedDate.getTime())
    ? row.listedDate
    : parsedListedDate.toISOString();

  return {
    internalId: row.internalId,
    id: row.id,
    source: row.source,
    address: row.address,
    city: row.city,
    state: row.state,
    zip: row.zip,
    price: row.price,
    bedrooms: row.bedrooms,
    bathrooms: row.bathrooms,
    sqft: row.sqft,
    latitude: row.latitude,
    longitude: row.longitude,
    status: row.status,
    description: row.description,
    listedDate,
    relevanceScore: Number(row.relevanceScore),
  };
}
