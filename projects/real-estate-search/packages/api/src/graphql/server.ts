import { SpanKind, SpanStatusCode, trace } from '@opentelemetry/api';
import {
  GraphQLError,
  GraphQLScalarType,
  Kind,
  type SelectionSetNode,
  type ValidationContext,
  type ValidationRule,
  type ValueNode,
} from 'graphql';
import { createGraphQLError, createSchema, createYoga, type YogaServerInstance } from 'graphql-yoga';
import type { RawSearchInput, SearchResponse } from '@real-estate/shared';
import type { AppDatabase } from '../db/client.js';
import * as searchService from '../search/executeSearch.js';
import {
  extractTraceContext,
  getApiTracer,
  logApi,
  SeverityNumber,
  withApiSpan,
} from '../telemetry.js';

const MAX_LITERAL_DEPTH = 5;
const MAX_ROOT_FIELDS = 10;
const MAX_QUERY_DEPTH = 15;
const FORBIDDEN_OBJECT_KEYS = new Set(['__proto__', 'constructor', 'prototype']);

function parseLiteralNode(ast: ValueNode, depth: number): unknown {
  if (depth > MAX_LITERAL_DEPTH) {
    throw createGraphQLError('Input literal nesting exceeds maximum allowed depth.', {
      extensions: { code: 'BAD_USER_INPUT' },
    });
  }

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
      return ast.values.map((item) => parseLiteralNode(item, depth + 1));
    case Kind.OBJECT: {
      const obj: Record<string, unknown> = Object.create(null);
      for (const field of ast.fields) {
        if (FORBIDDEN_OBJECT_KEYS.has(field.name.value)) {
          throw createGraphQLError('Invalid input object key.', {
            extensions: { code: 'BAD_USER_INPUT' },
          });
        }
        obj[field.name.value] = parseLiteralNode(field.value, depth + 1);
      }
      return obj;
    }
    default:
      return undefined;
  }
}

export function parseLiteral(ast: ValueNode): unknown {
  return parseLiteralNode(ast, 0);
}

function measureSelectionDepth(selectionSet: SelectionSetNode | undefined, depth: number): number {
  if (!selectionSet) {
    return depth;
  }
  let deepest = depth;
  for (const selection of selectionSet.selections) {
    if ('selectionSet' in selection && selection.selectionSet) {
      deepest = Math.max(deepest, measureSelectionDepth(selection.selectionSet, depth + 1));
    }
  }
  return deepest;
}

const queryComplexityRule: ValidationRule = (context: ValidationContext) => ({
  OperationDefinition(node) {
    if (node.selectionSet.selections.length > MAX_ROOT_FIELDS) {
      context.reportError(
        new GraphQLError(
          `Query exceeds maximum allowed root field count (${MAX_ROOT_FIELDS}).`,
          { nodes: node, extensions: { code: 'BAD_USER_INPUT' } }
        )
      );
    }
    if (measureSelectionDepth(node.selectionSet, 1) > MAX_QUERY_DEPTH) {
      context.reportError(
        new GraphQLError(`Query exceeds maximum allowed depth (${MAX_QUERY_DEPTH}).`, {
          nodes: node,
          extensions: { code: 'BAD_USER_INPUT' },
        })
      );
    }
  },
});

function getAllowedCorsOrigins(): string[] {
  return (process.env.CORS_ORIGIN ?? 'http://localhost:8080,http://localhost:5173')
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean);
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
    internalId: ID
    id: ID!
    source: String
    address: String!
    city: String!
    state: String
    zip: String
    price: Int!
    bedrooms: Int!
    bathrooms: Float
    sqft: Int
    latitude: Float
    longitude: Float
    status: String
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

  enum SearchErrorCode {
    VALIDATION_ERROR
    OUT_OF_RANGE
  }

  interface SearchError {
    code: SearchErrorCode!
    message: String!
    field: String
  }

  type SearchValidationError implements SearchError {
    code: SearchErrorCode!
    message: String!
    field: String
  }

  type SearchOutOfRangeError implements SearchError {
    code: SearchErrorCode!
    message: String!
    field: String
    totalPages: Int!
  }

  type SearchListingsSuccess {
    listings: [Listing!]!
    pagination: PaginationMeta!
  }

  union SearchListingsResult =
    | SearchListingsSuccess
    | SearchValidationError
    | SearchOutOfRangeError

  type Query {
    searchListings(input: SearchListingsInput): SearchListingsResult
  }
`;

export interface YogaServerContext extends Record<string, unknown> {
  hasDomainError?: boolean;
  hasInternalError?: boolean;
}

function toGraphqlSearchResult(result: SearchResponse) {
  if (result.ok) {
    return {
      __typename: 'SearchListingsSuccess' as const,
      listings: result.listings,
      pagination: result.pagination,
    };
  }

  if (result.error.code === 'INTERNAL_ERROR') {
    throw createGraphQLError(result.error.message, {
      extensions: { code: 'INTERNAL_SERVER_ERROR' },
    });
  }

  if (result.error.code === 'OUT_OF_RANGE') {
    return {
      __typename: 'SearchOutOfRangeError' as const,
      code: 'OUT_OF_RANGE' as const,
      message: result.error.message,
      field: result.error.field ?? 'page',
      totalPages: result.error.totalPages ?? 0,
    };
  }

  return {
    __typename: 'SearchValidationError' as const,
    code: 'VALIDATION_ERROR' as const,
    message: result.error.message,
    field: result.error.field ?? null,
  };
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
              let result: SearchResponse;
              try {
                result = await searchService.executeSearch(db, args?.input ?? {});
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
                ctx.hasInternalError = true;
                throw createGraphQLError('An unexpected internal error occurred.', {
                  extensions: { code: 'INTERNAL_SERVER_ERROR' },
                });
              }

              if (!result.ok) {
                if (result.error.code === 'INTERNAL_ERROR') {
                  ctx.hasInternalError = true;
                } else {
                  ctx.hasDomainError = true;
                }
                span.setAttribute('error.code', result.error.code);
                span.setStatus({
                  code: SpanStatusCode.ERROR,
                  message: result.error.message,
                });
              }
              return toGraphqlSearchResult(result);
            }
          );
        },
      },
    },
  });

  return createYoga({
    schema,
    cors: {
      origin: getAllowedCorsOrigins(),
      credentials: false,
      allowedHeaders: ['content-type', 'traceparent', 'tracestate'],
      methods: ['GET', 'POST', 'OPTIONS'],
    },
    plugins: [
      {
        onValidate({ addValidationRule }: { addValidationRule: (rule: ValidationRule) => void }) {
          addValidationRule(queryComplexityRule);
        },
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
                  return await wrapped();
                } catch (err) {
                  const error = err instanceof Error ? err : new Error(String(err));
                  span.recordException(error);
                  span.setStatus({
                    code: SpanStatusCode.ERROR,
                    message: error.message,
                  });
                  throw err;
                } finally {
                  span.end();
                }
              }
            );
          },
        },
        onResponse({ request, response, serverContext }) {
          const ctx = serverContext as (YogaServerContext & Record<string, unknown>) | undefined;
          const finalStatus = response.status;
          const method = request.method.toUpperCase();
          const pathname = new URL(request.url, 'http://localhost').pathname;
          const activeSpan = trace.getActiveSpan();
          activeSpan?.setAttribute('http.response.status_code', finalStatus);

          if (finalStatus >= 500 || ctx?.hasInternalError) {
            activeSpan?.setStatus({
              code: SpanStatusCode.ERROR,
              message: ctx?.hasInternalError ? 'GraphQL INTERNAL_SERVER_ERROR' : `HTTP ${finalStatus}`,
            });
            logApi(SeverityNumber.ERROR, 'ERROR', 'http.request.failed', {
              'http.request.method': method,
              'url.path': pathname,
              'http.response.status_code': finalStatus,
            });
          } else if (finalStatus >= 400 || ctx?.hasDomainError) {
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
