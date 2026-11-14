package com.realestate.api.search;

import com.realestate.api.domain.Listing;
import com.realestate.api.domain.ListingRepository;
import com.realestate.api.domain.PaginationMeta;
import com.realestate.api.domain.RawSearchInput;
import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.domain.SearchError;
import com.realestate.api.domain.SearchResult;
import com.realestate.api.telemetry.ApiTelemetry;
import com.realestate.api.validation.DefaultSearchValidator;
import com.realestate.api.validation.SearchValidator;
import com.realestate.api.validation.ValidationOutcome;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates search input validation, pagination planning, repository querying, and telemetry.
 */
public class SearchService {

    private final ListingRepository repository;
    private final SearchValidator validator;
    private final ApiTelemetry telemetry;

    public SearchService(ListingRepository repository) {
        this(repository, new DefaultSearchValidator(), ApiTelemetry.getGlobal());
    }

    public SearchService(ListingRepository repository, SearchValidator validator, ApiTelemetry telemetry) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    /**
     * Executes a validated, paginated property search with OpenTelemetry instrumentation.
     */
    public SearchResult executeSearch(RawSearchInput rawInput) {
        RawSearchInput safeInput = rawInput != null ? rawInput : RawSearchInput.empty();
        return telemetry.withSpan("search.execute", SpanKind.INTERNAL, Attributes.empty(), executeSpan -> {
            try {
                telemetry.log(
                    Severity.INFO,
                    "INFO",
                    "search.started",
                    Attributes.builder()
                        .put("search.hasCity", rawInput != null && rawInput.city() != null)
                        .put("search.hasKeyword", rawInput != null && rawInput.keyword() != null)
                        .put("search.hasTargetBudget", rawInput != null && rawInput.targetBudget() != null)
                        .build()
                );

                ValidationOutcome validation = validateWithSpan(safeInput);
                if (validation instanceof ValidationOutcome.Invalid invalid) {
                    return recordFailure(
                        executeSpan,
                        invalid.error(),
                        Severity.WARN,
                        "WARN",
                        "search.validation_failed",
                        Attributes.builder()
                            .put("error.code", invalid.error().code().name())
                            .put("error.field", invalid.error().field() != null ? invalid.error().field() : "")
                            .build(),
                        invalid.error().message()
                    );
                }

                SearchCriteria criteria = ((ValidationOutcome.Valid) validation).criteria();
                executeSpan.setAttribute("search.page", criteria.page());
                executeSpan.setAttribute("search.pageSize", criteria.pageSize());

                int totalResults = repository.countMatchingListings(criteria);
                executeSpan.setAttribute("search.totalResults", totalResults);

                if (totalResults == 0) {
                    PaginationMeta emptyPagination = new PaginationMeta(criteria.page(), criteria.pageSize(), 0, 0);
                    return completeSearch(executeSpan, criteria, List.of(), emptyPagination);
                }

                int totalPages = (int) Math.ceil((double) totalResults / criteria.pageSize());
                if (criteria.page() > totalPages) {
                    SearchError outOfRange = SearchError.outOfRange(criteria.page(), totalPages);
                    return recordFailure(
                        executeSpan,
                        outOfRange,
                        Severity.WARN,
                        "WARN",
                        "search.out_of_range",
                        Attributes.builder()
                            .put("search.page", criteria.page())
                            .put("search.totalPages", totalPages)
                            .put("search.totalResults", totalResults)
                            .build(),
                        outOfRange.message()
                    );
                }

                int offset = (criteria.page() - 1) * criteria.pageSize();
                List<Listing> listings = repository.fetchListingPage(criteria, offset);
                PaginationMeta pagination = new PaginationMeta(criteria.page(), criteria.pageSize(), totalResults, totalPages);
                return completeSearch(executeSpan, criteria, listings, pagination);
            } catch (RuntimeException err) {
                System.err.println("executeSearch internal error: " + err.getMessage());
                executeSpan.recordException(err);
                String safeErrMessage = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
                return recordFailure(
                    executeSpan,
                    SearchError.internalError("An unexpected database error occurred."),
                    Severity.ERROR,
                    "ERROR",
                    "search.internal_error",
                    Attributes.builder().put("error.message", safeErrMessage).build(),
                    safeErrMessage
                );
            }
        });
    }

    private ValidationOutcome validateWithSpan(RawSearchInput rawInput) {
        return telemetry.withSpan("search.validateInput", SpanKind.INTERNAL, Attributes.empty(), validateSpan -> {
            ValidationOutcome outcome = validator.validate(rawInput);
            validateSpan.setAttribute("search.validation.ok", outcome.isOk());
            if (outcome instanceof ValidationOutcome.Invalid invalid) {
                validateSpan.setStatus(StatusCode.ERROR, invalid.error().message());
            }
            return outcome;
        });
    }

    private SearchResult completeSearch(
        Span executeSpan,
        SearchCriteria criteria,
        List<Listing> listings,
        PaginationMeta pagination
    ) {
        executeSpan.setAttribute("search.ok", true);
        executeSpan.setAttribute("search.returnedCount", listings.size());
        telemetry.log(
            Severity.INFO,
            "INFO",
            "search.completed",
            Attributes.builder()
                .put("search.page", criteria.page())
                .put("search.pageSize", criteria.pageSize())
                .put("search.totalResults", pagination.totalResults())
                .put("search.returnedCount", listings.size())
                .build()
        );
        return SearchResult.success(listings, pagination);
    }

    private SearchResult recordFailure(
        Span executeSpan,
        SearchError error,
        Severity severity,
        String severityText,
        String eventName,
        Attributes logAttributes,
        String spanStatusMessage
    ) {
        executeSpan.setAttribute("search.ok", false);
        executeSpan.setStatus(StatusCode.ERROR, spanStatusMessage);
        telemetry.log(severity, severityText, eventName, logAttributes);
        return SearchResult.failure(error);
    }
}
