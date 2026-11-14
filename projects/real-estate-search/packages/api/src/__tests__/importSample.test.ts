import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { PGlite } from '@electric-sql/pglite';
import { sql } from 'drizzle-orm';
import { drizzle as drizzlePglite } from 'drizzle-orm/pglite';
import pg from 'pg';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import {
  createPostgresDatabase,
  createTestDatabase,
  ensureSchema,
  type AppDatabase,
} from '../db/client.js';
import * as clientModule from '../db/client.js';
import {
  importSampleListings,
  resolveSampleListingsPath,
  runImportCli,
} from '../db/importSample.js';
import * as schema from '../db/schema.js';
import { listingsTable } from '../db/schema.js';
import { executeSearch } from '../search/executeSearch.js';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

describe('importSampleListings and resolveSampleListingsPath', () => {
  let db: AppDatabase;
  let tempDir: string;

  beforeAll(async () => {
    db = await createTestDatabase();
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'import-test-'));
  });

  afterAll(() => {
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('resolves explicit paths directly and finds repository sample_listings.json by default', () => {
    const explicit = resolveSampleListingsPath('some/custom/path.json');
    expect(explicit).toBe(path.resolve('some/custom/path.json'));

    const resolvedDefault = resolveSampleListingsPath();
    expect(fs.existsSync(resolvedDefault)).toBe(true);
    expect(resolvedDefault).toContain('sample_listings.json');
  });

  it('imports the real sample_listings.json dataset and maps all 12 listings into PGlite', async () => {
    const importedCount = await importSampleListings(db);
    expect(importedCount).toBe(12);

    const searchRes = await executeSearch(db, { pageSize: 50 });
    expect(searchRes.ok).toBe(true);
    expect(searchRes.pagination?.totalResults).toBe(12);
  });

  it('is completely idempotent when re-importing the same dataset (returns 0 new records)', async () => {
    const secondImportCount = await importSampleListings(db);
    expect(secondImportCount).toBe(0);

    const searchRes = await executeSearch(db, { pageSize: 50 });
    expect(searchRes.ok).toBe(true);
    expect(searchRes.pagination?.totalResults).toBe(12);
  });

  it('correctly maps multi-part addresses, all sample fields, and location POINT across imported cities', async () => {
    // Check Springfield listings
    const springfield = await executeSearch(db, { city: 'Springfield' });
    expect(springfield.ok).toBe(true);
    expect(springfield.listings.length).toBeGreaterThan(0);
    const firstSpringfield = springfield.listings.find((l) => l.id === 'A1');
    expect(firstSpringfield).toBeDefined();
    expect(firstSpringfield?.internalId).toMatch(UUID_REGEX);
    expect(firstSpringfield?.source).toBe('MLS_A');
    expect(firstSpringfield?.address).toBe('123 Main St, Apt 4B, Springfield, VA 22150');
    expect(firstSpringfield?.state).toBe('VA');
    expect(firstSpringfield?.zip).toBe('22150');
    expect(firstSpringfield?.price).toBe(450000);
    expect(firstSpringfield?.bedrooms).toBe(2);
    expect(firstSpringfield?.bathrooms).toBe(1.5);
    expect(firstSpringfield?.sqft).toBe(980);
    expect(firstSpringfield?.latitude).toBe(38.7893);
    expect(firstSpringfield?.longitude).toBe(-77.1873);
    expect(firstSpringfield?.status).toBe('active');
    expect(firstSpringfield?.listedDate).toBe('2026-08-29T00:00:00.000Z');
    expect(firstSpringfield?.description).toBe(
      'Bright top-floor condo near shops and transit. Pet friendly.'
    );

    const chantilly = await executeSearch(db, { city: 'Chantilly' });
    expect(chantilly.ok).toBe(true);
    expect(chantilly.listings[0]?.id).toBe('A7');
    expect(chantilly.listings[0]?.status).toBe('pending');
    expect(chantilly.listings[0]?.bathrooms).toBe(2.5);
    expect(chantilly.listings[0]?.sqft).toBe(1950);

    // Verify PostgreSQL POINT(longitude, latitude) storage directly in listingsTable
    const dbRows = await db.select().from(listingsTable);
    const a1Row = dbRows.find((r) => r.id === 'A1' && r.source === 'MLS_A');
    expect(a1Row?.location).toEqual({ x: -77.1873, y: 38.7893 });

    // Check Fairfax listings
    const fairfax = await executeSearch(db, { city: 'Fairfax' });
    expect(fairfax.ok).toBe(true);
    expect(fairfax.listings.length).toBeGreaterThan(0);
    for (const listing of fairfax.listings) {
      expect(listing.city.toLowerCase()).toBe('fairfax');
    }

    // Check Reston listings
    const reston = await executeSearch(db, { city: 'Reston' });
    expect(reston.ok).toBe(true);
    expect(reston.listings.length).toBeGreaterThan(0);
    for (const listing of reston.listings) {
      expect(listing.city.toLowerCase()).toBe('reston');
    }

    // Check Vienna listings
    const vienna = await executeSearch(db, { city: 'Vienna' });
    expect(vienna.ok).toBe(true);
    expect(vienna.listings.length).toBeGreaterThan(0);
    for (const listing of vienna.listings) {
      expect(listing.city.toLowerCase()).toBe('vienna');
    }
  });

  it('handles empty listing arrays by returning 0 without errors', async () => {
    const emptyFile = path.join(tempDir, 'empty.json');
    fs.writeFileSync(emptyFile, JSON.stringify([]));

    const count = await importSampleListings(db, emptyFile);
    expect(count).toBe(0);
  });

  it('formats address without state/zip when state and zip are omitted', async () => {
    const recordsWithoutStateZip = [
      {
        id: 'NO-STATE-ZIP-1',
        address: '789 Pine Rd',
        city: 'Alexandria',
        price: 300000,
        bedrooms: 2,
        listedDate: '2026-09-01',
        description: 'Cozy condo without zip info',
      },
    ];
    const file = path.join(tempDir, 'no-state-zip.json');
    fs.writeFileSync(file, JSON.stringify(recordsWithoutStateZip));

    const count = await importSampleListings(db, file);
    expect(count).toBe(1);

    const searchRes = await executeSearch(db, { city: 'Alexandria' });
    expect(searchRes.ok).toBe(true);
    expect(searchRes.listings[0].address).toBe('789 Pine Rd');
  });

  it('throws an error if the sample listings file does not exist', async () => {
    const nonExistentPath = path.join(tempDir, 'non_existent_file.json');
    await expect(importSampleListings(db, nonExistentPath)).rejects.toThrow(
      /Sample listings file not found/
    );
  });

  it('throws an error if the sample listings file contains invalid JSON syntax', async () => {
    const invalidJsonPath = path.join(tempDir, 'invalid.json');
    fs.writeFileSync(invalidJsonPath, '{ invalid json syntax');
    await expect(importSampleListings(db, invalidJsonPath)).rejects.toThrow(
      /contains invalid JSON/
    );
  });

  it('throws an error if the JSON content is not an array', async () => {
    const notArrayPath = path.join(tempDir, 'not_array.json');
    fs.writeFileSync(notArrayPath, JSON.stringify({ id: '123' }));
    await expect(importSampleListings(db, notArrayPath)).rejects.toThrow(
      /must contain a JSON array/
    );
  });

  it('throws an error if any listing record is missing required fields', async () => {
    const invalidRecords = [
      null,
      { id: '1' },
      { id: '2', address: '123 St', city: 'City', price: 'not-number' },
      { id: '3', address: '123 St', city: 'City', price: 100, bedrooms: 'three' },
      { id: '4', address: '123 St', city: 'City', price: 100, bedrooms: 2, description: 123 },
      { id: '5', address: '123 St', city: 'City', price: 100, bedrooms: 2, description: 'desc', listedDate: 123 },
      { id: '6', address: '123 St', city: 'City', price: 100, bedrooms: 2, description: 'desc', listedDate: 'not-a-date' },
    ];

    for (const inv of invalidRecords) {
      const invalidPath = path.join(tempDir, `invalid_rec_${Math.random()}.json`);
      fs.writeFileSync(invalidPath, JSON.stringify([inv]));
      await expect(importSampleListings(db, invalidPath)).rejects.toThrow(
        /Invalid listing record/
      );
    }
  });

  it('rejects invalid optional fields (source, state, zip, status, bathrooms, sqft, latitude, longitude)', async () => {
    const baseValid = {
      id: 'OPT-TEST-1',
      address: '10 Test Way',
      city: 'McLean',
      price: 500000,
      bedrooms: 3,
      listedDate: '2026-09-01',
      description: 'Valid base record',
    };

    const invalidOptionalCases = [
      { ...baseValid, source: '' },
      { ...baseValid, source: 123 },
      { ...baseValid, state: '   ' },
      { ...baseValid, zip: 22101 },
      { ...baseValid, status: '' },
      { ...baseValid, bathrooms: -1 },
      { ...baseValid, bathrooms: 'two' },
      { ...baseValid, sqft: -100 },
      { ...baseValid, sqft: 1250.5 },
      { ...baseValid, sqft: '1250' },
      { ...baseValid, latitude: 38.9 }, // missing longitude
      { ...baseValid, longitude: -77.1 }, // missing latitude
      { ...baseValid, latitude: 95, longitude: -77.1 },
      { ...baseValid, latitude: -91, longitude: -77.1 },
      { ...baseValid, latitude: '38.9', longitude: -77.1 },
      { ...baseValid, latitude: 38.9, longitude: 185 },
      { ...baseValid, latitude: 38.9, longitude: -181 },
      { ...baseValid, latitude: 38.9, longitude: 'bad' },
    ];

    for (const invalidCase of invalidOptionalCases) {
      const file = path.join(tempDir, `invalid_opt_${Math.random()}.json`);
      fs.writeFileSync(file, JSON.stringify([invalidCase]));
      await expect(importSampleListings(db, file)).rejects.toThrow(/Invalid listing record/);
    }
  });

  it('enforces (id, source) composite uniqueness while allowing the same external id across distinct sources', async () => {
    const isolatedDb = await createTestDatabase();

    const feedA = [
      {
        id: 'SHARED-100',
        source: 'MLS_A',
        address: '100 Multi Feed Rd',
        city: 'Arlington',
        state: 'VA',
        zip: '22201',
        price: 600000,
        bedrooms: 3,
        bathrooms: 2,
        sqft: 1500,
        latitude: 38.8816,
        longitude: -77.091,
        listedDate: '2026-09-10',
        status: 'active',
        description: 'Feed A listing',
      },
    ];
    const feedB = [
      {
        ...feedA[0],
        source: 'MLS_B',
        price: 605000,
        description: 'Feed B listing',
      },
    ];

    const fileA = path.join(tempDir, 'feedA.json');
    const fileB = path.join(tempDir, 'feedB.json');
    fs.writeFileSync(fileA, JSON.stringify(feedA));
    fs.writeFileSync(fileB, JSON.stringify(feedB));

    expect(await importSampleListings(isolatedDb, fileA)).toBe(1);
    expect(await importSampleListings(isolatedDb, fileA)).toBe(0);
    expect(await importSampleListings(isolatedDb, fileB)).toBe(1);

    const searchRes = await executeSearch(isolatedDb, { city: 'Arlington' });
    expect(searchRes.ok).toBe(true);
    expect(searchRes.listings).toHaveLength(2);
    expect(searchRes.listings[0].internalId).toMatch(UUID_REGEX);
    expect(searchRes.listings[1].internalId).toMatch(UUID_REGEX);
    expect(searchRes.listings[0].internalId).not.toBe(searchRes.listings[1].internalId);
  });

  it('idempotently migrates a legacy id-primary-key listings table inside ensureSchema', async () => {
    const client = new PGlite();
    const legacyDb = drizzlePglite(client, { schema });

    await legacyDb.execute(sql.raw(`
      CREATE TABLE listings (
        id TEXT PRIMARY KEY,
        address TEXT NOT NULL,
        city TEXT NOT NULL,
        price INTEGER NOT NULL,
        bedrooms INTEGER NOT NULL,
        description TEXT NOT NULL,
        listed_date TEXT NOT NULL
      );
    `));

    await legacyDb.execute(sql.raw(`
      INSERT INTO listings (id, address, city, price, bedrooms, description, listed_date) VALUES
      ('LST-001', '101 Congress Ave', 'Austin', 450000, 3, 'Legacy seed row', '2026-10-20T00:00:00.000Z'),
      ('A1', '123 Main St, Apt 4B, Springfield, VA 22150', 'Springfield', 450000, 2, 'Legacy MLS_A row', '2026-08-29T00:00:00.000Z'),
      ('B7', '123 Main Street, Unit 4B, Springfield, VA 22150', 'Springfield', 452000, 2, 'Legacy MLS_B row', '2026-08-27T00:00:00.000Z');
    `));

    await ensureSchema(legacyDb);
    // Run a second time to verify idempotency
    await ensureSchema(legacyDb);

    const pkResult = await client.query<{ attname: string }>(`
      SELECT a.attname
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
      JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(c.conkey)
      WHERE t.relname = 'listings' AND c.contype = 'p';
    `);
    expect(pkResult.rows.map((r) => r.attname)).toEqual(['internal_id']);

    const indexResult = await client.query<{ indexname: string }>(`
      SELECT indexname FROM pg_indexes WHERE tablename = 'listings';
    `);
    const indexNames = indexResult.rows.map((r) => r.indexname);
    expect(indexNames).toContain('listings_id_source_unique');
    expect(indexNames).toContain('listings_location_gist');

    const migratedRows = await legacyDb.select().from(listingsTable);
    expect(migratedRows).toHaveLength(3);
    const seedRow = migratedRows.find((r) => r.id === 'LST-001');
    const a1Row = migratedRows.find((r) => r.id === 'A1');
    const b7Row = migratedRows.find((r) => r.id === 'B7');
    expect(seedRow?.source).toBe('SEED');
    expect(seedRow?.internalId).toMatch(UUID_REGEX);
    expect(a1Row?.source).toBe('MLS_A');
    expect(a1Row?.internalId).toMatch(UUID_REGEX);
    expect(b7Row?.source).toBe('MLS_B');
    expect(b7Row?.internalId).toMatch(UUID_REGEX);

    // Same external id with a different source now inserts cleanly after PK migration
    await legacyDb
      .insert(listingsTable)
      .values({
        id: 'A1',
        source: 'MLS_B',
        address: '123 Main St',
        city: 'Springfield',
        price: 451000,
        bedrooms: 2,
        description: 'Cross-source row',
        listedDate: '2026-08-30T00:00:00.000Z',
      })
      .onConflictDoNothing({ target: [listingsTable.id, listingsTable.source] });

    const afterInsertRows = await legacyDb.select().from(listingsTable);
    expect(afterInsertRows).toHaveLength(4);
  });

  it('normalizes date-only listedDate strings to UTC ISO-8601 timestamps on insert', async () => {
    const records = [
      {
        id: 'DATE-NORM-1',
        address: '456 Oak Ave',
        city: 'Herndon',
        price: 400000,
        bedrooms: 3,
        listedDate: '2026-09-01',
        description: 'Normalized UTC date listing',
      },
    ];
    const file = path.join(tempDir, 'date-norm.json');
    fs.writeFileSync(file, JSON.stringify(records));

    const count = await importSampleListings(db, file);
    expect(count).toBe(1);

    const searchRes = await executeSearch(db, { city: 'Herndon' });
    expect(searchRes.ok).toBe(true);
    expect(searchRes.listings[0]?.listedDate).toBe('2026-09-01T00:00:00.000Z');
  });

  it('closes pg.Pool cleanly if ensureSchema fails in createPostgresDatabase', async () => {
    vi.spyOn(pg.Pool.prototype, 'query').mockRejectedValueOnce(new Error('Schema init failed'));
    const endSpy = vi.spyOn(pg.Pool.prototype, 'end').mockResolvedValueOnce(undefined as never);

    await expect(
      createPostgresDatabase('postgres://user:pass@localhost:5432/broken_db')
    ).rejects.toThrow('Schema init failed');
    expect(endSpy).toHaveBeenCalledTimes(1);
  });

  it('runImportCli emits structured OTel JSON log for ephemeral PGlite mode', async () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});

    const count = await runImportCli();
    expect(count).toBe(12);

    const logEntries = consoleSpy.mock.calls.map((call) => JSON.parse(String(call[0])));
    const importLog = logEntries.find((entry) => entry.body === 'db.import.completed');
    expect(importLog).toBeDefined();
    expect(importLog.service).toBe('real-estate-api');
    expect(importLog.severityText).toBe('INFO');
    expect(importLog.attributes['db.system']).toBe('postgresql');
    expect(importLog.attributes['db.target']).toBe('ephemeral-pglite');
    expect(importLog.attributes['import.count']).toBe(12);
    expect(importLog.attributes['import.path']).toBeUndefined();
  });

  it('runImportCli emits structured OTel JSON log and closes DB for Postgres mode', async () => {
    const isolatedDb = await createTestDatabase();
    const mockClose = vi.fn().mockResolvedValue(undefined);
    vi.spyOn(clientModule, 'createPostgresDatabase').mockResolvedValueOnce({
      db: isolatedDb,
      close: mockClose,
    });

    const explicitFile = path.join(tempDir, 'cli-postgres.json');
    fs.writeFileSync(
      explicitFile,
      JSON.stringify([
        {
          id: 'CLI-PG-1',
          address: '500 Market St',
          city: 'Reston',
          price: 550000,
          bedrooms: 3,
          listedDate: '2026-09-15',
          description: 'CLI Postgres import test listing',
        },
      ])
    );

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => {});

    const count = await runImportCli({
      explicitPath: explicitFile,
      databaseUrl: 'postgres://user:pass@localhost:5432/mock_db',
    });
    expect(count).toBe(1);
    expect(mockClose).toHaveBeenCalledTimes(1);

    const logEntries = consoleSpy.mock.calls.map((call) => JSON.parse(String(call[0])));
    const importLog = logEntries.find((entry) => entry.body === 'db.import.completed');
    expect(importLog).toBeDefined();
    expect(importLog.service).toBe('real-estate-api');
    expect(importLog.severityText).toBe('INFO');
    expect(importLog.attributes['db.system']).toBe('postgresql');
    expect(importLog.attributes['db.target']).toBe('postgres');
    expect(importLog.attributes['import.count']).toBe(1);
    expect(importLog.attributes['import.path']).toBe(explicitFile);
  });
});
