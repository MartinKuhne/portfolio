package com.realestate.api.graphql;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;

import com.realestate.api.domain.RawSearchInput;
import com.realestate.api.domain.SearchError;
import com.realestate.api.domain.SearchErrorCode;
import com.realestate.api.domain.SearchResult;
import com.realestate.api.graphql.GraphqlPayloads.GraphqlInternalException;
import com.realestate.api.graphql.GraphqlPayloads.RequestTelemetryContext;
import com.realestate.api.graphql.GraphqlPayloads.SanitizingDataFetcherExceptionHandler;
import com.realestate.api.graphql.GraphqlPayloads.SearchListingsSuccessPayload;
import com.realestate.api.graphql.GraphqlPayloads.SearchOutOfRangeErrorPayload;
import com.realestate.api.graphql.GraphqlPayloads.SearchValidationErrorPayload;
import com.realestate.api.search.SearchService;
import com.realestate.api.telemetry.ApiTelemetry;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.TypeResolver;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the {@link GraphQLSchema} and {@link GraphQL} engine matching ADR-002 and SEC-06/SEC-07.
 */
public final class GraphqlSchemaFactory {

    public static final String REQUEST_TELEMETRY_CONTEXT_KEY = "requestTelemetryContext";

    public static final String SCHEMA_SDL = """
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
        """;

    private GraphqlSchemaFactory() {
    }

    /**
     * Builds the wired {@link GraphQLSchema}.
     */
    public static GraphQLSchema buildSchema(SearchService searchService, ApiTelemetry telemetry) {
        Objects.requireNonNull(searchService, "searchService must not be null");
        Objects.requireNonNull(telemetry, "telemetry must not be null");

        TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(SCHEMA_SDL);
        TypeResolver resultTypeResolver = env -> {
            Object obj = env.getObject();
            GraphQLSchema schema = env.getSchema();
            if (obj instanceof SearchListingsSuccessPayload) {
                return schema.getObjectType("SearchListingsSuccess");
            }
            if (obj instanceof SearchOutOfRangeErrorPayload) {
                return schema.getObjectType("SearchOutOfRangeError");
            }
            if (obj instanceof SearchValidationErrorPayload) {
                return schema.getObjectType("SearchValidationError");
            }
            return (GraphQLObjectType) null;
        };

        DataFetcher<Object> searchListingsFetcher = env -> telemetry.withSpan(
            "graphql.searchListings",
            SpanKind.INTERNAL,
            Attributes.builder()
                .put("graphql.operation.type", "query")
                .put("graphql.operation.name", "searchListings")
                .build(),
            span -> {
                RequestTelemetryContext reqCtx = env.getGraphQlContext().get(REQUEST_TELEMETRY_CONTEXT_KEY);
                Map<String, Object> inputMap = env.getArgument("input");
                RawSearchInput rawInput = inputMap != null ? RawSearchInput.fromMap(inputMap) : RawSearchInput.empty();

                SearchResult result;
                try {
                    result = searchService.executeSearch(rawInput);
                } catch (RuntimeException err) {
                    System.err.println("GraphQL resolver internal error: " + err.getMessage());
                    String safeMsg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
                    span.recordException(err);
                    span.setStatus(StatusCode.ERROR, safeMsg);
                    telemetry.log(
                        Severity.ERROR,
                        "ERROR",
                        "graphql.resolver_error",
                        Attributes.builder()
                            .put("graphql.operation.name", "searchListings")
                            .put("error.message", safeMsg)
                            .build()
                    );
                    if (reqCtx != null) {
                        reqCtx.markInternalError();
                    }
                    throw new GraphqlInternalException("An unexpected internal error occurred.");
                }

                if (result instanceof SearchResult.Success success) {
                    return new SearchListingsSuccessPayload(success.listings(), success.pagination());
                }

                SearchError error = ((SearchResult.Failure) result).error();
                span.setAttribute("error.code", error.code().name());
                span.setStatus(StatusCode.ERROR, error.message());

                if (error.code() == SearchErrorCode.INTERNAL_ERROR) {
                    if (reqCtx != null) {
                        reqCtx.markInternalError();
                    }
                    throw new GraphqlInternalException(error.message());
                }

                if (reqCtx != null) {
                    reqCtx.markDomainError();
                }

                if (error.code() == SearchErrorCode.OUT_OF_RANGE) {
                    return new SearchOutOfRangeErrorPayload(
                        "OUT_OF_RANGE",
                        error.message(),
                        error.field() != null ? error.field() : "page",
                        error.totalPages() != null ? error.totalPages() : 0
                    );
                }

                return new SearchValidationErrorPayload(
                    "VALIDATION_ERROR",
                    error.message(),
                    error.field()
                );
            }
        );

        RuntimeWiring runtimeWiring = RuntimeWiring.newRuntimeWiring()
            .scalar(JsonScalar.INSTANCE)
            .type(newTypeWiring("SearchListingsResult").typeResolver(resultTypeResolver))
            .type(newTypeWiring("SearchError").typeResolver(resultTypeResolver))
            .type(newTypeWiring("Query").dataFetcher("searchListings", searchListingsFetcher))
            .build();

        return new SchemaGenerator().makeExecutableSchema(typeRegistry, runtimeWiring);
    }

    /**
     * Builds a configured {@link GraphQL} instance with complexity validation and sanitized error handling.
     */
    public static GraphQL buildGraphQL(SearchService searchService, ApiTelemetry telemetry) {
        GraphQLSchema schema = buildSchema(searchService, telemetry);
        return GraphQL.newGraphQL(schema)
            .preparsedDocumentProvider(new QueryComplexityValidator())
            .defaultDataFetcherExceptionHandler(new SanitizingDataFetcherExceptionHandler())
            .build();
    }
}
