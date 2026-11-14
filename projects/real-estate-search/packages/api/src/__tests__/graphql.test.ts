import { Kind } from 'graphql';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { createTestDatabase, type AppDatabase } from '../db/client.js';
import * as clientModule from '../db/client.js';
import { seedDatabase } from '../db/seed.js';
import { createGraphqlApp, JSONScalar, parseLiteral } from '../graphql/server.js';
import { startServer } from '../index.js';
import * as searchService from '../search/executeSearch.js';

describe('GraphQL searchListings endpoint', () => {
  let db: AppDatabase;
  let yoga: ReturnType<typeof createGraphqlApp>;

  beforeAll(async () => {
    db = await createTestDatabase();
    await seedDatabase(db);
    yoga = createGraphqlApp(db);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns matching listings and pagination metadata on valid query (REQ-ARCH-003, REQ-PAG-005)', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            listings { id address city price bedrooms description listedDate relevanceScore }
            pagination { page pageSize totalResults totalPages }
            error { code message field totalPages }
          }
        }`,
        variables: { input: { city: 'Austin', targetBudget: 500000, page: 1, pageSize: 5 } },
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.ok).toBe(true);
    expect(body.data.searchListings.listings.length).toBeGreaterThan(0);
    expect(body.data.searchListings.pagination.page).toBe(1);
    expect(body.data.searchListings.pagination.pageSize).toBe(5);
    expect(body.data.searchListings.error).toBeNull();
  });

  it('handles query with inline input literals', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search {
          searchListings(input: {
            city: "Austin"
            minPrice: 100000
            maxPrice: 900000
            minBedrooms: 2
            targetBudget: 500000
            page: 1
            pageSize: 5
          }) {
            ok
            listings { id city }
            pagination { page pageSize totalResults totalPages }
            error { code message field }
          }
        }`,
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.ok).toBe(true);
    expect(body.data.searchListings.listings.length).toBeGreaterThan(0);
  });

  it('handles optional input and default parameters', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search {
          searchListings {
            ok
            listings { id city }
            pagination { page pageSize totalResults totalPages }
          }
        }`,
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.ok).toBe(true);
    expect(body.data.searchListings.pagination.page).toBe(1);
  });

  it('returns empty listings without error when no listings match query', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            listings { id }
            pagination { totalResults totalPages }
            error { code message }
          }
        }`,
        variables: { input: { city: 'NonExistentCity' } },
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.ok).toBe(true);
    expect(body.data.searchListings.listings).toEqual([]);
    expect(body.data.searchListings.pagination.totalResults).toBe(0);
  });

  it('returns explicit 400 status and structured error object without crashing on invalid inputs (REQ-ERR-004..006)', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            error { code message field totalPages }
          }
        }`,
        variables: { input: { minPrice: 'not-a-number' } },
      }),
    });
    expect(response.status).toBe(400);
    const body = await response.json();
    expect(body.data.searchListings).toEqual({
      ok: false,
      error: {
        code: 'VALIDATION_ERROR',
        message: expect.stringContaining('minPrice'),
        field: 'minPrice',
        totalPages: null,
      },
    });
  });

  it('returns explicit 400 status and structured error object for OUT_OF_RANGE error (REQ-VAL-012, REQ-ERR-004)', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            error { code message field totalPages }
          }
        }`,
        variables: { input: { city: 'Austin', page: 999, pageSize: 5 } },
      }),
    });
    expect(response.status).toBe(400);
    const body = await response.json();
    expect(body.data.searchListings.ok).toBe(false);
    expect(body.data.searchListings.error.code).toBe('OUT_OF_RANGE');
    expect(body.data.searchListings.error.field).toBe('page');
    expect(body.data.searchListings.error.totalPages).toBeGreaterThan(0);
  });

  it('returns valid payload with empty listings and null pagination when full selection set is requested on error (VALIDATION_ERROR and OUT_OF_RANGE)', async () => {
    const fullQuery = `query Search($input: SearchListingsInput!) {
      searchListings(input: $input) {
        ok
        listings { id address price bedrooms relevanceScore }
        pagination { page pageSize totalResults totalPages }
        error { code message field totalPages }
      }
    }`;

    // 1. VALIDATION_ERROR with full selection set
    const valRes = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: fullQuery,
        variables: { input: { minPrice: 'bad-input' } },
      }),
    });
    expect(valRes.status).toBe(400);
    const valBody = await valRes.json();
    expect(valBody.errors).toBeUndefined();
    expect(valBody.data.searchListings.ok).toBe(false);
    expect(valBody.data.searchListings.listings).toEqual([]);
    expect(valBody.data.searchListings.pagination).toBeNull();
    expect(valBody.data.searchListings.error).toEqual({
      code: 'VALIDATION_ERROR',
      message: expect.stringContaining('minPrice'),
      field: 'minPrice',
      totalPages: null,
    });

    // 2. OUT_OF_RANGE with full selection set
    const rangeRes = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: fullQuery,
        variables: { input: { city: 'Austin', page: 999, pageSize: 5 } },
      }),
    });
    expect(rangeRes.status).toBe(400);
    const rangeBody = await rangeRes.json();
    expect(rangeBody.errors).toBeUndefined();
    expect(rangeBody.data.searchListings.ok).toBe(false);
    expect(rangeBody.data.searchListings.listings).toEqual([]);
    expect(rangeBody.data.searchListings.pagination).toBeNull();
    expect(rangeBody.data.searchListings.error).toEqual({
      code: 'OUT_OF_RANGE',
      message: expect.stringContaining('Page 999 exceeds total pages'),
      field: 'page',
      totalPages: expect.any(Number),
    });
  });

  it('returns explicit 500 status and structured error object when database fails (REQ-ERR-004..006)', async () => {
    const brokenDb = {
      select: () => {
        throw new Error('Database connection lost');
      },
      execute: () => {
        throw new Error('Database connection lost');
      },
    } as unknown as AppDatabase;

    const brokenYoga = createGraphqlApp(brokenDb);
    const response = await brokenYoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            error { code message field totalPages }
          }
        }`,
        variables: { input: { city: 'Austin' } },
      }),
    });
    expect(response.status).toBe(500);
    const body = await response.json();
    expect(body.data.searchListings.ok).toBe(false);
    expect(body.data.searchListings.error.code).toBe('INTERNAL_ERROR');
    expect(body.data.searchListings.error.field).toBeNull();
  });

  it('catches thrown unexpected errors in resolver with 500 status', async () => {
    vi.spyOn(searchService, 'executeSearch').mockRejectedValueOnce(
      new Error('Catastrophic failure')
    );

    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search { searchListings { ok error { code message } } }`,
      }),
    });
    expect(response.status).toBe(500);
    const body = await response.json();
    expect(body.data.searchListings.ok).toBe(false);
    expect(body.data.searchListings.error.code).toBe('INTERNAL_ERROR');
    expect(body.data.searchListings.error.message).toBe('Catastrophic failure');
  });

  it('handles non-Error thrown in resolver with 500 status', async () => {
    vi.spyOn(searchService, 'executeSearch').mockRejectedValueOnce('raw string error');

    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search { searchListings { ok error { code message } } }`,
      }),
    });
    expect(response.status).toBe(500);
    const body = await response.json();
    expect(body.data.searchListings.ok).toBe(false);
    expect(body.data.searchListings.error.code).toBe('INTERNAL_ERROR');
    expect(body.data.searchListings.error.message).toBe('An unexpected error occurred.');
  });

  it('handles malformed GraphQL requests cleanly without crashing (REQ-ERR-006)', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: 'invalid-json-payload',
    });
    expect(response.status).toBe(400);
    const body = await response.json();
    expect(body.errors).toBeDefined();
    expect(body.errors.length).toBeGreaterThan(0);
  });

  it('responds to CORS preflight OPTIONS request', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'OPTIONS',
      headers: {
        Origin: 'http://localhost:5173',
        'Access-Control-Request-Method': 'POST',
      },
    });
    expect([200, 204]).toContain(response.status);
    expect(response.headers.get('access-control-allow-origin')).toBe('http://localhost:5173');
  });
});

describe('JSONScalar AST parsing and serialization', () => {
  it('parses various AST kinds properly', () => {
    expect(parseLiteral({ kind: Kind.STRING, value: 'test' })).toBe('test');
    expect(parseLiteral({ kind: Kind.BOOLEAN, value: true })).toBe(true);
    expect(parseLiteral({ kind: Kind.INT, value: '42' })).toBe(42);
    expect(parseLiteral({ kind: Kind.FLOAT, value: '42.5' })).toBe(42.5);
    expect(parseLiteral({ kind: Kind.NULL })).toBeNull();
    expect(
      parseLiteral({
        kind: Kind.LIST,
        values: [{ kind: Kind.INT, value: '1' }, { kind: Kind.INT, value: '2' }],
      })
    ).toEqual([1, 2]);
    expect(
      parseLiteral({
        kind: Kind.OBJECT,
        fields: [
          {
            kind: Kind.OBJECT_FIELD,
            name: { kind: Kind.NAME, value: 'key' },
            value: { kind: Kind.STRING, value: 'val' },
          },
        ],
      })
    ).toEqual({ key: 'val' });
    expect(parseLiteral({ kind: Kind.VARIABLE, name: { kind: Kind.NAME, value: 'var' } })).toBeUndefined();
  });

  it('serializes and parses value directly', () => {
    expect(JSONScalar.serialize(123)).toBe(123);
    expect(JSONScalar.parseValue('abc')).toBe('abc');
  });
});

describe('startServer lifecycle and HTTP execution', () => {
  it('starts server on ephemeral port, executes query over HTTP, and stops cleanly', async () => {
    const serverInstance = await startServer({ port: 0 });
    expect(serverInstance.port).toBeGreaterThan(0);

    const response = await fetch(`http://localhost:${serverInstance.port}/graphql`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            ok
            listings { id city }
            pagination { totalResults }
          }
        }`,
        variables: { input: { city: 'Austin', page: 1, pageSize: 5 } },
      }),
    });

    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.ok).toBe(true);
    expect(body.data.searchListings.listings.length).toBeGreaterThan(0);

    await serverInstance.stop();
  });

  it('starts server with environment port fallback when no port provided', async () => {
    const originalPort = process.env.PORT;
    process.env.PORT = '0';
    try {
      const serverInstance = await startServer();
      expect(serverInstance.port).toBeGreaterThan(0);
      await serverInstance.stop();
    } finally {
      if (originalPort !== undefined) {
        process.env.PORT = originalPort;
      } else {
        delete process.env.PORT;
      }
    }
  });

  it('supports custom databaseUrl and closes database on stop', async () => {
    const mockClose = vi.fn().mockResolvedValue(undefined);
    const testDb = await createTestDatabase();
    await seedDatabase(testDb);

    vi.spyOn(clientModule, 'createPostgresDatabase').mockResolvedValueOnce({
      db: testDb,
      close: mockClose,
    });

    const serverInstance = await startServer({
      port: 0,
      databaseUrl: 'postgres://user:pass@localhost:5432/mock_db',
    });
    expect(serverInstance.port).toBeGreaterThan(0);

    await serverInstance.stop();
    expect(mockClose).toHaveBeenCalledTimes(1);
  });

  it('rejects startServer when listen fails', async () => {
    // Attempting to listen on an invalid port like -1 causes an immediate error
    await expect(startServer({ port: -1 })).rejects.toThrow();
  });
});
