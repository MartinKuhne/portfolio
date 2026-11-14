import { GraphQLScalarType, Kind, type ValueNode } from 'graphql';
import { createSchema, createYoga, type YogaServerInstance } from 'graphql-yoga';
import type { RawSearchInput } from '@real-estate/shared';
import type { AppDatabase } from '../db/client.js';
import * as searchService from '../search/executeSearch.js';

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
          try {
            const input = args?.input ?? {};
            const result = await searchService.executeSearch(db, input);
            if (!result.ok) {
              ctx.httpStatus = result.error.code === 'INTERNAL_ERROR' ? 500 : 400;
            }
            return {
              ...result,
              listings: result.listings ?? [],
              pagination: result.pagination ?? null,
            };
          } catch (err) {
            ctx.httpStatus = 500;
            return {
              ok: false,
              listings: [],
              pagination: null,
              error: {
                code: 'INTERNAL_ERROR',
                message: err instanceof Error ? err.message : 'An unexpected error occurred.',
                field: null,
              },
            };
          }
        },
      },
    },
  });

  return createYoga({
    schema,
    cors: true,
    plugins: [
      {
        onResponse({ response, setResponse, serverContext, fetchAPI }) {
          const ctx = serverContext as (YogaServerContext & Record<string, unknown>) | undefined;
          if (ctx && typeof ctx.httpStatus === 'number') {
            const status = ctx.httpStatus;
            setResponse(
              new fetchAPI.Response(response.body, {
                status,
                statusText: status === 400 ? 'Bad Request' : 'Internal Server Error',
                headers: response.headers,
              })
            );
          }
        },
      },
    ],
  });
}
