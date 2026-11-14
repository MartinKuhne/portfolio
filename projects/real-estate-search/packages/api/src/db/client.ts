import { PGlite } from '@electric-sql/pglite';
import { pg_trgm } from '@electric-sql/pglite/contrib/pg_trgm';
import { sql } from 'drizzle-orm';
import { drizzle as drizzlePg, type NodePgDatabase } from 'drizzle-orm/node-postgres';
import { drizzle as drizzlePglite, type PgliteDatabase } from 'drizzle-orm/pglite';
import pg from 'pg';
import * as schema from './schema.js';

export type AppDatabase = PgliteDatabase<typeof schema> | NodePgDatabase<typeof schema>;

export async function ensureSchema(db: AppDatabase): Promise<void> {
  await db.execute(sql.raw(`
    CREATE TABLE IF NOT EXISTS listings (
      internal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id TEXT NOT NULL,
      source TEXT NOT NULL DEFAULT 'SEED',
      address TEXT NOT NULL,
      city TEXT NOT NULL,
      state TEXT,
      zip TEXT,
      price INTEGER NOT NULL,
      bedrooms INTEGER NOT NULL,
      bathrooms DOUBLE PRECISION,
      sqft INTEGER,
      latitude DOUBLE PRECISION,
      longitude DOUBLE PRECISION,
      location POINT,
      listed_date TIMESTAMPTZ NOT NULL,
      status TEXT NOT NULL DEFAULT 'active',
      description TEXT NOT NULL
    );
  `));

  await db.execute(sql.raw(`
    CREATE UNIQUE INDEX IF NOT EXISTS listings_id_source_unique ON listings (id, source);
  `));

  await db.execute(sql.raw(`
    CREATE INDEX IF NOT EXISTS listings_location_gist ON listings USING gist (location);
  `));

  await db.execute(sql.raw(`
    CREATE INDEX IF NOT EXISTS listings_listed_date_desc ON listings (listed_date DESC);
  `));

  await db.execute(sql.raw(`
    CREATE INDEX IF NOT EXISTS listings_price_idx ON listings (price);
  `));

  await db.execute(sql.raw(`
    CREATE INDEX IF NOT EXISTS listings_bedrooms_idx ON listings (bedrooms);
  `));

  await db.execute(sql.raw(`
    CREATE INDEX IF NOT EXISTS listings_city_lower_idx ON listings (LOWER(city));
  `));

  await db.execute(sql.raw(`
    DO $$
    BEGIN
      IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_trgm') THEN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
        CREATE INDEX IF NOT EXISTS listings_description_trgm
          ON listings USING gin (description gin_trgm_ops);
      END IF;
    END $$;
  `));
}

export async function createTestDatabase(): Promise<AppDatabase> {
  const client = new PGlite({ extensions: { pg_trgm } });
  const db = drizzlePglite(client, { schema });
  await ensureSchema(db);
  return db;
}

export async function createPostgresDatabase(
  connectionString: string
): Promise<{ db: AppDatabase; close: () => Promise<void> }> {
  const pool = new pg.Pool({ connectionString });
  const db = drizzlePg(pool, { schema });
  try {
    await ensureSchema(db);
  } catch (err) {
    await pool.end();
    throw err;
  }
  return {
    db,
    close: async () => {
      await pool.end();
    },
  };
}

/**
 * Inserts listing records idempotently by `(id, source)`, skipping rows that already exist.
 */
export async function insertListingsIdempotently(
  db: AppDatabase,
  records: schema.NewListingRecord[]
): Promise<schema.ListingRecord[]> {
  if (records.length === 0) {
    return [];
  }

  return db
    .insert(schema.listingsTable)
    .values(records)
    .onConflictDoNothing({ target: [schema.listingsTable.id, schema.listingsTable.source] })
    .returning();
}
