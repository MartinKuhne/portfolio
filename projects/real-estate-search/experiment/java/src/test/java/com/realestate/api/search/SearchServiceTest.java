package com.realestate.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.realestate.api.db.InMemoryListingRepository;
import com.realestate.api.db.SeedListings;
import com.realestate.api.domain.ListingRepository;
import com.realestate.api.domain.RawSearchInput;
import com.realestate.api.domain.SearchErrorCode;
import com.realestate.api.domain.SearchResult;
import com.realestate.api.scoring.DefaultRelevanceScorer;
import com.realestate.api.telemetry.ApiTelemetry;
import com.realestate.api.telemetry.TelemetryConfig;
import com.realestate.api.validation.DefaultSearchValidator;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

    private InMemorySpanExporter spanExporter;
    private ByteArrayOutputStream logBuffer;
    private ApiTelemetry telemetry;
    private InMemoryListingRepository repository;
    private SearchService service;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        logBuffer = new ByteArrayOutputStream();
        TelemetryConfig config = new TelemetryConfig(
            "real-estate-api",
            null,
            null,
            List.of(SimpleSpanProcessor.create(spanExporter)),
            List.of(),
            new PrintStream(logBuffer, true, StandardCharsets.UTF_8)
        );
        telemetry = ApiTelemetry.create(config);
        repository = new InMemoryListingRepository(new DefaultRelevanceScorer(), telemetry.tracer());
        SeedListings.seedDatabase(repository);
        service = new SearchService(repository, new DefaultSearchValidator(), telemetry);
    }

    @Test
    void executesValidSearchWithSpansAndStructuredLogs() {
        SearchResult result = service.executeSearch(
            new RawSearchInput(400000, 700000, 3, "Austin", "pool", 500000, 1, 5)
        );

        assertThat(result).isInstanceOf(SearchResult.Success.class);
        SearchResult.Success success = (SearchResult.Success) result;
        assertThat(success.listings()).isNotEmpty();
        assertThat(success.pagination().page()).isEqualTo(1);
        assertThat(success.pagination().pageSize()).isEqualTo(5);

        List<String> spanNames = spanExporter.getFinishedSpanItems().stream().map(SpanData::getName).toList();
        assertThat(spanNames).contains("search.execute", "search.validateInput", "search.db.count", "search.db.query");

        String logs = logBuffer.toString(StandardCharsets.UTF_8);
        assertThat(logs).contains("search.started").contains("search.completed");
    }

    @Test
    void returnsEmptySuccessWhenNoListingsMatch() {
        SearchResult result = service.executeSearch(
            new RawSearchInput(null, null, null, "Atlantis", null, null, 1, 10)
        );

        assertThat(result).isInstanceOf(SearchResult.Success.class);
        SearchResult.Success success = (SearchResult.Success) result;
        assertThat(success.listings()).isEmpty();
        assertThat(success.pagination().totalResults()).isZero();
        assertThat(success.pagination().totalPages()).isZero();
    }

    @Test
    void handlesNullInputAsDefaultSearch() {
        SearchResult result = service.executeSearch(null);

        assertThat(result).isInstanceOf(SearchResult.Success.class);
        SearchResult.Success success = (SearchResult.Success) result;
        assertThat(success.listings()).hasSize(10);
        assertThat(success.pagination().totalResults()).isEqualTo(24);
    }

    @Test
    void returnsValidationFailureWithoutQueryingRepository() {
        ListingRepository mockRepo = mock(ListingRepository.class);
        SearchService mockedService = new SearchService(mockRepo, new DefaultSearchValidator(), telemetry);

        SearchResult result = mockedService.executeSearch(
            new RawSearchInput(600000, 400000, null, null, null, null, null, null)
        );

        assertThat(result).isInstanceOf(SearchResult.Failure.class);
        SearchResult.Failure failure = (SearchResult.Failure) result;
        assertThat(failure.error().code()).isEqualTo(SearchErrorCode.VALIDATION_ERROR);
        assertThat(failure.error().field()).isEqualTo("minPrice");
        verify(mockRepo, never()).countMatchingListings(any());

        SpanData validateSpan = spanExporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("search.validateInput"))
            .findFirst()
            .orElseThrow();
        assertThat(validateSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(logBuffer.toString(StandardCharsets.UTF_8)).contains("search.validation_failed");
    }

    @Test
    void returnsOutOfRangeFailureWhenPageExceedsTotalPages() {
        SearchResult result = service.executeSearch(
            new RawSearchInput(null, null, null, "Austin", null, null, 99, 10)
        );

        assertThat(result).isInstanceOf(SearchResult.Failure.class);
        SearchResult.Failure failure = (SearchResult.Failure) result;
        assertThat(failure.error().code()).isEqualTo(SearchErrorCode.OUT_OF_RANGE);
        assertThat(failure.error().field()).isEqualTo("page");
        assertThat(failure.error().totalPages()).isEqualTo(1);
        assertThat(logBuffer.toString(StandardCharsets.UTF_8)).contains("search.out_of_range");
    }

    @Test
    void catchesUnexpectedRepositoryExceptionAndReturnsSanitizedInternalError() {
        ListingRepository brokenRepo = mock(ListingRepository.class);
        when(brokenRepo.countMatchingListings(any())).thenThrow(new IllegalStateException("DB socket reset"));
        SearchService brokenService = new SearchService(brokenRepo, new DefaultSearchValidator(), telemetry);

        SearchResult result = brokenService.executeSearch(RawSearchInput.empty());

        assertThat(result).isInstanceOf(SearchResult.Failure.class);
        SearchResult.Failure failure = (SearchResult.Failure) result;
        assertThat(failure.error().code()).isEqualTo(SearchErrorCode.INTERNAL_ERROR);
        assertThat(failure.error().message()).isEqualTo("An unexpected database error occurred.");
        assertThat(failure.error().message()).doesNotContain("DB socket reset");

        SpanData execSpan = spanExporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("search.execute"))
            .findFirst()
            .orElseThrow();
        assertThat(execSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(execSpan.getEvents()).anyMatch(e -> e.getName().equals("exception"));
        assertThat(logBuffer.toString(StandardCharsets.UTF_8)).contains("search.internal_error");
    }
}
