import { SpanKind, SpanStatusCode, trace } from '@opentelemetry/api';
import { GraphQLScalarType, Kind, type ValueNode } from 'graphql';
import { createSchema, createYoga, type YogaServerInstance } from 'graphql-yoga';
import type { RawSearchInput } from '@real-estate/shared';
import type { AppDatabase } from '../db/client.js';
import * as searchService from '../search/executeSearch.js';
import {
  extractTraceContext,
  getApiTracer,
  logApi,
  SeverityNumber,
  withApiSpan,
} from '../telemetry.js';

export function parseLiteral(ast: ValueNode): unknown {
  switch (ast.kind) {
    case Kind.STRING:
    case Kind.BOOLEAN:
      return ast.value;
    case Kind.INT:
      return parseInt(ast.value, 10);
    case Kind.FLOAT:
      return parseFloat(ast.value);
    case Kind.NULL:
      return null;
    case Kind.LIST:
      return ast.values.map(parseLiteral);
    case Kind.OBJECT: {
      const obj: Record<string, unknown> = {};
      for (const field of ast.fields) {
        obj[field.name.value] = parseLiteral(field.value);
      }
      return obj;
    }
    default:
      return undefined;
  }
}

export const JSONScalar = new GraphQLScalarType({
  name: 'JSONScalar',
  description: 'Pass-through scalar for arbitrary JSON primitives or structures without coercion',
  serialize: (value) => value,
  parseValue: (value) => value,
  parseLiteral,
});

export const typeDefs = /* GraphQL */ `
  scalar JSONScalar

  input SearchListingsInput {
    minPrice: JSONScalar
    maxPrice: JSONScalar
    minBedrooms: JSONScalar
    city: String
    keyword: String
    targetBudget: JSONScalar
    page: JSONScalar
    pageSize: JSONScalar
  }

  type Listing {
    id: ID!
    address: String!
    city: String!
    price: Int!
    bedrooms: Int!
    description: String!
    listedDate: String!
    relevanceScore: Float!
  }

  type PaginationMeta {
    page: Int!
    pageSize: Int!
    totalResults: Int!
    totalPages: Int!
  }

  type SearchError {
    code: String!
    message: String!
    field: String
    totalPages: Int
  }

  type SearchListingsPayload {
    ok: Boolean!
    listings: [Listing!]!
    pagination: PaginationMeta
    error: SearchError
  }

  type Query {
    searchListings(input: SearchListingsInput): SearchListingsPayload!
  }
`;

export interface YogaServerContext extends Record<string, unknown> {
  httpStatus?: number;
}

export function createGraphqlApp(
  db: AppDatabase
): YogaServerInstance<Record<string, unknown>, Record<string, unknown>> {
  const schema = createSchema<YogaServerContext>({
    typeDefs,
    resolvers: {
      JSONScalar,
      Query: {
        searchListings: async (
          _parent: unknown,
          args: { input?: RawSearchInput },
          ctx: YogaServerContext
        ) => {
          return withApiSpan(
            'graphql.searchListings',
            {
              'graphql.operation.type': 'query',
              'graphql.operation.name': 'searchListings',
            },
            async (span) => {
              try {
                const input = args?.input ?? {};
                const result = await searchService.executeSearch(db, input);
                if (!result.ok) {
                  ctx.httpStatus = result.error.code === 'INTERNAL_ERROR' ? 500 : 400;
                  span.setStatus({
                    code: SpanStatusCode.ERROR,
                    message: result.error.message,
                  });
                }
                return {
                  ...result,
                  listings: result.listings ?? [],
                  pagination: result.pagination ?? null,
                };
              } catch (err) {
                console.error('GraphQL resolver internal error:', err);
                const error = err instanceof Error ? err : new Error(String(err));
                span.recordException(error);
                span.setStatus({
                  code: SpanStatusCode.ERROR,
                  message: error.message,
                });
                logApi(SeverityNumber.ERROR, 'ERROR', 'graphql.resolver_error', {
                  'graphql.operation.name': 'searchListings',
                  'error.message': error.message,
                });
                ctx.httpStatus = 500;
                return {
                  ok: false,
                  listings: [],
                  pagination: null,
                  error: {
                    code: 'INTERNAL_ERROR',
                    message: 'An unexpected internal error occurred.',
                    field: null,
                  },
                };
              }
            }
          );
        },
      },
    },
  });

  return createYoga({
    schema,
    cors: {
      allowedHeaders: ['content-type', 'traceparent', 'tracestate'],
      methods: ['GET', 'POST', 'OPTIONS'],
    },
    plugins: [
      {
        instrumentation: {
          request({ request }, wrapped) {
            const parentContext = extractTraceContext(request.headers);
            const method = request.method.toUpperCase();
            const pathname = new URL(request.url, 'http://localhost').pathname;

            return getApiTracer().startActiveSpan(
              `${method} ${pathname}`,
              {
                kind: SpanKind.SERVER,
                attributes: {
                  'http.request.method': method,
                  'url.path': pathname,
                },
              },
              parentContext,
              async (span) => {
                try {
                  await wrapped();
                } finally {
                  span.end();
                }
              }
            );
          },
        },
        onResponse({ request, response, setResponse, serverContext, fetchAPI }) {
          const ctx = serverContext as (YogaServerContext & Record<string, unknown>) | undefined;
          let finalStatus = response.status;
          if (ctx && typeof ctx.httpStatus === 'number') {
            finalStatus = ctx.httpStatus;
            setResponse(
              new fetchAPI.Response(response.body, {
                status: finalStatus,
                statusText: finalStatus === 400 ? 'Bad Request' : 'Internal Server Error',
                headers: response.headers,
              })
            );
          }

          const method = request.method.toUpperCase();
          const pathname = new URL(request.url, 'http://localhost').pathname;
          const activeSpan = trace.getActiveSpan();
          activeSpan?.setAttribute('http.response.status_code', finalStatus);

          if (finalStatus >= 500) {
            activeSpan?.setStatus({
              code: SpanStatusCode.ERROR,
              message: `HTTP ${finalStatus}`,
            });
            logApi(SeverityNumber.ERROR, 'ERROR', 'http.request.failed', {
              'http.request.method': method,
              'url.path': pathname,
              'http.response.status_code': finalStatus,
            });
          } else if (finalStatus >= 400) {
            activeSpan?.setStatus({
              code: SpanStatusCode.ERROR,
              message: `HTTP ${finalStatus}`,
            });
            logApi(SeverityNumber.WARN, 'WARN', 'http.request.rejected', {
              'http.request.method': method,
              'url.path': pathname,
              'http.response.status_code': finalStatus,
            });
          } else {
            logApi(SeverityNumber.INFO, 'INFO', 'http.request.completed', {
              'http.request.method': method,
              'url.path': pathname,
              'http.response.status_code': finalStatus,
            });
          }
        },
      },
    ],
  });
}
