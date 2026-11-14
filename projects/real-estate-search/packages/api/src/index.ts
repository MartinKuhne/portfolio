import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createPostgresDatabase, createTestDatabase, type AppDatabase } from './db/client.js';
import { seedDatabase } from './db/seed.js';
import { createGraphqlApp } from './graphql/server.js';

export { createGraphqlApp } from './graphql/server.js';
export { executeSearch } from './search/executeSearch.js';
export * from './db/client.js';
export * from './db/schema.js';
export * from './db/seed.js';

export interface StartServerOptions {
  port?: number;
  databaseUrl?: string;
}

export interface RunningServer {
  server: http.Server;
  port: number;
  db: AppDatabase;
  stop: () => Promise<void>;
}

export async function startServer(options: StartServerOptions = {}): Promise<RunningServer> {
  const databaseUrl = options.databaseUrl ?? process.env.DATABASE_URL;
  let db: AppDatabase;
  let closeDb: (() => Promise<void>) | undefined;

  if (databaseUrl) {
    const pgDb = await createPostgresDatabase(databaseUrl);
    db = pgDb.db;
    closeDb = pgDb.close;
  } else {
    db = await createTestDatabase();
  }

  await seedDatabase(db);

  const yoga = createGraphqlApp(db);
  const server = http.createServer(yoga);

  const desiredPort = options.port ?? (process.env.PORT ? parseInt(process.env.PORT, 10) : 4000);

  return new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(desiredPort, () => {
      const address = server.address();
      const actualPort =
        typeof address === 'object' && address !== null ? address.port : desiredPort;
      resolve({
        server,
        port: actualPort,
        db,
        stop: async () => {
          await new Promise<void>((res, rej) => {
            server.close((err) => (err ? rej(err) : res()));
          });
          if (closeDb) {
            await closeDb();
          }
        },
      });
    });
  });
}

const isMain =
  Boolean(process.argv[1]) &&
  fileURLToPath(import.meta.url).toLowerCase() === path.resolve(process.argv[1]).toLowerCase();

/* v8 ignore start */
if (isMain) {
  startServer()
    .then(({ port }) => {
      console.log(`Server is running on http://localhost:${port}/graphql`);
    })
    .catch((err) => {
      console.error('Failed to start server:', err);
      process.exit(1);
    });
}
/* v8 ignore stop */
