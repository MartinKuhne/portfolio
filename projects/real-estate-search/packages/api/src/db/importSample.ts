import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  createPostgresDatabase,
  createTestDatabase,
  insertListingsIdempotently,
  type AppDatabase,
} from './client.js';
import type { NewListingRecord } from './schema.js';
import { initApiTelemetry, logApi, SeverityNumber, shutdownApiTelemetry } from '../telemetry.js';

export interface SampleListingRecord {
  id: string;
  source?: string;
  address: string;
  city: string;
  state?: string;
  zip?: string;
  price: number;
  bedrooms: number;
  bathrooms?: number;
  sqft?: number;
  latitude?: number;
  longitude?: number;
  listedDate: string;
  status?: string;
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

function parseOptionalNonEmptyString(
  value: unknown,
  fieldName: string,
  index: number
): string | null {
  if (value === undefined) {
    return null;
  }
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(
      `Invalid listing record at index ${index}: optional field "${fieldName}" must be a non-empty string.`
    );
  }
  return value.trim();
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
      item.listedDate.trim() === '' ||
      Number.isNaN(Date.parse(item.listedDate))
    ) {
      throw new Error(
        `Invalid listing record at index ${i}: required fields (id, address, city, price, bedrooms, description, listedDate) are missing or invalid.`
      );
    }

    const source = parseOptionalNonEmptyString(item.source, 'source', i) ?? 'IMPORT';
    const state = parseOptionalNonEmptyString(item.state, 'state', i);
    const zip = parseOptionalNonEmptyString(item.zip, 'zip', i);
    const status = parseOptionalNonEmptyString(item.status, 'status', i) ?? 'active';

    let bathrooms: number | null = null;
    if (item.bathrooms !== undefined) {
      if (typeof item.bathrooms !== 'number' || !Number.isFinite(item.bathrooms) || item.bathrooms < 0) {
        throw new Error(
          `Invalid listing record at index ${i}: optional field "bathrooms" must be a finite number >= 0.`
        );
      }
      bathrooms = item.bathrooms;
    }

    let sqft: number | null = null;
    if (item.sqft !== undefined) {
      if (typeof item.sqft !== 'number' || !Number.isInteger(item.sqft) || item.sqft < 0) {
        throw new Error(
          `Invalid listing record at index ${i}: optional field "sqft" must be a non-negative integer.`
        );
      }
      sqft = item.sqft;
    }

    const hasLat = item.latitude !== undefined;
    const hasLon = item.longitude !== undefined;
    if (hasLat !== hasLon) {
      throw new Error(
        `Invalid listing record at index ${i}: latitude and longitude must both be provided or both omitted.`
      );
    }

    let latitude: number | null = null;
    let longitude: number | null = null;
    let location: { x: number; y: number } | null = null;
    if (hasLat && hasLon) {
      if (
        typeof item.latitude !== 'number' ||
        !Number.isFinite(item.latitude) ||
        item.latitude < -90 ||
        item.latitude > 90
      ) {
        throw new Error(
          `Invalid listing record at index ${i}: latitude must be a finite number between -90 and 90.`
        );
      }
      if (
        typeof item.longitude !== 'number' ||
        !Number.isFinite(item.longitude) ||
        item.longitude < -180 ||
        item.longitude > 180
      ) {
        throw new Error(
          `Invalid listing record at index ${i}: longitude must be a finite number between -180 and 180.`
        );
      }
      latitude = item.latitude;
      longitude = item.longitude;
      location = { x: longitude, y: latitude };
    }

    const formattedAddress =
      state !== null && zip !== null
        ? `${item.address.trim()}, ${item.city.trim()}, ${state} ${zip}`
        : item.address.trim();

    recordsToInsert.push({
      id: item.id.trim(),
      source,
      address: formattedAddress,
      city: item.city.trim(),
      state,
      zip,
      price: Math.round(item.price),
      bedrooms: Math.round(item.bedrooms),
      bathrooms,
      sqft,
      latitude,
      longitude,
      location,
      status,
      description: item.description,
      listedDate: new Date(item.listedDate.trim()).toISOString(),
    });
  }

  if (recordsToInsert.length === 0) {
    return 0;
  }

  const inserted = await insertListingsIdempotently(db, recordsToInsert);

  return inserted.length;
}

export interface RunImportCliOptions {
  explicitPath?: string;
  databaseUrl?: string;
}

/**
 * Runs the sample-listings CLI import flow with initialized OpenTelemetry logging.
 */
export async function runImportCli(options: RunImportCliOptions = {}): Promise<number> {
  const { explicitPath, databaseUrl } = options;
  let db: AppDatabase;
  let closeDb: (() => Promise<void>) | undefined;

  initApiTelemetry();
  try {
    if (databaseUrl) {
      const pgDb = await createPostgresDatabase(databaseUrl);
      db = pgDb.db;
      closeDb = pgDb.close;
    } else {
      db = await createTestDatabase();
    }

    const count = await importSampleListings(db, explicitPath);
    logApi(SeverityNumber.INFO, 'INFO', 'db.import.completed', {
      'db.system': 'postgresql',
      'db.target': databaseUrl ? 'postgres' : 'ephemeral-pglite',
      'import.count': count,
      ...(explicitPath ? { 'import.path': explicitPath } : {}),
    });
    return count;
  } finally {
    try {
      if (closeDb) {
        await closeDb();
      }
    } finally {
      await shutdownApiTelemetry();
    }
  }
}

export const isMain =
  Boolean(process.argv[1]) &&
  fileURLToPath(import.meta.url).toLowerCase() === path.resolve(process.argv[1]).toLowerCase();

/* v8 ignore start */
if (isMain) {
  runImportCli({
    explicitPath: process.argv[2],
    databaseUrl: process.env.DATABASE_URL,
  }).catch((err) => {
    console.error('Failed to import sample listings:', err);
    process.exit(1);
  });
}
/* v8 ignore stop */
