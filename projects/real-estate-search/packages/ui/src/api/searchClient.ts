import {
  context,
  SpanKind,
  SpanStatusCode,
  trace,
  type Context,
  type Span,
} from '@opentelemetry/api';
import {
  type Listing,
  type PaginationMeta,
  type RawSearchInput,
  type SearchError,
  type SearchResponse,
  validateSearchInput,
} from '@real-estate/shared';
import { getTracer, injectTraceHeaders, logUi } from '../telemetry.js';

const SEARCH_QUERY = /* GraphQL */ `
  query SearchListings($input: SearchListingsInput) {
    searchListings(input: $input) {
      __typename
      ... on SearchListingsSuccess {
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
      }
      ... on SearchError {
        code
        message
        field
      }
      ... on SearchOutOfRangeError {
        totalPages
      }
    }
  }
`;

type GraphqlSearchListingsResult =
  | {
      __typename: 'SearchListingsSuccess';
      listings: Listing[];
      pagination: PaginationMeta;
    }
  | {
      __typename: 'SearchValidationError';
      code: 'VALIDATION_ERROR';
      message: string;
      field: string | null;
    }
  | {
      __typename: 'SearchOutOfRangeError';
      code: 'OUT_OF_RANGE';
      message: string;
      field: string | null;
      totalPages: number;
    };

/** Builds a failed `SearchResponse` envelope with empty listings and null pagination. */
function createErrorResponse(error: SearchError): SearchResponse {
  return {
    ok: false,
    listings: [],
    pagination: null,
    error,
  };
}

interface InternalFailureTelemetry {
  uiSpan: Span;
  httpSpan: Span;
  httpCtx: Context;
  message: string;
  logEvent: string;
  logAttributes: Record<string, string | number>;
  exception?: Error;
}

/** Records span errors/exceptions and emits a trace-correlated UI error log for internal failures. */
function recordInternalFailure({
  uiSpan,
  httpSpan,
  httpCtx,
  message,
  logEvent,
  logAttributes,
  exception,
}: InternalFailureTelemetry): SearchResponse {
  if (exception) {
    httpSpan.recordException(exception);
    uiSpan.recordException(exception);
  }
  httpSpan.setStatus({ code: SpanStatusCode.ERROR, message });
  uiSpan.setAttribute('search.ok', false);
  uiSpan.setStatus({ code: SpanStatusCode.ERROR, message });
  logUi('ERROR', logEvent, logAttributes, httpCtx);
  return createErrorResponse({
    code: 'INTERNAL_ERROR',
    message,
    field: null,
  });
}

function mapGraphqlResultToSearchResponse(
  raw: GraphqlSearchListingsResult | null | undefined
): SearchResponse | null {
  if (!raw) {
    return null;
  }
  switch (raw.__typename) {
    case 'SearchListingsSuccess':
      return {
        ok: true,
        listings: raw.listings,
        pagination: raw.pagination,
        error: null,
      };
    case 'SearchValidationError':
      return createErrorResponse({
        code: 'VALIDATION_ERROR',
        message: raw.message,
        field: raw.field ?? null,
      });
    case 'SearchOutOfRangeError':
      return createErrorResponse({
        code: 'OUT_OF_RANGE',
        message: raw.message,
        field: raw.field ?? 'page',
        totalPages: raw.totalPages,
      });
    default:
      return null;
  }
}

export type ApiBackendId = 'node' | 'go' | 'java';

export interface ApiBackendOption {
  id: ApiBackendId;
  label: string;
  endpoint: string;
  serviceName: string;
}

export const API_BACKENDS: readonly ApiBackendOption[] = [
  {
    id: 'node',
    label: 'TypeScript (Node.js)',
    endpoint: '/graphql',
    serviceName: 'real-estate-api',
  },
  {
    id: 'go',
    label: 'Go',
    endpoint: '/api/go/graphql',
    serviceName: 'real-estate-api-go',
  },
  {
    id: 'java',
    label: 'Java',
    endpoint: '/api/java/graphql',
    serviceName: 'real-estate-api-java',
  },
] as const;

export interface SearchRequestOptions {
  backend?: ApiBackendId;
  endpoint?: string;
}

/** Resolves metadata for the selected API backend, defaulting to TypeScript (Node.js). */
export function getApiBackendOption(backend?: ApiBackendId): ApiBackendOption {
  return API_BACKENDS.find((option) => option.id === backend) ?? API_BACKENDS[0];
}

export async function fetchSearchListings(
  rawInput: RawSearchInput,
  signal?: AbortSignal,
  options?: SearchRequestOptions
): Promise<SearchResponse> {
  const backend = getApiBackendOption(options?.backend);
  const endpoint = options?.endpoint ?? backend.endpoint;
  const tracer = getTracer();
  const uiSpan = tracer.startSpan('ui.searchListings', {
    kind: SpanKind.INTERNAL,
    attributes: {
      'search.page': String(rawInput.page ?? 1),
      'search.pageSize': String(rawInput.pageSize ?? 10),
      'api.backend': backend.id,
      'url.path': endpoint,
    },
  });
  const uiCtx = trace.setSpan(context.active(), uiSpan);

  const validation = validateSearchInput(rawInput);
  if (!validation.ok) {
    uiSpan.setAttribute('search.ok', false);
    uiSpan.setAttribute('error.code', validation.error.code);
    uiSpan.setStatus({ code: SpanStatusCode.ERROR, message: validation.error.message });
    logUi(
      'WARN',
      'ui.search.validation_failed',
      {
        'api.backend': backend.id,
        'error.code': validation.error.code,
        'error.field': validation.error.field ?? '',
      },
      uiCtx
    );
    uiSpan.end();
    return createErrorResponse(validation.error);
  }

  const httpSpan = tracer.startSpan(
    `HTTP POST ${endpoint}`,
    {
      kind: SpanKind.CLIENT,
      attributes: {
        'http.request.method': 'POST',
        'url.path': endpoint,
        'api.backend': backend.id,
      },
    },
    uiCtx
  );
  const httpCtx = trace.setSpan(uiCtx, httpSpan);

  try {
    const headers = context.with(httpCtx, () =>
      injectTraceHeaders({ 'Content-Type': 'application/json' })
    );
    logUi(
      'INFO',
      'ui.search.request_started',
      {
        'api.backend': backend.id,
        'http.request.method': 'POST',
        'url.path': endpoint,
      },
      httpCtx
    );

    const response = await fetch(endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        query: SEARCH_QUERY,
        variables: { input: rawInput },
      }),
      signal,
    });

    if (typeof response.status === 'number') {
      httpSpan.setAttribute('http.response.status_code', response.status);
    }

    let json: {
      data?: { searchListings?: GraphqlSearchListingsResult | null };
      errors?: Array<{ message?: string; extensions?: { code?: string } }>;
    };
    try {
      json = (await response.json()) as typeof json;
    } catch (parseErr: unknown) {
      const exception = parseErr instanceof Error ? parseErr : new Error(String(parseErr));
      return recordInternalFailure({
        uiSpan,
        httpSpan,
        httpCtx,
        message: `Search service returned an unexpected response (HTTP ${response.status}).`,
        logEvent: 'ui.search.invalid_response',
        logAttributes: { 'http.response.status_code': response.status },
        exception,
      });
    }

    if (json.errors && json.errors.length > 0) {
      const message = json.errors[0]?.message || 'GraphQL query failed.';
      return recordInternalFailure({
        uiSpan,
        httpSpan,
        httpCtx,
        message,
        logEvent: 'ui.search.graphql_errors',
        logAttributes: { 'error.message': message },
      });
    }

    const payload = mapGraphqlResultToSearchResponse(json.data?.searchListings);
    if (!payload) {
      const emptyMessage = 'No response data returned from search server.';
      return recordInternalFailure({
        uiSpan,
        httpSpan,
        httpCtx,
        message: emptyMessage,
        logEvent: 'ui.search.empty_response',
        logAttributes: { 'error.message': emptyMessage },
      });
    }

    uiSpan.setAttribute('search.ok', payload.ok);
    if (payload.ok) {
      const totalResults = payload.pagination.totalResults;
      uiSpan.setAttribute('search.totalResults', totalResults);
      uiSpan.setAttribute('search.returnedCount', payload.listings.length);
      logUi(
        'INFO',
        'ui.search.completed',
        {
          'search.totalResults': totalResults,
          'search.returnedCount': payload.listings.length,
        },
        httpCtx
      );
    } else {
      uiSpan.setAttribute('error.code', payload.error.code);
      uiSpan.setStatus({ code: SpanStatusCode.ERROR, message: payload.error.message });
      httpSpan.setStatus({ code: SpanStatusCode.ERROR, message: payload.error.message });
      logUi(
        'WARN',
        'ui.search.server_error',
        {
          'error.code': payload.error.code,
          'error.message': payload.error.message,
        },
        httpCtx
      );
    }
    return payload;
  } catch (err: unknown) {
    if ((err as { name?: string })?.name === 'AbortError') {
      logUi('INFO', 'ui.search.request_aborted', {}, httpCtx);
      throw err;
    }

    const message = err instanceof Error ? err.message : 'Network request failed.';
    const exception = err instanceof Error ? err : new Error(message);
    return recordInternalFailure({
      uiSpan,
      httpSpan,
      httpCtx,
      message,
      logEvent: 'ui.search.network_error',
      logAttributes: { 'error.message': message },
      exception,
    });
  } finally {
    httpSpan.end();
    uiSpan.end();
  }
}
