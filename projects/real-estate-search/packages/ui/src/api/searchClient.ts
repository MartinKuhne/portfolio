import {
  type RawSearchInput,
  type SearchResponse,
  validateSearchInput,
} from '@real-estate/shared';

const SEARCH_QUERY = /* GraphQL */ `
  query SearchListings($input: SearchListingsInput) {
    searchListings(input: $input) {
      ok
      listings {
        id
        address
        city
        price
        bedrooms
        description
        listedDate
        relevanceScore
      }
      pagination {
        page
        pageSize
        totalResults
        totalPages
      }
      error {
        code
        message
        field
        totalPages
      }
    }
  }
`;

export async function fetchSearchListings(
  rawInput: RawSearchInput,
  signal?: AbortSignal
): Promise<SearchResponse> {
  const validation = validateSearchInput(rawInput);
  if (!validation.ok) {
    return {
      ok: false,
      listings: [],
      pagination: null,
      error: validation.error,
    };
  }

  try {
    const response = await fetch('/graphql', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        query: SEARCH_QUERY,
        variables: { input: rawInput },
      }),
      signal,
    });

    const json = (await response.json()) as {
      data?: { searchListings?: SearchResponse };
      errors?: Array<{ message: string }>;
    };

    if (json.data?.searchListings) {
      return json.data.searchListings;
    }

    if (json.errors && json.errors.length > 0) {
      return {
        ok: false,
        listings: [],
        pagination: null,
        error: {
          code: 'INTERNAL_ERROR',
          message: json.errors[0]?.message || 'GraphQL query failed.',
          field: null,
        },
      };
    }

    return {
      ok: false,
      listings: [],
      pagination: null,
      error: {
        code: 'INTERNAL_ERROR',
        message: 'No response data returned from search server.',
        field: null,
      },
    };
  } catch (err: unknown) {
    if ((err as { name?: string })?.name === 'AbortError') {
      throw err;
    }

    return {
      ok: false,
      listings: [],
      pagination: null,
      error: {
        code: 'INTERNAL_ERROR',
        message: err instanceof Error ? err.message : 'Network request failed.',
        field: null,
      },
    };
  }
}
