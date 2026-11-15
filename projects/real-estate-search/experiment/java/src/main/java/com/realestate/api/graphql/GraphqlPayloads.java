package com.realestate.api.graphql;

import com.realestate.api.domain.Listing;
import com.realestate.api.domain.PaginationMeta;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GraphQL output payload types, per-request telemetry tracking, and sanitized exception handling.
 */
public final class GraphqlPayloads {

    private GraphqlPayloads() {
    }

    public record SearchListingsSuccessPayload(List<Listing> listings, PaginationMeta pagination) {
    }

    public record SearchValidationErrorPayload(String code, String message, String field) {
    }

    public record SearchOutOfRangeErrorPayload(String code, String message, String field, int totalPages) {
    }

    /**
     * Mutable per-request marker tracking whether GraphQL execution hit a domain or internal error.
     */
    public static final class RequestTelemetryContext {
        private boolean domainError;
        private boolean internalError;

        public boolean hasDomainError() {
            return domainError;
        }

        public void markDomainError() {
            this.domainError = true;
        }

        public boolean hasInternalError() {
            return internalError;
        }

        public void markInternalError() {
            this.internalError = true;
        }
    }

    /**
     * Exception carrying an already-sanitized internal error message for GraphQL error formatting.
     */
    public static final class GraphqlInternalException extends RuntimeException {
        public GraphqlInternalException(String sanitizedMessage) {
            super(sanitizedMessage);
        }
    }

    /**
     * Sanitizes unexpected resolver exceptions into {@code INTERNAL_SERVER_ERROR} without leaking details.
     */
    public static final class SanitizingDataFetcherExceptionHandler implements DataFetcherExceptionHandler {

        @Override
        public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
            DataFetcherExceptionHandlerParameters handlerParameters
        ) {
            Throwable exception = handlerParameters.getException();
            ErrorType errorType = ErrorType.DataFetchingException;
            String errorCode = "INTERNAL_SERVER_ERROR";
            String sanitizedMessage;
            if (exception instanceof GraphqlInternalException) {
                sanitizedMessage = exception.getMessage();
            } else if (exception instanceof CoercingParseLiteralException
                || exception instanceof CoercingParseValueException) {
                errorType = ErrorType.ValidationError;
                errorCode = "BAD_USER_INPUT";
                sanitizedMessage = exception.getMessage() != null
                    ? exception.getMessage()
                    : "Invalid scalar input.";
            } else {
                System.err.println("Unexpected GraphQL data fetcher exception: " + exception.getMessage());
                sanitizedMessage = "An unexpected internal error occurred.";
            }

            GraphQLError error = GraphQLError.newError()
                .errorType(errorType)
                .message(sanitizedMessage)
                .path(handlerParameters.getPath())
                .location(handlerParameters.getSourceLocation())
                .extensions(Map.of("code", errorCode))
                .build();

            return CompletableFuture.completedFuture(
                DataFetcherExceptionHandlerResult.newResult().error(error).build()
            );
        }
    }
}
