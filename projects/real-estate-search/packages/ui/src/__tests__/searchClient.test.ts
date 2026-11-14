import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchSearchListings } from '../api/searchClient.js';

describe('fetchSearchListings (searchClient)', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('short-circuits on client-side validation error without calling fetch (REQ-VAL-001)', async () => {
    const mockFetch = vi.fn();
    globalThis.fetch = mockFetch;

    const res = await fetchSearchListings({ minPrice: 600000, maxPrice: 400000 });

    expect(res.ok).toBe(false);
    if (!res.ok) {
      expect(res.error.message).toMatch(/minPrice must not exceed maxPrice/i);
      expect(res.error.field).toBe('minPrice');
    }
    expect(res.listings).toEqual([]);
    expect(res.pagination).toBeNull();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  it('sends GraphQL query and returns listings on success', async () => {
    const mockData = {
      ok: true,
      listings: [
        {
          id: 'LST-001',
          address: '101 Congress Ave, Austin, TX',
          city: 'Austin',
          price: 485000,
          bedrooms: 3,
          description: 'Modern downtown condo',
          listedDate: '2026-10-20T00:00:00.000Z',
          relevanceScore: 0.94,
        },
      ],
      pagination: { page: 1, pageSize: 10, totalResults: 1, totalPages: 1 },
      error: null,
    };

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ data: { searchListings: mockData } }),
    });
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const res = await fetchSearchListings({ city: 'Austin', page: 1, pageSize: 10 });

    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledWith(
      '/graphql',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      })
    );
    expect(res).toEqual(mockData);
  });

  it('handles HTTP 400 error payload returned by server (REQ-ERR-001)', async () => {
    const errorPayload = {
      ok: false,
      listings: [],
      pagination: null,
      error: {
        code: 'OUT_OF_RANGE',
        message: 'Requested page 5 exceeds total pages 2.',
        field: 'page',
        totalPages: 2,
      },
    };

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ data: { searchListings: errorPayload } }),
    });
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const res = await fetchSearchListings({ page: 5, pageSize: 10 });

    expect(res).toEqual(errorPayload);
  });

  it('handles GraphQL errors array in response', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({ errors: [{ message: 'Internal server error occurred.' }] }),
    });
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const res = await fetchSearchListings({ page: 1, pageSize: 10 });

    expect(res.ok).toBe(false);
    if (!res.ok) {
      expect(res.error.code).toBe('INTERNAL_ERROR');
      expect(res.error.message).toBe('Internal server error occurred.');
    }
  });

  it('handles GraphQL errors array with missing message fallback', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      json: async () => ({ errors: [{}] }),
    });
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const res = await fetchSearchListings({ page: 1, pageSize: 10 });

    expect(res.ok).toBe(false);
    if (!res.ok) {
      expect(res.error.code).toBe('INTERNAL_ERROR');
      expect(res.error.message).toBe('GraphQL query failed.');
    }
  });

  it('handles empty response without data or errors gracefully', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({}),
    });
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const res = await fetchSearchListings({ page: 1, pageSize: 10 });

    expect(res.ok).toBe(false);
    if (!res.ok) {
      expect(res.error.code).toBe('INTERNAL_ERROR');
      expect(res.error.message).toMatch(/No response data/i);
    }
  });

  it('handles network fetch rejection gracefully', async () => {
    const mockFetch = vi.fn().mockRejectedValue(new Error('Network offline'));
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const res = await fetchSearchListings({ page: 1, pageSize: 10 });

    expect(res.ok).toBe(false);
    if (!res.ok) {
      expect(res.error.code).toBe('INTERNAL_ERROR');
      expect(res.error.message).toBe('Network offline');
    }
  });

  it('handles non-Error rejection object gracefully', async () => {
    const mockFetch = vi.fn().mockRejectedValue('Fatal network crash');
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const res = await fetchSearchListings({ page: 1, pageSize: 10 });

    expect(res.ok).toBe(false);
    if (!res.ok) {
      expect(res.error.code).toBe('INTERNAL_ERROR');
      expect(res.error.message).toBe('Network request failed.');
    }
  });

  it('re-throws AbortError when request is intentionally aborted', async () => {
    const abortError = new DOMException('The operation was aborted', 'AbortError');
    const mockFetch = vi.fn().mockRejectedValue(abortError);
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const controller = new AbortController();
    controller.abort();

    await expect(
      fetchSearchListings({ page: 1, pageSize: 10 }, controller.signal)
    ).rejects.toThrow('The operation was aborted');
  });
});
