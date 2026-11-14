import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createTestDatabase, type AppDatabase } from '../db/client.js';
import {
  importSampleListings,
  resolveSampleListingsPath,
} from '../db/importSample.js';
import { executeSearch } from '../search/executeSearch.js';

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

  it('correctly maps multi-part addresses and allows searching across imported cities', async () => {
    // Check Springfield listings
    const springfield = await executeSearch(db, { city: 'Springfield' });
    expect(springfield.ok).toBe(true);
    expect(springfield.listings.length).toBeGreaterThan(0);
    const firstSpringfield = springfield.listings.find((l) => l.id === 'A1');
    expect(firstSpringfield).toBeDefined();
    expect(firstSpringfield?.address).toBe('123 Main St, Apt 4B, Springfield, VA 22150');
    expect(firstSpringfield?.price).toBe(450000);
    expect(firstSpringfield?.bedrooms).toBe(2);

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
    ];

    for (const inv of invalidRecords) {
      const invalidPath = path.join(tempDir, `invalid_rec_${Math.random()}.json`);
      fs.writeFileSync(invalidPath, JSON.stringify([inv]));
      await expect(importSampleListings(db, invalidPath)).rejects.toThrow(
        /Invalid listing record/
      );
    }
  });
});
