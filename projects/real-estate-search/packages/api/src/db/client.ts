import { PGlite } from '@electric-sql/pglite';
import { sql } from 'drizzle-orm';
import { drizzle as drizzlePg, type NodePgDatabase } from 'drizzle-orm/node-postgres';
import { drizzle as drizzlePglite, type PgliteDatabase } from 'drizzle-orm/pglite';
import pg from 'pg';
import * as schema from './schema.js';

export type AppDatabase = PgliteDatabase<typeof schema> | NodePgDatabase<typeof schema>;

export async function ensureSchema(db: AppDatabase): Promise<void> {
  await db.execute(sql.raw(`
    CREATE TABLE IF NOT EXISTS listings (
      id TEXT PRIMARY KEY,
      address TEXT NOT NULL,
      city TEXT NOT NULL,
      price INTEGER NOT NULL,
      bedrooms INTEGER NOT NULL,
      description TEXT NOT NULL,
      listed_date TEXT NOT NULL
    );
  `));
}

export async function createTestDatabase(): Promise<AppDatabase> {
  const client = new PGlite();
  const db = drizzlePglite(client, { schema });
  await ensureSchema(db);
  return db;
}

export async function createPostgresDatabase(
  connectionString: string
): Promise<{ db: AppDatabase; close: () => Promise<void> }> {
  const pool = new pg.Pool({ connectionString });
  const db = drizzlePg(pool, { schema });
  await ensureSchema(db);
  return {
    db,
    close: async () => {
      await pool.end();
    },
  };
}
