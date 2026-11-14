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
    DO $$
    DECLARE
      pk_name text;
      pk_on_internal boolean := false;
    BEGIN
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS internal_id UUID DEFAULT gen_random_uuid();
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'SEED';
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS state TEXT;
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS zip TEXT;
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS bathrooms DOUBLE PRECISION;
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS sqft INTEGER;
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS location POINT;
      ALTER TABLE listings ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'active';

      UPDATE listings SET internal_id = gen_random_uuid() WHERE internal_id IS NULL;
      ALTER TABLE listings ALTER COLUMN internal_id SET DEFAULT gen_random_uuid();
      ALTER TABLE listings ALTER COLUMN internal_id SET NOT NULL;

      SELECT c.conname,
             (array_agg(a.attname::text ORDER BY k.ord) = ARRAY['internal_id'])
      INTO pk_name, pk_on_internal
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
      JOIN pg_namespace n ON n.oid = t.relnamespace
      CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
      WHERE t.relname = 'listings'
        AND n.nspname = current_schema()
        AND c.contype = 'p'
      GROUP BY c.conname;

      IF pk_name IS NULL THEN
        ALTER TABLE listings ADD PRIMARY KEY (internal_id);
      ELSIF NOT pk_on_internal THEN
        EXECUTE format('ALTER TABLE listings DROP CONSTRAINT %I', pk_name);
        ALTER TABLE listings ADD PRIMARY KEY (internal_id);
        UPDATE listings SET source = 'MLS_A' WHERE source = 'SEED' AND id LIKE 'A%';
        UPDATE listings SET source = 'MLS_B' WHERE source = 'SEED' AND id LIKE 'B%';
      END IF;

      IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'listings'
          AND column_name = 'listed_date'
          AND data_type <> 'timestamp with time zone'
      ) THEN
        ALTER TABLE listings
          ALTER COLUMN listed_date TYPE TIMESTAMPTZ
          USING listed_date::timestamptz;
      END IF;
    END $$;
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
 * Inserts listing records idempotently by `(id, source)` while backfilling nullable
 * metadata and geolocation columns for rows migrated from an earlier schema version.
 */
export async function insertOrBackfillListings(
  db: AppDatabase,
  records: schema.NewListingRecord[]
): Promise<schema.ListingRecord[]> {
  if (records.length === 0) {
    return [];
  }

  return db
    .insert(schema.listingsTable)
    .values(records)
    .onConflictDoUpdate({
      target: [schema.listingsTable.id, schema.listingsTable.source],
      set: {
        state: sql`COALESCE(${schema.listingsTable.state}, excluded.state)`,
        zip: sql`COALESCE(${schema.listingsTable.zip}, excluded.zip)`,
        bathrooms: sql`COALESCE(${schema.listingsTable.bathrooms}, excluded.bathrooms)`,
        sqft: sql`COALESCE(${schema.listingsTable.sqft}, excluded.sqft)`,
        latitude: sql`COALESCE(${schema.listingsTable.latitude}, excluded.latitude)`,
        longitude: sql`COALESCE(${schema.listingsTable.longitude}, excluded.longitude)`,
        location: sql`COALESCE(${schema.listingsTable.location}, excluded.location)`,
      },
      setWhere: sql`(${schema.listingsTable.state} IS NULL AND excluded.state IS NOT NULL)
        OR (${schema.listingsTable.zip} IS NULL AND excluded.zip IS NOT NULL)
        OR (${schema.listingsTable.bathrooms} IS NULL AND excluded.bathrooms IS NOT NULL)
        OR (${schema.listingsTable.sqft} IS NULL AND excluded.sqft IS NOT NULL)
        OR (${schema.listingsTable.latitude} IS NULL AND excluded.latitude IS NOT NULL)
        OR (${schema.listingsTable.longitude} IS NULL AND excluded.longitude IS NOT NULL)
        OR (${schema.listingsTable.location} IS NULL AND excluded.location IS NOT NULL)`,
    })
    .returning();
}
