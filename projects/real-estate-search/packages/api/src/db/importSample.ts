import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { AppDatabase } from './client.js';
import { createPostgresDatabase, createTestDatabase } from './client.js';
import { listingsTable, type NewListingRecord } from './schema.js';

export interface SampleListingRecord {
  id: string;
  address: string;
  city: string;
  state?: string;
  zip?: string;
  price: number;
  bedrooms: number;
  listedDate: string;
  description: string;
  [key: string]: unknown;
}

export function resolveSampleListingsPath(explicitPath?: string): string {
  if (explicitPath) {
    return path.resolve(explicitPath);
  }

  const candidates = [
    path.resolve(process.cwd(), 'sample_listings.json'),
    path.resolve(process.cwd(), '../../sample_listings.json'),
    fileURLToPath(new URL('../../../sample_listings.json', import.meta.url)),
    fileURLToPath(new URL('../../../../sample_listings.json', import.meta.url)),
    '/app/sample_listings.json',
  ];

  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }

  return path.resolve(process.cwd(), 'sample_listings.json');
}

export async function importSampleListings(
  db: AppDatabase,
  filePath?: string
): Promise<number> {
  const resolvedPath = resolveSampleListingsPath(filePath);

  if (!fs.existsSync(resolvedPath)) {
    throw new Error(`Sample listings file not found at: ${resolvedPath}`);
  }

  let rawContent: string;
  try {
    rawContent = fs.readFileSync(resolvedPath, 'utf-8');
  } catch (err) {
    /* v8 ignore start */
    throw new Error(
      `Failed to read sample listings file at ${resolvedPath}: ${err instanceof Error ? err.message : String(err)}`
    );
    /* v8 ignore stop */
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(rawContent);
  } catch (err) {
    throw new Error(
      `Sample listings file contains invalid JSON: ${err instanceof Error ? err.message : String(err)}`
    );
  }

  if (!Array.isArray(parsed)) {
    throw new Error('Sample listings file must contain a JSON array of listing objects.');
  }

  const recordsToInsert: NewListingRecord[] = [];

  for (let i = 0; i < parsed.length; i++) {
    const item = parsed[i] as Partial<SampleListingRecord> | null | undefined;

    if (
      !item ||
      typeof item !== 'object' ||
      typeof item.id !== 'string' ||
      item.id.trim() === '' ||
      typeof item.address !== 'string' ||
      item.address.trim() === '' ||
      typeof item.city !== 'string' ||
      item.city.trim() === '' ||
      typeof item.price !== 'number' ||
      !Number.isFinite(item.price) ||
      typeof item.bedrooms !== 'number' ||
      !Number.isFinite(item.bedrooms) ||
      typeof item.description !== 'string' ||
      typeof item.listedDate !== 'string' ||
      item.listedDate.trim() === ''
    ) {
      throw new Error(
        `Invalid listing record at index ${i}: required fields (id, address, city, price, bedrooms, description, listedDate) are missing or invalid.`
      );
    }

    const formattedAddress =
      typeof item.state === 'string' &&
      item.state.trim() !== '' &&
      typeof item.zip === 'string' &&
      item.zip.trim() !== ''
        ? `${item.address.trim()}, ${item.city.trim()}, ${item.state.trim()} ${item.zip.trim()}`
        : item.address.trim();

    recordsToInsert.push({
      id: item.id.trim(),
      address: formattedAddress,
      city: item.city.trim(),
      price: Math.round(item.price),
      bedrooms: Math.round(item.bedrooms),
      description: item.description,
      listedDate: item.listedDate.trim(),
    });
  }

  if (recordsToInsert.length === 0) {
    return 0;
  }

  const inserted = await db
    .insert(listingsTable)
    .values(recordsToInsert)
    .onConflictDoNothing()
    .returning();

  return inserted.length;
}

export const isMain =
  Boolean(process.argv[1]) &&
  fileURLToPath(import.meta.url).toLowerCase() === path.resolve(process.argv[1]).toLowerCase();

/* v8 ignore start */
if (isMain) {
  const explicitPath = process.argv[2];
  const databaseUrl = process.env.DATABASE_URL;
  let db: AppDatabase;
  let closeDb: (() => Promise<void>) | undefined;

  const run = async () => {
    if (databaseUrl) {
      const pgDb = await createPostgresDatabase(databaseUrl);
      db = pgDb.db;
      closeDb = pgDb.close;
    } else {
      db = await createTestDatabase();
    }

    const count = await importSampleListings(db, explicitPath);
    console.log(`Successfully imported ${count} sample listings.`);
    if (closeDb) {
      await closeDb();
    }
  };

  run().catch((err) => {
    console.error('Failed to import sample listings:', err);
    process.exit(1);
  });
}
/* v8 ignore stop */
