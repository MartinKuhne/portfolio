import { context, SpanKind, SpanStatusCode, trace } from '@opentelemetry/api';
import {
  type RawSearchInput,
  type SearchResponse,
  validateSearchInput,
} from '@real-estate/shared';
import { getTracer, injectTraceHeaders, logUi } from '../telemetry.js';

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
  const tracer = getTracer();
  const uiSpan = tracer.startSpan('ui.searchListings', {
    kind: SpanKind.CLIENT,
    attributes: {
      'search.page': String(rawInput.page ?? 1),
      'search.pageSize': String(rawInput.pageSize ?? 10),
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
        'error.code': validation.error.code,
        'error.field': validation.error.field ?? '',
      },
      uiCtx
    );
    uiSpan.end();
    return {
      ok: false,
      listings: [],
      pagination: null,
      error: validation.error,
    };
  }

  const httpSpan = tracer.startSpan(
    'HTTP POST /graphql',
    {
      kind: SpanKind.CLIENT,
      attributes: {
        'http.request.method': 'POST',
        'url.path': '/graphql',
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
        'http.request.method': 'POST',
        'url.path': '/graphql',
      },
      httpCtx
    );

    const response = await fetch('/graphql', {
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

    const json = (await response.json()) as {
      data?: { searchListings?: SearchResponse };
      errors?: Array<{ message: string }>;
    };

    if (json.data?.searchListings) {
      const payload = json.data.searchListings;
      uiSpan.setAttribute('search.ok', payload.ok);
      if (payload.ok) {
        const totalResults = payload.pagination?.totalResults ?? 0;
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
    }

    if (json.errors && json.errors.length > 0) {
      const message = json.errors[0]?.message || 'GraphQL query failed.';
      httpSpan.setStatus({ code: SpanStatusCode.ERROR, message });
      uiSpan.setAttribute('search.ok', false);
      uiSpan.setStatus({ code: SpanStatusCode.ERROR, message });
      logUi('ERROR', 'ui.search.graphql_errors', { 'error.message': message }, httpCtx);
      return {
        ok: false,
        listings: [],
        pagination: null,
        error: {
          code: 'INTERNAL_ERROR',
          message,
          field: null,
        },
      };
    }

    const emptyMessage = 'No response data returned from search server.';
    httpSpan.setStatus({ code: SpanStatusCode.ERROR, message: emptyMessage });
    uiSpan.setAttribute('search.ok', false);
    uiSpan.setStatus({ code: SpanStatusCode.ERROR, message: emptyMessage });
    logUi('ERROR', 'ui.search.empty_response', { 'error.message': emptyMessage }, httpCtx);
    return {
      ok: false,
      listings: [],
      pagination: null,
      error: {
        code: 'INTERNAL_ERROR',
        message: emptyMessage,
        field: null,
      },
    };
  } catch (err: unknown) {
    if ((err as { name?: string })?.name === 'AbortError') {
      httpSpan.setAttribute('http.aborted', true);
      logUi('INFO', 'ui.search.aborted', {}, httpCtx);
      throw err;
    }

    const message = err instanceof Error ? err.message : 'Network request failed.';
    const error = err instanceof Error ? err : new Error(message);
    httpSpan.recordException(error);
    httpSpan.setStatus({ code: SpanStatusCode.ERROR, message });
    uiSpan.recordException(error);
    uiSpan.setAttribute('search.ok', false);
    uiSpan.setStatus({ code: SpanStatusCode.ERROR, message });
    logUi('ERROR', 'ui.search.network_error', { 'error.message': message }, httpCtx);

    return {
      ok: false,
      listings: [],
      pagination: null,
      error: {
        code: 'INTERNAL_ERROR',
        message,
        field: null,
      },
    };
  } finally {
    httpSpan.end();
    uiSpan.end();
  }
}
