import {
  computeRelevanceScore,
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

    const rows = await db
      .select()
      .from(listingsTable)
      .where(conditions.length > 0 ? and(...conditions) : undefined);

    const listings: Listing[] = rows.map((row) => ({
      id: row.id,
      address: row.address,
      city: row.city,
      price: row.price,
      bedrooms: row.bedrooms,
      description: row.description,
      listedDate: row.listedDate,
      relevanceScore: computeRelevanceScore({
        price: row.price,
        listedDate: row.listedDate,
        targetBudget: input.targetBudget,
      }),
    }));

    listings.sort((a, b) => {
      if (b.relevanceScore !== a.relevanceScore) {
        return b.relevanceScore - a.relevanceScore;
      }
      const dateB = Date.parse(b.listedDate);
      const dateA = Date.parse(a.listedDate);
      if (dateB !== dateA) {
        return dateB - dateA;
      }
      return a.id.localeCompare(b.id);
    });

    const totalResults = listings.length;
    const totalPages = totalResults === 0 ? 0 : Math.ceil(totalResults / input.pageSize);

    if (totalResults > 0 && input.page > totalPages) {
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

    const startIndex = (input.page - 1) * input.pageSize;
    const paginatedListings = listings.slice(startIndex, startIndex + input.pageSize);

    return {
      ok: true,
      listings: paginatedListings,
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
