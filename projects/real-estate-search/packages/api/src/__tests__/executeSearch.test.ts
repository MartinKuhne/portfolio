import { beforeAll, describe, expect, it, vi } from 'vitest';
import { PGlite } from '@electric-sql/pglite';
import { pg_trgm } from '@electric-sql/pglite/contrib/pg_trgm';
import { sql } from 'drizzle-orm';
import { drizzle as drizzlePglite } from 'drizzle-orm/pglite';
import pg from 'pg';
import { computeRelevanceScore, REFERENCE_DATE_ISO } from '@real-estate/shared';
import {
  createPostgresDatabase,
  createTestDatabase,
  ensureSchema,
  type AppDatabase,
} from '../db/client.js';
import * as schema from '../db/schema.js';
import { listingsTable } from '../db/schema.js';
import { seedDatabase } from '../db/seed.js';
import { executeSearch } from '../search/executeSearch.js';
import { mapListingRow } from '../search/searchQuery.js';

describe('executeSearch domain service', () => {
  let db: AppDatabase;

  beforeAll(async () => {
    db = await createTestDatabase();
    await seedDatabase(db);
  });

  it('produces identical deterministic outputs for identical inputs (REQ-ARCH-004)', async () => {
    const first = await executeSearch(db, { city: 'Austin', targetBudget: 500000 });
    const second = await executeSearch(db, { city: 'Austin', targetBudget: 500000 });
    expect(first).toEqual(second);
  });

  it('filters by minPrice, maxPrice, minBedrooms, case-insensitive city, and case-insensitive keyword with AND semantics (REQ-FLT-002..008)', async () => {
    const res = await executeSearch(db, {
      minPrice: 400000,
      maxPrice: 650000,
      minBedrooms: 3,
      city: '  austin ',
      keyword: '  POOL ',
    });
    expect(res.ok).toBe(true);
    expect(res.listings.length).toBeGreaterThan(0);
    for (const item of res.listings) {
      expect(item.price).toBeGreaterThanOrEqual(400000);
      expect(item.price).toBeLessThanOrEqual(650000);
      expect(item.bedrooms).toBeGreaterThanOrEqual(3);
      expect(item.city.toLowerCase()).toBe('austin');
      expect(item.description.toLowerCase()).toContain('pool');
    }
  });

  it('escapes SQL wildcard characters % and _ in keyword and city (Review Focus)', async () => {
    const resKeyword = await executeSearch(db, { keyword: '100%' });
    expect(resKeyword.ok).toBe(true);
    expect(resKeyword.listings.length).toBeGreaterThan(0);
    for (const item of resKeyword.listings) {
      expect(item.description).toContain('100%');
    }

    // Testing city with wildcard characters that does not exist literally
    const resWildcardCity = await executeSearch(db, { city: 'Aust%' });
    expect(resWildcardCity.ok).toBe(true);
    expect(resWildcardCity.listings).toHaveLength(0);

    const resUnderscoreCity = await executeSearch(db, { city: 'Aust_n' });
    expect(resUnderscoreCity.ok).toBe(true);
    expect(resUnderscoreCity.listings).toHaveLength(0);
  });

  it('matches city case-insensitively via LOWER(city) while treating % and _ as literal characters', async () => {
    const cityDb = await createTestDatabase();
    await cityDb.insert(listingsTable).values([
      {
        id: 'CITY-LIT-1',
        address: '1 Literal Way',
        city: '100%_Ville',
        price: 400000,
        bedrooms: 3,
        description: 'City name containing literal percent and underscore',
        listedDate: '2026-10-01T00:00:00.000Z',
      },
      {
        id: 'CITY-LIT-2',
        address: '2 Wildcard Trap Rd',
        city: '100XYZ_Ville',
        price: 410000,
        bedrooms: 3,
        description: 'Should not match literal percent query',
        listedDate: '2026-10-02T00:00:00.000Z',
      },
      {
        id: 'CITY-LIT-3',
        address: '3 Underscore Trap Rd',
        city: '100%AVille',
        price: 420000,
        bedrooms: 3,
        description: 'Should not match literal underscore query',
        listedDate: '2026-10-03T00:00:00.000Z',
      },
    ]);

    const res = await executeSearch(cityDb, { city: '100%_vIlLe' });
    expect(res.ok).toBe(true);
    expect(res.listings.map((l) => l.id)).toEqual(['CITY-LIT-1']);
  });

  it('sorts by relevanceScore DESC, then listedDate DESC, then id ASC on ties (REQ-RNK-008..010)', async () => {
    const res = await executeSearch(db, { city: 'Denver', targetBudget: 500000, pageSize: 20 });
    expect(res.ok).toBe(true);
    expect(res.listings.length).toBeGreaterThanOrEqual(4);

    for (let i = 1; i < res.listings.length; i++) {
      const prev = res.listings[i - 1];
      const curr = res.listings[i];
      expect(prev.relevanceScore).toBeGreaterThanOrEqual(curr.relevanceScore);
      if (prev.relevanceScore === curr.relevanceScore) {
        if (prev.listedDate === curr.listedDate) {
          expect(prev.id.localeCompare(curr.id)).toBeLessThan(0);
        } else {
          expect(Date.parse(prev.listedDate)).toBeGreaterThan(Date.parse(curr.listedDate));
        }
      }
    }
  });

  it('slices results by page and pageSize and returns pagination metadata (REQ-PAG-004, REQ-PAG-005)', async () => {
    const p1 = await executeSearch(db, { page: 1, pageSize: 5 });
    const p2 = await executeSearch(db, { page: 2, pageSize: 5 });
    expect(p1.ok && p2.ok).toBe(true);
    expect(p1.listings).toHaveLength(5);
    expect(p1.pagination).toMatchObject({ page: 1, pageSize: 5 });
    expect(p1.listings[0].id).not.toBe(p2.listings[0].id);
  });

  it('returns empty listings without error when totalResults is 0 and page is 1 (Review Focus)', async () => {
    const res = await executeSearch(db, { city: 'NonExistentCity', page: 1 });
    expect(res).toEqual({
      ok: true,
      listings: [],
      pagination: { page: 1, pageSize: 10, totalResults: 0, totalPages: 0 },
      error: null,
    });
  });

  it('returns OUT_OF_RANGE error with totalPages when totalResults > 0 and page > totalPages (REQ-VAL-012)', async () => {
    const res = await executeSearch(db, { city: 'Austin', page: 99, pageSize: 10 });
    expect(res.ok).toBe(false);
    expect(res.error?.code).toBe('OUT_OF_RANGE');
    expect(res.error?.totalPages).toBeGreaterThan(0);
    expect(res.listings).toEqual([]);
    expect(res.pagination).toBeNull();
  });

  it('ranks listings correctly when targetBudget is omitted (REQ-RNK-006)', async () => {
    const res = await executeSearch(db, { city: 'Austin' });
    expect(res.ok).toBe(true);
    expect(res.listings.length).toBeGreaterThan(0);
    for (const item of res.listings) {
      expect(item.relevanceScore).toBeGreaterThanOrEqual(0);
      expect(item.relevanceScore).toBeLessThanOrEqual(1);
    }
  });

  it('returns VALIDATION_ERROR when search input validation fails (REQ-VAL-006, REQ-ERR-005)', async () => {
    const res = await executeSearch(db, { minPrice: 600000, maxPrice: 400000 });
    expect(res.ok).toBe(false);
    expect(res.error).toEqual({
      code: 'VALIDATION_ERROR',
      field: 'minPrice',
      message: 'minPrice must not exceed maxPrice.',
    });
    expect(res.listings).toEqual([]);
    expect(res.pagination).toBeNull();
  });

  it('handles decimal minPrice/maxPrice safely in SQL and blocks INT4 overflow or null-byte injection via validation', async () => {
    const decimalRes = await executeSearch(db, { minPrice: 250000.5, maxPrice: 600000.75 });
    expect(decimalRes.ok).toBe(true);

    const overflowRes = await executeSearch(db, { minPrice: 2_147_483_648 });
    expect(overflowRes.ok).toBe(false);
    expect(overflowRes.error?.code).toBe('VALIDATION_ERROR');

    const nullByteRes = await executeSearch(db, { city: 'Austin\0' });
    expect(nullByteRes.ok).toBe(false);
    expect(nullByteRes.error?.code).toBe('VALIDATION_ERROR');
  });

  it('handles unexpected database errors gracefully by returning sanitized INTERNAL_ERROR (REQ-ERR-006)', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const mockDb = {
      select: () => {
        throw new Error('Simulated database connection failure');
      },
    } as unknown as AppDatabase;

    const res = await executeSearch(mockDb, { city: 'Austin' });
    expect(res.ok).toBe(false);
    expect(res.error).toEqual({
      code: 'INTERNAL_ERROR',
      message: 'An unexpected database error occurred.',
      field: null,
    });
    expect(res.error?.message).not.toContain('Simulated database connection failure');
    expect(consoleSpy).toHaveBeenCalledWith(
      'executeSearch internal error:',
      expect.any(Error)
    );
    expect(res.listings).toEqual([]);
    expect(res.pagination).toBeNull();
    consoleSpy.mockRestore();
  });

  it('handles non-Error thrown gracefully by returning sanitized INTERNAL_ERROR', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const mockDb = {
      select: () => {
        throw 'String error';
      },
    } as unknown as AppDatabase;

    const res = await executeSearch(mockDb, { city: 'Austin' });
    expect(res.ok).toBe(false);
    expect(res.error).toEqual({
      code: 'INTERNAL_ERROR',
      message: 'An unexpected database error occurred.',
      field: null,
    });
    expect(res.error?.message).not.toContain('String error');
    expect(consoleSpy).toHaveBeenCalledWith('executeSearch internal error:', 'String error');
    expect(res.listings).toEqual([]);
    expect(res.pagination).toBeNull();
    consoleSpy.mockRestore();
  });

  it('maintains strict SQL-vs-shared scoring parity for edge-case and seed listings with and without targetBudget (ADR-004, REQ-RNK-002..007)', async () => {
    const parityDb = await createTestDatabase();
    await seedDatabase(parityDb);

    await parityDb.insert(listingsTable).values([
      {
        id: 'edge-ref-date',
        address: '100 Reference St',
        city: 'ParityCity',
        price: 500000,
        bedrooms: 3,
        description: 'Exact reference date listing',
        listedDate: REFERENCE_DATE_ISO,
      },
      {
        id: 'edge-future-date',
        address: '200 Future Ave',
        city: 'ParityCity',
        price: 250000,
        bedrooms: 2,
        description: 'Future listed date clamping check',
        listedDate: '2026-12-15T12:00:00.000Z',
      },
      {
        id: 'edge-over-365-days',
        address: '300 Vintage Rd',
        city: 'ParityCity',
        price: 1200000,
        bedrooms: 4,
        description: 'Older than 365 days and over 2x target budget',
        listedDate: '2025-01-01T00:00:00.000Z',
      },
      {
        id: 'edge-exact-365-days',
        address: '400 Boundary Ln',
        city: 'ParityCity',
        price: 425000,
        bedrooms: 3,
        description: 'Exactly 365 days before reference date',
        listedDate: '2025-11-01T00:00:00.000Z',
      },
    ]);

    const targetBudget = 500000;
    const withBudget = await executeSearch(parityDb, { pageSize: 100, targetBudget });
    expect(withBudget.ok).toBe(true);
    expect(withBudget.listings.length).toBe(28);
    for (const item of withBudget.listings) {
      expect(item.relevanceScore).toBe(
        computeRelevanceScore({
          price: item.price,
          listedDate: item.listedDate,
          targetBudget,
        })
      );
    }

    const withoutBudget = await executeSearch(parityDb, { pageSize: 100 });
    expect(withoutBudget.ok).toBe(true);
    expect(withoutBudget.listings.length).toBe(28);
    for (const item of withoutBudget.listings) {
      expect(item.relevanceScore).toBe(
        computeRelevanceScore({
          price: item.price,
          listedDate: item.listedDate,
        })
      );
    }

    // Verify no-targetBudget index-friendly listedDate DESC ordering matches REQ-RNK-008..010
    const idsInOrder = withoutBudget.listings.map((item) => item.id);
    expect(idsInOrder.indexOf('edge-future-date')).toBeLessThan(
      idsInOrder.indexOf('edge-ref-date')
    );
    expect(idsInOrder.indexOf('edge-exact-365-days')).toBeLessThan(
      idsInOrder.indexOf('edge-over-365-days')
    );
    for (let i = 1; i < withoutBudget.listings.length; i++) {
      const prev = withoutBudget.listings[i - 1];
      const curr = withoutBudget.listings[i];
      expect(prev.relevanceScore).toBeGreaterThanOrEqual(curr.relevanceScore);
      const prevMs = Date.parse(prev.listedDate);
      const currMs = Date.parse(curr.listedDate);
      expect(prevMs).toBeGreaterThanOrEqual(currMs);
      if (prevMs === currMs) {
        expect(prev.id <= curr.id).toBe(true);
      }
    }
  });

  it('creates TIMESTAMPTZ listed_date and all filter/ranking indexes via ensureSchema, including legacy TEXT migration', async () => {
    const colResult = await db.execute<{ data_type: string }>(sql`
      SELECT data_type
      FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name = 'listings'
        AND column_name = 'listed_date';
    `);
    expect(colResult.rows[0]?.data_type).toBe('timestamp with time zone');

    const idxResult = await db.execute<{ indexname: string }>(sql`
      SELECT indexname
      FROM pg_indexes
      WHERE schemaname = current_schema()
        AND tablename = 'listings';
    `);
    const indexNames = idxResult.rows.map((r) => r.indexname);
    expect(indexNames).toContain('listings_id_source_unique');
    expect(indexNames).toContain('listings_location_gist');
    expect(indexNames).toContain('listings_listed_date_desc');
    expect(indexNames).toContain('listings_price_idx');
    expect(indexNames).toContain('listings_bedrooms_idx');
    expect(indexNames).toContain('listings_city_lower_idx');
    expect(indexNames).toContain('listings_description_trgm');

    // Legacy TEXT listed_date table upgrade
    const legacyClient = new PGlite({ extensions: { pg_trgm } });
    const legacyDb = drizzlePglite(legacyClient, { schema });
    await legacyClient.exec(`
      CREATE TABLE listings (
        internal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        id TEXT NOT NULL,
        source TEXT NOT NULL DEFAULT 'SEED',
        address TEXT NOT NULL,
        city TEXT NOT NULL,
        price INTEGER NOT NULL,
        bedrooms INTEGER NOT NULL,
        description TEXT NOT NULL,
        listed_date TEXT NOT NULL
      );
      INSERT INTO listings (id, address, city, price, bedrooms, description, listed_date)
      VALUES ('LEG-1', '1 Legacy Ln', 'Austin', 400000, 2, 'Legacy text date', '2026-09-15T00:00:00.000Z');
    `);

    await ensureSchema(legacyDb);

    const legacyCol = await legacyClient.query<{ data_type: string }>(`
      SELECT data_type
      FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name = 'listings'
        AND column_name = 'listed_date';
    `);
    expect(legacyCol.rows[0]?.data_type).toBe('timestamp with time zone');

    const legacyIdx = await legacyClient.query<{ indexname: string }>(`
      SELECT indexname FROM pg_indexes WHERE tablename = 'listings';
    `);
    const legacyIdxNames = legacyIdx.rows.map((r) => r.indexname);
    expect(legacyIdxNames).toContain('listings_listed_date_desc');
    expect(legacyIdxNames).toContain('listings_price_idx');
    expect(legacyIdxNames).toContain('listings_bedrooms_idx');
    expect(legacyIdxNames).toContain('listings_city_lower_idx');
    expect(legacyIdxNames).toContain('listings_description_trgm');

    const legacySearch = await executeSearch(legacyDb, { city: 'austin' });
    expect(legacySearch.ok).toBe(true);
    expect(legacySearch.listings[0]?.listedDate).toBe('2026-09-15T00:00:00.000Z');
  });

  it('normalizes Postgres TIMESTAMPTZ strings to UTC ISO-8601 and preserves invalid date strings in mapListingRow', () => {
    const baseRow = {
      internalId: '00000000-0000-0000-0000-000000000001',
      id: 'ROW-1',
      source: 'SEED',
      address: '1 Test St',
      city: 'Austin',
      state: 'TX',
      zip: '78701',
      price: 450000,
      bedrooms: 3,
      bathrooms: 2,
      sqft: 1500,
      latitude: 30.26,
      longitude: -97.74,
      status: 'active',
      description: 'Test',
      relevanceScore: 0.85,
    };

    expect(mapListingRow({ ...baseRow, listedDate: '2026-08-29 00:00:00+00' }).listedDate).toBe(
      '2026-08-29T00:00:00.000Z'
    );
    expect(mapListingRow({ ...baseRow, listedDate: 'not-a-valid-date' }).listedDate).toBe(
      'not-a-valid-date'
    );
  });

  it('idempotently seeds database without duplicating listings or throwing', async () => {
    await expect(seedDatabase(db)).resolves.not.toThrow();
    const res = await executeSearch(db, { pageSize: 100 });
    expect(res.ok).toBe(true);
    expect(res.pagination?.totalResults).toBe(24);
  });

  it('sorts deterministically across duplicate external IDs from different sources and enforces (id, source) uniqueness', async () => {
    const multiSourceDb = await createTestDatabase();

    await multiSourceDb
      .insert(listingsTable)
      .values([
        {
          id: 'DUP-001',
          source: 'MLS_B',
          address: '100 Main St',
          city: 'Reston',
          state: 'VA',
          zip: '20190',
          price: 500000,
          bedrooms: 3,
          bathrooms: 2.5,
          sqft: 1800,
          latitude: 38.9586,
          longitude: -77.357,
          location: { x: -77.357, y: 38.9586 },
          status: 'active',
          description: 'Duplicate external ID from MLS_B',
          listedDate: '2026-10-15T00:00:00.000Z',
        },
        {
          id: 'DUP-001',
          source: 'MLS_A',
          address: '100 Main St',
          city: 'Reston',
          state: 'VA',
          zip: '20190',
          price: 500000,
          bedrooms: 3,
          bathrooms: 2.5,
          sqft: 1800,
          latitude: 38.9586,
          longitude: -77.357,
          location: { x: -77.357, y: 38.9586 },
          status: 'active',
          description: 'Duplicate external ID from MLS_A',
          listedDate: '2026-10-15T00:00:00.000Z',
        },
      ])
      .onConflictDoNothing({ target: [listingsTable.id, listingsTable.source] });

    // Re-inserting (DUP-001, MLS_A) should be skipped cleanly
    await multiSourceDb
      .insert(listingsTable)
      .values({
        id: 'DUP-001',
        source: 'MLS_A',
        address: '100 Main St',
        city: 'Reston',
        price: 500000,
        bedrooms: 3,
        description: 'Should be skipped',
        listedDate: '2026-10-15T00:00:00.000Z',
      })
      .onConflictDoNothing({ target: [listingsTable.id, listingsTable.source] });

    const res = await executeSearch(multiSourceDb, { city: 'Reston', targetBudget: 500000 });
    expect(res.ok).toBe(true);
    expect(res.listings).toHaveLength(2);
    expect(res.listings[0].id).toBe('DUP-001');
    expect(res.listings[1].id).toBe('DUP-001');
    expect(res.listings[0].source).toBe('MLS_A');
    expect(res.listings[1].source).toBe('MLS_B');
    expect(res.listings[0].internalId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
    );
    expect(res.listings[1].internalId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
    );
    expect(res.listings[0].internalId).not.toBe(res.listings[1].internalId);
  });

  it('initializes and closes postgres database via createPostgresDatabase', async () => {
    const endSpy = vi.fn().mockResolvedValue(undefined);
    const querySpy = vi.fn().mockResolvedValue({ rows: [], rowCount: 0 });

    const poolSpy = vi.spyOn(pg, 'Pool').mockImplementation(() => {
      return {
        query: querySpy,
        end: endSpy,
        on: vi.fn(),
      } as unknown as pg.Pool;
    });

    const pgDb = await createPostgresDatabase('postgres://user:pass@localhost:5432/db');
    expect(pgDb.db).toBeDefined();
    expect(querySpy).toHaveBeenCalled();
    await pgDb.close();
    expect(endSpy).toHaveBeenCalled();

    poolSpy.mockRestore();
  });
});
