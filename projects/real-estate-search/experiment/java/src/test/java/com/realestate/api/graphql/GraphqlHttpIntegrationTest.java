package com.realestate.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realestate.api.db.InMemoryListingRepository;
import com.realestate.api.db.SeedListings;
import com.realestate.api.domain.ListingRepository;
import com.realestate.api.scoring.DefaultRelevanceScorer;
import com.realestate.api.search.SearchService;
import com.realestate.api.telemetry.ApiTelemetry;
import com.realestate.api.telemetry.TelemetryConfig;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphqlHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private InMemorySpanExporter spanExporter;
    private ByteArrayOutputStream logBuffer;
    private ApiTelemetry telemetry;
    private GraphqlHttpApp app;
    private String baseUrl;

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
        InMemoryListingRepository repo = new InMemoryListingRepository(new DefaultRelevanceScorer(), telemetry.tracer());
        SeedListings.seedDatabase(repo);
        SearchService service = new SearchService(repo);
        // Use explicit service wired with our test telemetry
        service = new SearchService(repo, new com.realestate.api.validation.DefaultSearchValidator(), telemetry);
        app = new GraphqlHttpApp(service, telemetry, CorsConfig.fromEnvValue(null)).start(0);
        baseUrl = "http://127.0.0.1:" + app.port() + "/graphql";
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        if (telemetry != null) {
            telemetry.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsMatchingListingsAndPaginationOnValidQuery() throws Exception {
        String query = """
            query Search($input: SearchListingsInput!) {
              searchListings(input: $input) {
                __typename
                ... on SearchListingsSuccess {
                  listings {
                    internalId id source address city state zip
                    price bedrooms bathrooms sqft latitude longitude
                    status description listedDate relevanceScore
                  }
                  pagination { page pageSize totalResults totalPages }
                }
                ... on SearchError { code message field }
                ... on SearchOutOfRangeError { totalPages }
              }
            }
            """;

        HttpResponse<String> response = postGraphql(Map.of(
            "query", query,
            "variables", Map.of("input", Map.of("city", "Austin", "targetBudget", 500000, "page", 1, "pageSize", 5))
        ), Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> body = parseJson(response.body());
        assertThat(body).doesNotContainKey("errors");
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        Map<String, Object> result = (Map<String, Object>) data.get("searchListings");
        assertThat(result.get("__typename")).isEqualTo("SearchListingsSuccess");

        List<Map<String, Object>> listings = (List<Map<String, Object>>) result.get("listings");
        assertThat(listings).isNotEmpty();
        Map<String, Object> first = listings.get(0);
        assertThat((String) first.get("internalId")).matches("^[0-9a-f-]{36}$");
        assertThat(first.get("source")).isEqualTo("SEED");
        assertThat(first.get("state")).isEqualTo("TX");
        assertThat((String) first.get("zip")).matches("^\\d{5}$");
        assertThat(first.get("bathrooms")).isInstanceOf(Number.class);
        assertThat(first.get("sqft")).isInstanceOf(Number.class);
        assertThat(first.get("latitude")).isInstanceOf(Number.class);
        assertThat(first.get("longitude")).isInstanceOf(Number.class);
        assertThat(first.get("status")).isEqualTo("active");

        Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
        assertThat(pagination.get("page")).isEqualTo(1);
        assertThat(pagination.get("pageSize")).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void supportsInlineLiteralsOmittedInputAndZeroMatches() throws Exception {
        HttpResponse<String> inlineRes = postGraphql(Map.of(
            "query", """
                query Search {
                  searchListings(input: {
                    city: "Austin"
                    minPrice: 100000
                    maxPrice: 900000
                    minBedrooms: 2
                    targetBudget: 500000
                    page: 1
                    pageSize: 5
                  }) {
                    __typename
                    ... on SearchListingsSuccess {
                      listings { id city }
                      pagination { page pageSize totalResults totalPages }
                    }
                  }
                }
                """
        ), Map.of());
        assertThat(inlineRes.statusCode()).isEqualTo(200);
        Map<String, Object> inlineResult = (Map<String, Object>) ((Map<String, Object>) parseJson(inlineRes.body()).get("data")).get("searchListings");
        assertThat(inlineResult.get("__typename")).isEqualTo("SearchListingsSuccess");

        HttpResponse<String> omittedRes = postGraphql(Map.of(
            "query", "query { searchListings { __typename ... on SearchListingsSuccess { pagination { page } } } }"
        ), Map.of());
        assertThat(omittedRes.statusCode()).isEqualTo(200);
        Map<String, Object> omittedResult = (Map<String, Object>) ((Map<String, Object>) parseJson(omittedRes.body()).get("data")).get("searchListings");
        assertThat(((Map<String, Object>) omittedResult.get("pagination")).get("page")).isEqualTo(1);

        HttpResponse<String> emptyRes = postGraphql(Map.of(
            "query", "query($input: SearchListingsInput!) { searchListings(input: $input) { __typename ... on SearchListingsSuccess { listings { id } pagination { totalResults totalPages } } } }",
            "variables", Map.of("input", Map.of("city", "NonExistentCity"))
        ), Map.of());
        assertThat(emptyRes.statusCode()).isEqualTo(200);
        Map<String, Object> emptyResult = (Map<String, Object>) ((Map<String, Object>) parseJson(emptyRes.body()).get("data")).get("searchListings");
        assertThat((List<?>) emptyResult.get("listings")).isEmpty();
        assertThat(((Map<String, Object>) emptyResult.get("pagination")).get("totalResults")).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsHttp200UnionVariantsForValidationAndOutOfRangeErrorsAndLeavesServerSpanUnset() throws Exception {
        String fullQuery = """
            query Search($input: SearchListingsInput!) {
              searchListings(input: $input) {
                __typename
                ... on SearchListingsSuccess {
                  listings { id }
                  pagination { page }
                }
                ... on SearchError { code message field }
                ... on SearchOutOfRangeError { totalPages }
              }
            }
            """;

        HttpResponse<String> valRes = postGraphql(Map.of(
            "query", fullQuery,
            "variables", Map.of("input", Map.of("minPrice", "not-a-number"))
        ), Map.of());

        assertThat(valRes.statusCode()).isEqualTo(200);
        Map<String, Object> valBody = parseJson(valRes.body());
        assertThat(valBody).doesNotContainKey("errors");
        Map<String, Object> valResult = (Map<String, Object>) ((Map<String, Object>) valBody.get("data")).get("searchListings");
        assertThat(valResult.get("__typename")).isEqualTo("SearchValidationError");
        assertThat(valResult.get("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(valResult.get("field")).isEqualTo("minPrice");
        assertThat((String) valResult.get("message")).contains("minPrice");

        SpanData serverSpanDomain = spanExporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals("POST /graphql"))
            .findFirst()
            .orElseThrow();
        assertThat(serverSpanDomain.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(logBuffer.toString(StandardCharsets.UTF_8)).contains("http.request.rejected");

        spanExporter.reset();
        HttpResponse<String> rangeRes = postGraphql(Map.of(
            "query", fullQuery,
            "variables", Map.of("input", Map.of("city", "Austin", "page", 999, "pageSize", 5))
        ), Map.of());

        assertThat(rangeRes.statusCode()).isEqualTo(200);
        Map<String, Object> rangeResult = (Map<String, Object>) ((Map<String, Object>) parseJson(rangeRes.body()).get("data")).get("searchListings");
        assertThat(rangeResult.get("__typename")).isEqualTo("SearchOutOfRangeError");
        assertThat(rangeResult.get("code")).isEqualTo("OUT_OF_RANGE");
        assertThat(rangeResult.get("field")).isEqualTo("page");
        assertThat((Integer) rangeResult.get("totalPages")).isGreaterThan(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsSanitizedInternalServerErrorWithNullSearchListingsAndSetsServerSpanError() throws Exception {
        ListingRepository brokenRepo = mock(ListingRepository.class);
        when(brokenRepo.countMatchingListings(any())).thenThrow(new RuntimeException("Database connection lost"));
        SearchService brokenService = new SearchService(brokenRepo, new com.realestate.api.validation.DefaultSearchValidator(), telemetry);
        GraphqlHttpApp brokenApp = new GraphqlHttpApp(brokenService, telemetry, CorsConfig.fromEnvValue(null)).start(0);
        try {
            HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + brokenApp.port() + "/graphql"))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of(
                        "query", "query { searchListings { __typename } }"
                    ))))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertThat(res.statusCode()).isEqualTo(200);
            Map<String, Object> body = parseJson(res.body());
            assertThat(((Map<String, Object>) body.get("data")).get("searchListings")).isNull();
            List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).get("message")).isEqualTo("An unexpected database error occurred.");
            assertThat((String) errors.get(0).get("message")).doesNotContain("Database connection lost");
            assertThat(((Map<String, Object>) errors.get(0).get("extensions")).get("code")).isEqualTo("INTERNAL_SERVER_ERROR");

            SpanData serverSpan = spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("POST /graphql"))
                .findFirst()
                .orElseThrow();
            assertThat(serverSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(logBuffer.toString(StandardCharsets.UTF_8)).contains("http.request.failed");
        } finally {
            brokenApp.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void catchesUnexpectedExceptionThrownDirectlyBySearchService() throws Exception {
        SearchService explodingService = new SearchService(
            new InMemoryListingRepository(),
            new com.realestate.api.validation.DefaultSearchValidator(),
            telemetry
        ) {
            @Override
            public com.realestate.api.domain.SearchResult executeSearch(com.realestate.api.domain.RawSearchInput rawInput) {
                throw new RuntimeException("Catastrophic failure");
            }
        };
        GraphqlHttpApp explodingApp = new GraphqlHttpApp(explodingService, telemetry, CorsConfig.fromEnvValue(null)).start(0);
        try {
            HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + explodingApp.port() + "/graphql"))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(Map.of(
                        "query", "query { searchListings { __typename } }"
                    ))))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertThat(res.statusCode()).isEqualTo(200);
            Map<String, Object> body = parseJson(res.body());
            assertThat(((Map<String, Object>) body.get("data")).get("searchListings")).isNull();
            List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
            assertThat(errors.get(0).get("message")).isEqualTo("An unexpected internal error occurred.");
            assertThat((String) errors.get(0).get("message")).doesNotContain("Catastrophic failure");
            assertThat(((Map<String, Object>) errors.get(0).get("extensions")).get("code")).isEqualTo("INTERNAL_SERVER_ERROR");
            assertThat(logBuffer.toString(StandardCharsets.UTF_8)).contains("graphql.resolver_error");
        } finally {
            explodingApp.stop();
        }
    }

    @Test
    void returnsHttp400ForMalformedJsonSyntaxErrorsComplexityAndForbiddenScalarInputs() throws Exception {
        HttpResponse<String> malformed = postRaw("invalid-json-payload");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(parseJson(malformed.body())).containsKey("errors");

        HttpResponse<String> emptyBody = postRaw("");
        assertThat(emptyBody.statusCode()).isEqualTo(400);

        HttpResponse<String> missingQuery = postGraphql(Map.of("variables", Map.of()), Map.of());
        assertThat(missingQuery.statusCode()).isEqualTo(400);

        HttpResponse<String> badVars = postGraphql(Map.of("query", "{ searchListings { __typename } }", "variables", "not-an-object"), Map.of());
        assertThat(badVars.statusCode()).isEqualTo(400);

        HttpResponse<String> syntaxErr = postGraphql(Map.of("query", "query { searchListings("), Map.of());
        assertThat(syntaxErr.statusCode()).isEqualTo(400);

        StringBuilder aliased = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            aliased.append("q").append(i).append(": searchListings { __typename } ");
        }
        HttpResponse<String> aliasRes = postGraphql(Map.of("query", "query { " + aliased + " }"), Map.of());
        assertThat(aliasRes.statusCode()).isEqualTo(400);
        assertThat(aliasRes.body()).contains("maximum allowed root field count");

        String nested = "__typename";
        for (int i = 0; i < 16; i++) {
            nested = "... on SearchListingsResult { " + nested + " }";
        }
        HttpResponse<String> depthRes = postGraphql(Map.of("query", "query { searchListings { " + nested + " } }"), Map.of());
        assertThat(depthRes.statusCode()).isEqualTo(400);
        assertThat(depthRes.body()).contains("maximum allowed depth");

        HttpResponse<String> protoRes = postGraphql(Map.of(
            "query", "query { searchListings(input: { minPrice: { __proto__: \"evil\" } }) { __typename } }"
        ), Map.of());
        assertThat(protoRes.statusCode()).isEqualTo(400);
    }

    @Test
    void handlesCorsPreflightAndTraceHeaderAllowlist() throws Exception {
        HttpResponse<String> allowed = HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type, traceparent, tracestate")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(allowed.statusCode()).isIn(200, 204);
        assertThat(allowed.headers().firstValue("access-control-allow-origin")).contains("http://localhost:5173");
        assertThat(allowed.headers().firstValue("access-control-allow-credentials")).isEmpty();
        String allowHeaders = allowed.headers().firstValue("access-control-allow-headers").orElse("").toLowerCase();
        assertThat(allowHeaders).contains("traceparent").contains("tracestate");

        HttpResponse<String> blocked = HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.com")
                .header("Access-Control-Request-Method", "POST")
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(blocked.headers().firstValue("access-control-allow-origin")).isEmpty();
        assertThat(blocked.headers().firstValue("access-control-allow-credentials")).isEmpty();
    }

    @Test
    void propagatesIncomingW3cTraceparentAcrossHttpGraphqlSearchAndLogs() throws Exception {
        String traceId = "11111111222222223333333344444444";
        String parentSpanId = "5555555566666666";
        String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";

        HttpResponse<String> res = postGraphql(
            Map.of(
                "query", "query Search($input: SearchListingsInput!) { searchListings(input: $input) { __typename ... on SearchListingsSuccess { listings { id } pagination { totalResults } } } }",
                "variables", Map.of("input", Map.of("city", "Austin", "page", 1, "pageSize", 5))
            ),
            Map.of("TraceParent", traceparent, "TraceState", "vendor=1")
        );

        assertThat(res.statusCode()).isEqualTo(200);
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        List<String> names = spans.stream().map(SpanData::getName).toList();
        assertThat(names).contains(
            "POST /graphql",
            "graphql.searchListings",
            "search.execute",
            "search.validateInput",
            "search.db.count",
            "search.db.query"
        );

        SpanData serverSpan = spans.stream().filter(s -> s.getName().equals("POST /graphql")).findFirst().orElseThrow();
        assertThat(serverSpan.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(serverSpan.getTraceId()).isEqualTo(traceId);
        assertThat(serverSpan.getParentSpanId()).isEqualTo(parentSpanId);
        assertThat(serverSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);

        for (SpanData s : spans) {
            assertThat(s.getTraceId()).isEqualTo(traceId);
        }

        List<Map<String, Object>> logs = logBuffer.toString(StandardCharsets.UTF_8)
            .lines()
            .map(line -> {
                try {
                    return MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .toList();
        assertThat(logs).hasSizeGreaterThanOrEqualTo(3);
        for (Map<String, Object> entry : logs) {
            assertThat(entry.get("traceId")).isEqualTo(traceId);
            assertThat((String) entry.get("spanId")).matches("^[0-9a-f]{16}$");
            assertThat(entry.get("service")).isEqualTo("real-estate-api");
        }
        assertThat(logs.stream().map(e -> (String) e.get("body")).toList())
            .contains("search.started", "search.completed", "http.request.completed");
    }

    @Test
    void supportsGetGraphqlEndpoint() throws Exception {
        String encodedQuery = URLEncoder.encode("query Op { searchListings { __typename } }", StandardCharsets.UTF_8);
        String encodedVars = URLEncoder.encode("{\"x\":1}", StandardCharsets.UTF_8);
        HttpResponse<String> okRes = HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "?query=" + encodedQuery + "&variables=" + encodedVars + "&operationName=Op"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(okRes.statusCode()).isEqualTo(200);

        HttpResponse<String> badRes = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(badRes.statusCode()).isEqualTo(400);

        String badVars = URLEncoder.encode("{bad", StandardCharsets.UTF_8);
        HttpResponse<String> badGetVars = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl + "?query=" + encodedQuery + "&variables=" + badVars)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(badGetVars.statusCode()).isEqualTo(400);

        HttpResponse<String> nullGetVars = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl + "?query=" + encodedQuery + "&variables=null")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(nullGetVars.statusCode()).isEqualTo(400);
    }

    @Test
    void handlesPostEdgePayloadsAndUnexpectedHttpHandlerExceptions() throws Exception {
        HttpResponse<String> nullJson = postRaw("null");
        assertThat(nullJson.statusCode()).isEqualTo(400);

        HttpResponse<String> badOpName = postGraphql(Map.of(
            "query", "query Op { searchListings { __typename } }",
            "operationName", 123
        ), Map.of());
        assertThat(badOpName.statusCode()).isEqualTo(400);

        HttpResponse<String> withOpName = postGraphql(Map.of(
            "query", "query Op { searchListings { __typename } }",
            "operationName", "Op"
        ), Map.of());
        assertThat(withOpName.statusCode()).isEqualTo(200);

        // Trigger unhandled runtime exception inside HTTP handler via failing PreparsedDocumentProvider
        graphql.GraphQL brokenGraphQL = graphql.GraphQL.newGraphQL(
                GraphqlSchemaFactory.buildSchema(
                    new SearchService(new InMemoryListingRepository(), new com.realestate.api.validation.DefaultSearchValidator(), telemetry),
                    telemetry
                )
            )
            .preparsedDocumentProvider((executionInput, parseFn) -> {
                throw new RuntimeException("Unhandled engine failure");
            })
            .build();
        GraphqlHttpApp faultyApp = new GraphqlHttpApp(brokenGraphQL, telemetry, CorsConfig.fromEnvValue(null)).start(0);
        try {
            HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + faultyApp.port() + "/graphql"))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ searchListings { __typename } }\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertThat(res.statusCode()).isEqualTo(500);
            assertThat(res.body()).contains("INTERNAL_SERVER_ERROR");
            assertThat(res.body()).doesNotContain("Unhandled engine failure");
            assertThat(faultyApp.javalin()).isNotNull();
        } finally {
            faultyApp.stop();
        }
    }

    private HttpResponse<String> postGraphql(Map<String, Object> body, Map<String, String> headers) throws Exception {
        return postRaw(MAPPER.writeValueAsString(body), headers);
    }

    private HttpResponse<String> postRaw(String rawBody) throws Exception {
        return postRaw(rawBody, Map.of());
    }

    private HttpResponse<String> postRaw(String rawBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(rawBody));
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> parseJson(String raw) throws Exception {
        return MAPPER.readValue(raw, new TypeReference<>() {});
    }
}
