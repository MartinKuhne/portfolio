import { EventEmitter } from 'node:events';
import { Kind } from 'graphql';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { createTestDatabase, type AppDatabase } from '../db/client.js';
import * as clientModule from '../db/client.js';
import { seedDatabase } from '../db/seed.js';
import { createGraphqlApp, JSONScalar, parseLiteral } from '../graphql/server.js';
import { registerShutdownHandlers, startServer } from '../index.js';
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
            __typename
            ... on SearchListingsSuccess {
              listings {
                internalId
                id
                source
                address
                city
                state
                zip
                price
                bedrooms
                bathrooms
                sqft
                latitude
                longitude
                status
                description
                listedDate
                relevanceScore
              }
              pagination { page pageSize totalResults totalPages }
            }
            ... on SearchError { code message field }
            ... on SearchOutOfRangeError { totalPages }
          }
        }`,
        variables: { input: { city: 'Austin', targetBudget: 500000, page: 1, pageSize: 5 } },
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.errors).toBeUndefined();
    expect(body.data.searchListings.__typename).toBe('SearchListingsSuccess');
    expect(body.data.searchListings.listings.length).toBeGreaterThan(0);
    const first = body.data.searchListings.listings[0];
    expect(first.internalId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(first.source).toBe('SEED');
    expect(first.state).toBe('TX');
    expect(first.zip).toMatch(/^\d{5}$/);
    expect(typeof first.bathrooms).toBe('number');
    expect(typeof first.sqft).toBe('number');
    expect(typeof first.latitude).toBe('number');
    expect(typeof first.longitude).toBe('number');
    expect(first.status).toBe('active');
    expect(body.data.searchListings.pagination.page).toBe(1);
    expect(body.data.searchListings.pagination.pageSize).toBe(5);
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
            __typename
            ... on SearchListingsSuccess {
              listings { id city }
              pagination { page pageSize totalResults totalPages }
            }
            ... on SearchError { code message field }
          }
        }`,
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.__typename).toBe('SearchListingsSuccess');
    expect(body.data.searchListings.listings.length).toBeGreaterThan(0);
  });

  it('handles optional input and default parameters', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search {
          searchListings {
            __typename
            ... on SearchListingsSuccess {
              listings { id city }
              pagination { page pageSize totalResults totalPages }
            }
          }
        }`,
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.__typename).toBe('SearchListingsSuccess');
    expect(body.data.searchListings.pagination.page).toBe(1);
  });

  it('returns empty listings without error when no listings match query', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            __typename
            ... on SearchListingsSuccess {
              listings { id }
              pagination { totalResults totalPages }
            }
            ... on SearchError { code message }
          }
        }`,
        variables: { input: { city: 'NonExistentCity' } },
      }),
    });
    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.__typename).toBe('SearchListingsSuccess');
    expect(body.data.searchListings.listings).toEqual([]);
    expect(body.data.searchListings.pagination.totalResults).toBe(0);
  });

  it('returns HTTP 200 and SearchValidationError union variant without crashing on invalid inputs (REQ-ERR-004..006)', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            __typename
            ... on SearchError { code message field }
          }
        }`,
        variables: { input: { minPrice: 'not-a-number' } },
      }),
    });
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.errors).toBeUndefined();
    expect(body.data.searchListings).toEqual({
      __typename: 'SearchValidationError',
      code: 'VALIDATION_ERROR',
      message: expect.stringContaining('minPrice'),
      field: 'minPrice',
    });
  });

  it('returns HTTP 200 and SearchOutOfRangeError union variant for OUT_OF_RANGE error (REQ-VAL-012, REQ-ERR-004)', async () => {
    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search($input: SearchListingsInput!) {
          searchListings(input: $input) {
            __typename
            ... on SearchError { code message field }
            ... on SearchOutOfRangeError { totalPages }
          }
        }`,
        variables: { input: { city: 'Austin', page: 999, pageSize: 5 } },
      }),
    });
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.errors).toBeUndefined();
    expect(body.data.searchListings.__typename).toBe('SearchOutOfRangeError');
    expect(body.data.searchListings.code).toBe('OUT_OF_RANGE');
    expect(body.data.searchListings.field).toBe('page');
    expect(body.data.searchListings.totalPages).toBeGreaterThan(0);
  });

  it('returns valid GraphQL union results when full fragment selection set is requested on error (VALIDATION_ERROR and OUT_OF_RANGE)', async () => {
    const fullQuery = `query Search($input: SearchListingsInput!) {
      searchListings(input: $input) {
        __typename
        ... on SearchListingsSuccess {
          listings { id address price bedrooms relevanceScore }
          pagination { page pageSize totalResults totalPages }
        }
        ... on SearchError { code message field }
        ... on SearchOutOfRangeError { totalPages }
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
    expect(valRes.status).toBe(200);
    const valBody = await valRes.json();
    expect(valBody.errors).toBeUndefined();
    expect(valBody.data.searchListings).toEqual({
      __typename: 'SearchValidationError',
      code: 'VALIDATION_ERROR',
      message: expect.stringContaining('minPrice'),
      field: 'minPrice',
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
    expect(rangeRes.status).toBe(200);
    const rangeBody = await rangeRes.json();
    expect(rangeBody.errors).toBeUndefined();
    expect(rangeBody.data.searchListings).toEqual({
      __typename: 'SearchOutOfRangeError',
      code: 'OUT_OF_RANGE',
      message: expect.stringContaining('Page 999 exceeds total pages'),
      field: 'page',
      totalPages: expect.any(Number),
    });
  });

  it('returns HTTP 200 with top-level GraphQL error and null searchListings when database fails (REQ-ERR-004..006)', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
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
            __typename
            ... on SearchError { code message field }
          }
        }`,
        variables: { input: { city: 'Austin' } },
      }),
    });
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.data.searchListings).toBeNull();
    expect(body.errors).toBeDefined();
    expect(body.errors[0].message).toBe('An unexpected database error occurred.');
    expect(body.errors[0].message).not.toContain('Database connection lost');
    expect(body.errors[0].extensions.code).toBe('INTERNAL_SERVER_ERROR');
    expect(consoleSpy).toHaveBeenCalledWith(
      'executeSearch internal error:',
      expect.any(Error)
    );
  });

  it('catches thrown unexpected errors in resolver and surfaces sanitized GraphQL error', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(searchService, 'executeSearch').mockRejectedValueOnce(
      new Error('Catastrophic failure')
    );

    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search { searchListings { __typename } }`,
      }),
    });
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.data.searchListings).toBeNull();
    expect(body.errors[0].extensions.code).toBe('INTERNAL_SERVER_ERROR');
    expect(body.errors[0].message).toBe('An unexpected internal error occurred.');
    expect(body.errors[0].message).not.toContain('Catastrophic failure');
    expect(consoleSpy).toHaveBeenCalledWith(
      'GraphQL resolver internal error:',
      expect.any(Error)
    );
  });

  it('handles non-Error thrown in resolver and surfaces sanitized GraphQL error', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.spyOn(searchService, 'executeSearch').mockRejectedValueOnce('raw string error');

    const response = await yoga.fetch('http://localhost:4000/graphql', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        query: `query Search { searchListings { __typename } }`,
      }),
    });
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body.data.searchListings).toBeNull();
    expect(body.errors[0].extensions.code).toBe('INTERNAL_SERVER_ERROR');
    expect(body.errors[0].message).toBe('An unexpected internal error occurred.');
    expect(body.errors[0].message).not.toContain('raw string error');
    expect(consoleSpy).toHaveBeenCalledWith(
      'GraphQL resolver internal error:',
      'raw string error'
    );
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
            __typename
            ... on SearchListingsSuccess {
              listings { id city }
              pagination { totalResults }
            }
          }
        }`,
        variables: { input: { city: 'Austin', page: 1, pageSize: 5 } },
      }),
    });

    const body = await response.json();
    expect(response.status).toBe(200);
    expect(body.data.searchListings.__typename).toBe('SearchListingsSuccess');
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

  it('registerShutdownHandlers stops server once on SIGTERM and exits with 0', async () => {
    const emitter = new EventEmitter();
    const stop = vi.fn().mockResolvedValue(undefined);
    const onExit = vi.fn();

    registerShutdownHandlers({ stop }, emitter, onExit);
    emitter.emit('SIGTERM');
    emitter.emit('SIGINT');
    emitter.emit('SIGTERM');

    await vi.waitFor(() => {
      expect(onExit).toHaveBeenCalledWith(0);
    });
    expect(stop).toHaveBeenCalledTimes(1);
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('registerShutdownHandlers logs error and exits with 1 when stop fails on SIGINT', async () => {
    const emitter = new EventEmitter();
    const stopError = new Error('close failure');
    const stop = vi.fn().mockRejectedValue(stopError);
    const onExit = vi.fn();
    const consoleErrSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    registerShutdownHandlers({ stop }, emitter, onExit);
    emitter.emit('SIGINT');

    await vi.waitFor(() => {
      expect(onExit).toHaveBeenCalledWith(1);
    });
    expect(stop).toHaveBeenCalledTimes(1);
    expect(consoleErrSpy).toHaveBeenCalledWith('Error during graceful shutdown:', stopError);
  });
});
