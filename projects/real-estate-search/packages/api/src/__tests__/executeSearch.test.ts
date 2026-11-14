import { beforeAll, describe, expect, it, vi } from 'vitest';
import pg from 'pg';
import { computeRelevanceScore, REFERENCE_DATE_ISO } from '@real-estate/shared';
import {
  createPostgresDatabase,
  createTestDatabase,
  type AppDatabase,
} from '../db/client.js';
import { listingsTable } from '../db/schema.js';
import { seedDatabase } from '../db/seed.js';
import { executeSearch } from '../search/executeSearch.js';

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
  });

  it('idempotently seeds database without duplicating listings or throwing', async () => {
    await expect(seedDatabase(db)).resolves.not.toThrow();
    const res = await executeSearch(db, { pageSize: 100 });
    expect(res.ok).toBe(true);
    expect(res.pagination?.totalResults).toBe(24);
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
