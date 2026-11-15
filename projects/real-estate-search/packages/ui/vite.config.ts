import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function resolveJsToTsPlugin() {
  return {
    name: 'resolve-js-to-ts',
    resolveId(source: string, importer: string | undefined) {
      if (importer && source.startsWith('.') && source.endsWith('.js')) {
        const dir = path.dirname(importer);
        const base = source.slice(0, -3);
        const tsxPath = path.resolve(dir, `${base}.tsx`);
        if (fs.existsSync(tsxPath)) {
          return tsxPath;
        }
        const tsPath = path.resolve(dir, `${base}.ts`);
        if (fs.existsSync(tsPath)) {
          return tsPath;
        }
      }
      return null;
    },
  };
}

export default defineConfig({
  root: __dirname,
  plugins: [react(), resolveJsToTsPlugin()],
  resolve: {
    alias: {
      '@real-estate/shared': path.resolve(__dirname, '../shared/src/index.ts'),
    },
  },
  server: {
    proxy: {
      '/graphql': 'http://localhost:4000',
      '/api/go/graphql': {
        target: 'http://localhost:4001',
        rewrite: (reqPath) => reqPath.replace(/^\/api\/go/, ''),
      },
      '/api/java/graphql': {
        target: 'http://localhost:4002',
        rewrite: (reqPath) => reqPath.replace(/^\/api\/java/, ''),
      },
      '/v1/traces': 'http://localhost:4318',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: [path.resolve(__dirname, './src/test-setup.ts')],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: ['src/**/*.ts', 'src/**/*.tsx'],
      exclude: ['src/main.tsx', 'src/test-setup.ts', 'src/**/*.d.ts', 'src/**/__tests__/**'],
      thresholds: {
        lines: 90,
        functions: 90,
        branches: 90,
        statements: 90,
      },
    },
  },
});
