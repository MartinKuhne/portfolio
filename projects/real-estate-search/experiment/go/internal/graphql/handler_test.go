package graphql_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/graphql-go/graphql/language/ast"
	"go.opentelemetry.io/otel/codes"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/domain"
	gql "real-estate-search/internal/graphql"
	"real-estate-search/internal/repository"
	"real-estate-search/internal/search"
	"real-estate-search/internal/telemetry"
)

type failingRepo struct {
	repository.Repository
}

func (f *failingRepo) CountMatchingListings(_ context.Context, _ domain.ValidatedSearchInput) (int, error) {
	return 0, fmt.Errorf("Database connection lost")
}

func setupTestHandler(t *testing.T) (*gql.Handler, repository.Repository) {
	repo := repository.NewMemoryRepository()
	ctx := context.Background()

	source := "SEED"
	state := "TX"
	zip := "78701"
	bathrooms := 2.0
	sqft := 1550
	lat := 30.2638
	lon := -97.7443
	status := "active"

	listings := []domain.Listing{
		{
			ID:             "LST-001",
			Source:         &source,
			Address:        "101 Congress Ave",
			City:           "Austin",
			State:          &state,
			Zip:            &zip,
			Price:          450000,
			Bedrooms:       3,
			Bathrooms:      &bathrooms,
			Sqft:           &sqft,
			Latitude:       &lat,
			Longitude:      &lon,
			Status:         &status,
			Description:    "Condo downtown with pool and gym.",
			ListedDate:     "2026-10-20T00:00:00.000Z",
			RelevanceScore: 0.95,
		},
		{
			ID:             "LST-002",
			Source:         &source,
			Address:        "202 Barton Springs Rd",
			City:           "Austin",
			State:          &state,
			Zip:            &zip,
			Price:          550000,
			Bedrooms:       3,
			Bathrooms:      &bathrooms,
			Sqft:           &sqft,
			Latitude:       &lat,
			Longitude:      &lon,
			Status:         &status,
			Description:    "Ranch home with heated pool.",
			ListedDate:     "2026-10-25T00:00:00.000Z",
			RelevanceScore: 0.90,
		},
	}

	if _, err := repo.InsertListingsIdempotently(ctx, listings); err != nil {
		t.Fatalf("failed to insert test listings: %v", err)
	}

	svc := search.NewService(repo)
	schema, err := gql.BuildSchema(svc)
	if err != nil {
		t.Fatalf("failed to build schema: %v", err)
	}

	return gql.NewHandler(schema), repo
}

func TestGraphQL_SearchListings_Success(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchListingsSuccess {
				listings {
					internalId
					id
					source
					address
					city
					state
					zip
					price
					bedrooms
					bathrooms
					sqft
					latitude
					longitude
					status
					description
					listedDate
					relevanceScore
				}
				pagination { page pageSize totalResults totalPages }
			}
			... on SearchError { code message field }
			... on SearchOutOfRangeError { totalPages }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{
				"city":         "Austin",
				"targetBudget": 500000,
				"page":         1,
				"pageSize":     5,
			},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d. Body: %s", w.Code, w.Body.String())
	}

	var res struct {
		Data struct {
			SearchListings struct {
				Typename string `json:"__typename"`
				Listings []struct {
					ID             string   `json:"id"`
					Source         *string  `json:"source"`
					State          *string  `json:"state"`
					Bathrooms      *float64 `json:"bathrooms"`
					Sqft           *int     `json:"sqft"`
					RelevanceScore float64  `json:"relevanceScore"`
				} `json:"listings"`
				Pagination struct {
					Page         int `json:"page"`
					PageSize     int `json:"pageSize"`
					TotalResults int `json:"totalResults"`
					TotalPages   int `json:"totalPages"`
				} `json:"pagination"`
			} `json:"searchListings"`
		} `json:"data"`
		Errors []any `json:"errors"`
	}

	if err := json.Unmarshal(w.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if len(res.Errors) > 0 {
		t.Fatalf("unexpected errors: %+v", res.Errors)
	}

	if res.Data.SearchListings.Typename != "SearchListingsSuccess" {
		t.Errorf("expected SearchListingsSuccess, got %s", res.Data.SearchListings.Typename)
	}
	if len(res.Data.SearchListings.Listings) != 2 {
		t.Errorf("expected 2 listings, got %d", len(res.Data.SearchListings.Listings))
	}
	if res.Data.SearchListings.Pagination.TotalResults != 2 {
		t.Errorf("expected totalResults 2, got %d", res.Data.SearchListings.Pagination.TotalResults)
	}
}

func TestGraphQL_SearchListings_InlineLiterals(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search {
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
			... on SearchError { code message field }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{"query": query})
	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d. Body: %s", w.Code, w.Body.String())
	}

	if !strings.Contains(w.Body.String(), "SearchListingsSuccess") {
		t.Errorf("expected SearchListingsSuccess in body: %s", w.Body.String())
	}
}

func TestGraphQL_SearchListings_EmptyResults(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchListingsSuccess {
				listings { id }
				pagination { totalResults totalPages }
			}
			... on SearchError { code message }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{"city": "NonExistentCity"},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d", w.Code)
	}

	if !strings.Contains(w.Body.String(), `"totalResults":0`) {
		t.Errorf("expected totalResults 0 in body: %s", w.Body.String())
	}
}

func TestGraphQL_SearchListings_ValidationErrorUnion(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchError { code message field }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{"minPrice": "not-a-number"},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 for domain validation error, got %d", w.Code)
	}

	body := w.Body.String()
	if !strings.Contains(body, "SearchValidationError") || !strings.Contains(body, "VALIDATION_ERROR") || !strings.Contains(body, "minPrice") {
		t.Errorf("expected SearchValidationError in body, got: %s", body)
	}
}

func TestGraphQL_SearchListings_OutOfRangeErrorUnion(t *testing.T) {
	handler, _ := setupTestHandler(t)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchError { code message field }
			... on SearchOutOfRangeError { totalPages }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{
				"city":     "Austin",
				"page":     999,
				"pageSize": 5,
			},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 for out of range error, got %d", w.Code)
	}

	body := w.Body.String()
	if !strings.Contains(body, "SearchOutOfRangeError") || !strings.Contains(body, "OUT_OF_RANGE") || !strings.Contains(body, `"totalPages":1`) {
		t.Errorf("expected SearchOutOfRangeError in body, got: %s", body)
	}
}

func TestGraphQL_SearchListings_DatabaseInternalError(t *testing.T) {
	failingRepo := &failingRepo{Repository: repository.NewMemoryRepository()}
	svc := search.NewService(failingRepo)
	schema, err := gql.BuildSchema(svc)
	if err != nil {
		t.Fatalf("failed to build schema: %v", err)
	}
	handler := gql.NewHandler(schema)

	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchError { code message field }
		}
	}`

	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{"city": "Austin"},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200 for internal resolver error, got %d", w.Code)
	}

	body := w.Body.String()
	if !strings.Contains(body, `"searchListings":null`) {
		t.Errorf("expected null searchListings, got: %s", body)
	}
	if !strings.Contains(body, "INTERNAL_SERVER_ERROR") || !strings.Contains(body, "An unexpected internal error occurred.") {
		t.Errorf("expected sanitized INTERNAL_SERVER_ERROR in errors, got: %s", body)
	}
	// Confirm internal DB error detail was NOT leaked to client
	if strings.Contains(body, "Database connection lost") {
		t.Errorf("internal database error detail was leaked: %s", body)
	}
}

func TestGraphQL_MethodNotAllowed(t *testing.T) {
	handler, _ := setupTestHandler(t)
	req := httptest.NewRequest(http.MethodGet, "/graphql", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusMethodNotAllowed {
		t.Errorf("expected 405 Method Not Allowed, got %d", w.Code)
	}
}

type errReader struct{}

func (errReader) Read(p []byte) (n int, err error) {
	return 0, fmt.Errorf("read error")
}

func TestGraphQL_BodyReadError(t *testing.T) {
	handler, _ := setupTestHandler(t)
	req := httptest.NewRequest(http.MethodPost, "/graphql", errReader{})
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400 Bad Request, got %d", w.Code)
	}
}

func TestGraphQL_UnknownFieldPreExecutionError(t *testing.T) {
	handler, _ := setupTestHandler(t)
	reqBody, _ := json.Marshal(map[string]any{
		"query": `query { nonExistentField }`,
	})
	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for unknown field, got %d", w.Code)
	}
}

func TestGraphQL_CORS_OptionsAndAllowedOrigins(t *testing.T) {
	handler, _ := setupTestHandler(t)

	// Allowed origin
	reqAllowed := httptest.NewRequest(http.MethodOptions, "/graphql", nil)
	reqAllowed.Header.Set("Origin", "http://localhost:5173")
	reqAllowed.Header.Set("Access-Control-Request-Method", "POST")
	wAllowed := httptest.NewRecorder()
	handler.ServeHTTP(wAllowed, reqAllowed)

	if wAllowed.Code != http.StatusOK && wAllowed.Code != http.StatusNoContent {
		t.Errorf("expected 200/204 for allowed origin, got %d", wAllowed.Code)
	}
	if wAllowed.Header().Get("Access-Control-Allow-Origin") != "http://localhost:5173" {
		t.Errorf("expected allow origin header set to http://localhost:5173, got %s", wAllowed.Header().Get("Access-Control-Allow-Origin"))
	}
	if wAllowed.Header().Get("Vary") != "Origin" {
		t.Errorf("expected Vary: Origin header, got %q", wAllowed.Header().Get("Vary"))
	}
	if wAllowed.Header().Get("Access-Control-Allow-Methods") != "POST, OPTIONS" {
		t.Errorf("expected Access-Control-Allow-Methods 'POST, OPTIONS', got %q", wAllowed.Header().Get("Access-Control-Allow-Methods"))
	}
	allowHeaders := strings.ToLower(wAllowed.Header().Get("Access-Control-Allow-Headers"))
	if !strings.Contains(allowHeaders, "traceparent") || !strings.Contains(allowHeaders, "tracestate") {
		t.Errorf("expected traceparent and tracestate in allowed headers: %s", allowHeaders)
	}

	// Untrusted origin
	reqBlocked := httptest.NewRequest(http.MethodOptions, "/graphql", nil)
	reqBlocked.Header.Set("Origin", "https://evil.com")
	reqBlocked.Header.Set("Access-Control-Request-Method", "POST")
	wBlocked := httptest.NewRecorder()
	handler.ServeHTTP(wBlocked, reqBlocked)

	if wBlocked.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Errorf("expected empty Access-Control-Allow-Origin for untrusted origin, got %s", wBlocked.Header().Get("Access-Control-Allow-Origin"))
	}
}

func TestGraphQL_ComplexityRules_RootFieldsAndDepth(t *testing.T) {
	handler, _ := setupTestHandler(t)

	// 1. More than 10 root fields
	aliased := make([]string, 11)
	for i := 0; i < 11; i++ {
		aliased[i] = fmt.Sprintf("q%d: searchListings { __typename }", i)
	}
	tooManyAliasesQuery := "query TooManyAliases { " + strings.Join(aliased, " ") + " }"
	reqBody1, _ := json.Marshal(map[string]any{"query": tooManyAliasesQuery})

	req1 := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody1))
	req1.Header.Set("Content-Type", "application/json")
	w1 := httptest.NewRecorder()
	handler.ServeHTTP(w1, req1)

	if w1.Code != http.StatusBadRequest {
		t.Errorf("expected HTTP 400 for root field limit, got %d", w1.Code)
	}
	if !strings.Contains(w1.Body.String(), "maximum allowed root field count") {
		t.Errorf("expected root field limit error in body: %s", w1.Body.String())
	}

	// 2. Query depth > 15
	nested := "__typename"
	for i := 0; i < 16; i++ {
		nested = fmt.Sprintf("... on SearchListingsResult { %s }", nested)
	}
	tooDeepQuery := fmt.Sprintf("query TooDeep { searchListings { %s } }", nested)
	reqBody2, _ := json.Marshal(map[string]any{"query": tooDeepQuery})

	req2 := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody2))
	req2.Header.Set("Content-Type", "application/json")
	w2 := httptest.NewRecorder()
	handler.ServeHTTP(w2, req2)

	if w2.Code != http.StatusBadRequest {
		t.Errorf("expected HTTP 400 for depth limit, got %d", w2.Code)
	}
	if !strings.Contains(w2.Body.String(), "maximum allowed depth") {
		t.Errorf("expected depth limit error in body: %s", w2.Body.String())
	}
}

func TestJSONScalar_ASTParsingAndSecurity(t *testing.T) {
	// Primitives
	if val := gql.ParseLiteral(&ast.StringValue{Value: "hello"}); val != "hello" {
		t.Errorf("expected 'hello', got %v", val)
	}
	if val := gql.ParseLiteral(&ast.IntValue{Value: "42"}); val != 42 {
		t.Errorf("expected 42, got %v", val)
	}
	if val := gql.ParseLiteral(&ast.FloatValue{Value: "42.5"}); val != 42.5 {
		t.Errorf("expected 42.5, got %v", val)
	}
	if val := gql.ParseLiteral(&ast.BooleanValue{Value: true}); val != true {
		t.Errorf("expected true, got %v", val)
	}
	if val := gql.ParseLiteral(&ast.EnumValue{Value: "CUSTOM_ENUM"}); val != "CUSTOM_ENUM" {
		t.Errorf("expected CUSTOM_ENUM, got %v", val)
	}
	if val := gql.ParseLiteral(nil); val != nil {
		t.Errorf("expected nil for nil astNode, got %v", val)
	}

	// List
	listVal := gql.ParseLiteral(&ast.ListValue{
		Values: []ast.Value{
			&ast.IntValue{Value: "1"},
			&ast.IntValue{Value: "2"},
		},
	})
	if slice, ok := listVal.([]any); !ok || len(slice) != 2 || slice[0] != 1 || slice[1] != 2 {
		t.Errorf("unexpected list: %v", listVal)
	}

	// Object
	objVal := gql.ParseLiteral(&ast.ObjectValue{
		Fields: []*ast.ObjectField{
			{
				Name:  &ast.Name{Value: "key"},
				Value: &ast.StringValue{Value: "val"},
			},
		},
	})
	if m, ok := objVal.(map[string]any); !ok || m["key"] != "val" {
		t.Errorf("unexpected object: %v", objVal)
	}

	// Depth limit rejection (> 5 levels): ParseLiteral returns nil (never panics), ParseLiteralChecked returns error
	var deepNode ast.Value = &ast.IntValue{Value: "1"}
	for i := 0; i < 7; i++ {
		deepNode = &ast.ListValue{Values: []ast.Value{deepNode}}
	}
	if val := gql.ParseLiteral(deepNode); val != nil {
		t.Errorf("expected nil for deep literal, got %v", val)
	}
	if _, err := gql.ParseLiteralChecked(deepNode); err == nil || !strings.Contains(err.Error(), "Input literal nesting exceeds maximum allowed depth.") {
		t.Errorf("expected depth limit error, got %v", err)
	}

	// Prototype pollution key rejection: ParseLiteral returns nil (never panics), ParseLiteralChecked returns error
	for _, forbidden := range []string{"__proto__", "constructor", "prototype"} {
		badObj := &ast.ObjectValue{
			Fields: []*ast.ObjectField{
				{
					Name:  &ast.Name{Value: forbidden},
					Value: &ast.StringValue{Value: "attack"},
				},
			},
		}
		if val := gql.ParseLiteral(badObj); val != nil {
			t.Errorf("expected nil for forbidden key %s, got %v", forbidden, val)
		}
		if _, err := gql.ParseLiteralChecked(badObj); err == nil || !strings.Contains(err.Error(), "Invalid input object key.") {
			t.Errorf("expected forbidden key error for %s, got %v", forbidden, err)
		}
	}

	// Serialize and ParseValue pass-through
	if res := gql.JSONScalar.Serialize(123); res != 123 {
		t.Errorf("expected serialize pass-through 123, got %v", res)
	}
	if res := gql.JSONScalar.ParseValue("abc"); res != "abc" {
		t.Errorf("expected parseValue pass-through 'abc', got %v", res)
	}
}

func TestGraphQL_ServeHTTP_LiteralAndVariableSecurity(t *testing.T) {
	handler, _ := setupTestHandler(t)

	// 1. Inline literal nesting > 5 returns HTTP 400 BAD_USER_INPUT without panicking
	deepLiteralQuery := `query { searchListings(input: { minPrice: [[[[[[1]]]]]] }) { __typename } }`
	reqBody, _ := json.Marshal(map[string]any{"query": deepLiteralQuery})
	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), "BAD_USER_INPUT") {
		t.Errorf("expected HTTP 400 BAD_USER_INPUT for deep inline literal, got %d: %s", w.Code, w.Body.String())
	}

	// 2. Inline forbidden keys (__proto__, constructor, prototype) return HTTP 400 BAD_USER_INPUT
	for _, forbidden := range []string{"__proto__", "constructor", "prototype"} {
		q := fmt.Sprintf(`query { searchListings(input: { minPrice: { %s: "x" } }) { __typename } }`, forbidden)
		body, _ := json.Marshal(map[string]any{"query": q})
		r := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, r)
		if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "BAD_USER_INPUT") {
			t.Errorf("expected HTTP 400 BAD_USER_INPUT for inline %s, got %d: %s", forbidden, rec.Code, rec.Body.String())
		}
	}

	// 3. Variable forbidden keys and excessive nesting return HTTP 400 BAD_USER_INPUT
	varQuery := `query Search($input: SearchListingsInput) { searchListings(input: $input) { __typename } }`
	for _, badVars := range []map[string]any{
		{"input": map[string]any{"minPrice": map[string]any{"__proto__": "x"}}},
		{"input": map[string]any{"minPrice": map[string]any{"constructor": "x"}}},
		{"input": map[string]any{"minPrice": map[string]any{"prototype": "x"}}},
		{"input": map[string]any{"minPrice": []any{[]any{[]any{[]any{[]any{[]any{1}}}}}}}},
	} {
		body, _ := json.Marshal(map[string]any{"query": varQuery, "variables": badVars})
		r := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, r)
		if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "BAD_USER_INPUT") {
			t.Errorf("expected HTTP 400 BAD_USER_INPUT for bad variables %+v, got %d: %s", badVars, rec.Code, rec.Body.String())
		}
	}
}

func TestGraphQL_StrictNonCoercion_CityAndKeyword(t *testing.T) {
	handler, _ := setupTestHandler(t)

	// Inline non-string literals must NEVER coerce to string
	for _, literal := range []string{`123`, `true`, `["Austin"]`, `{a: "Austin"}`} {
		for _, field := range []string{"city", "keyword"} {
			q := fmt.Sprintf(`query { searchListings(input: { %s: %s }) { __typename } }`, field, literal)
			body, _ := json.Marshal(map[string]any{"query": q})
			r := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(body))
			r.Header.Set("Content-Type", "application/json")
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, r)
			if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "BAD_USER_INPUT") {
				t.Errorf("expected HTTP 400 BAD_USER_INPUT for inline %s=%s, got %d: %s", field, literal, rec.Code, rec.Body.String())
			}
		}
	}

	// Non-string variables must NEVER coerce to string
	varQuery := `query Search($input: SearchListingsInput) { searchListings(input: $input) { __typename } }`
	for _, bad := range []any{123, true, []any{"Austin"}, map[string]any{"a": 1}} {
		for _, field := range []string{"city", "keyword"} {
			body, _ := json.Marshal(map[string]any{
				"query":     varQuery,
				"variables": map[string]any{"input": map[string]any{field: bad}},
			})
			r := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(body))
			r.Header.Set("Content-Type", "application/json")
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, r)
			if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "BAD_USER_INPUT") {
				t.Errorf("expected HTTP 400 BAD_USER_INPUT for variable %s=%#v, got %d: %s", field, bad, rec.Code, rec.Body.String())
			}
		}
	}
}

func TestGraphQL_FragmentComplexityBypassesAndCycles(t *testing.T) {
	handler, _ := setupTestHandler(t)

	// 1. Inline fragment > 10 root fields
	aliased := make([]string, 11)
	for i := 0; i < 11; i++ {
		aliased[i] = fmt.Sprintf("q%d: searchListings { __typename }", i)
	}
	inlineBypass := fmt.Sprintf("query { ... on Query { %s } }", strings.Join(aliased, " "))

	// 2. Named fragment spread > 10 root fields
	spreadBypass := fmt.Sprintf("query { ...Many } fragment Many on Query { %s }", strings.Join(aliased, " "))

	// 3. Named fragment spread > 15 depth
	nested := "__typename"
	for i := 0; i < 16; i++ {
		nested = fmt.Sprintf("... on SearchListingsResult { %s }", nested)
	}
	deepSpread := fmt.Sprintf("query { searchListings { ...Deep } } fragment Deep on SearchListingsResult { %s }", nested)

	// 4. Fragment cycle
	cycleQuery := `query { searchListings { ...A } } fragment A on SearchListingsResult { ...B } fragment B on SearchListingsResult { ...A }`

	for _, tc := range []struct {
		name  string
		query string
	}{
		{"inline fragment root fields", inlineBypass},
		{"named fragment root fields", spreadBypass},
		{"named fragment depth", deepSpread},
		{"fragment cycle", cycleQuery},
	} {
		t.Run(tc.name, func(t *testing.T) {
			body, _ := json.Marshal(map[string]any{"query": tc.query})
			r := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(body))
			r.Header.Set("Content-Type", "application/json")
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, r)
			if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "BAD_USER_INPUT") {
				t.Errorf("%s: expected HTTP 400 BAD_USER_INPUT, got %d: %s", tc.name, rec.Code, rec.Body.String())
			}
		})
	}
}

func TestGraphQL_BodySizeLimit(t *testing.T) {
	handler, _ := setupTestHandler(t)

	oversized := strings.Repeat("a", (1<<20)+100)
	req := httptest.NewRequest(http.MethodPost, "/graphql", strings.NewReader(oversized))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("expected HTTP 413 for >1MiB body, got %d: %s", w.Code, w.Body.String())
	}
}

func TestGraphQL_PartialDataAndLogStatusAttribute(t *testing.T) {
	ctx := context.Background()
	exporter := tracetest.NewInMemoryExporter()
	processor := sdktrace.NewSimpleSpanProcessor(exporter)
	_ = telemetry.ShutdownTelemetry(ctx)
	_ = telemetry.InitTelemetry(ctx, func(opts *telemetry.TelemetryOptions) {
		opts.SpanProcessors = []sdktrace.SpanProcessor{processor}
	})
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	var logBuf bytes.Buffer
	telemetry.SetLogOutput(&logBuf)
	defer telemetry.SetLogOutput(nil)

	failRepo := &failingRepo{Repository: repository.NewMemoryRepository()}
	svc := search.NewService(failRepo)
	schema, err := gql.BuildSchema(svc)
	if err != nil {
		t.Fatalf("failed to build schema: %v", err)
	}
	handler := gql.NewHandler(schema)

	// Aliased field must be preserved in result.Data when resolver fails
	query := `query { first: searchListings(input: { city: "Austin" }) { __typename } }`
	reqBody, _ := json.Marshal(map[string]any{"query": query})
	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d", w.Code)
	}

	var resp map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	dataMap, ok := resp["data"].(map[string]any)
	if !ok {
		t.Fatalf("expected data map, got %T", resp["data"])
	}
	if _, hasAlias := dataMap["first"]; !hasAlias {
		t.Errorf("expected aliased field 'first' to be preserved in data, got: %+v", dataMap)
	}

	// Verify server span status is Error
	var foundErrorServerSpan bool
	for _, s := range exporter.GetSpans() {
		if s.Name == "POST /graphql" && s.Status.Code == codes.Error {
			foundErrorServerSpan = true
		}
	}
	if !foundErrorServerSpan {
		t.Errorf("expected POST /graphql server span to have codes.Error status")
	}

	// Verify log entry uses http.response.status_code and http.request.failed
	logs := logBuf.String()
	if !strings.Contains(logs, `"http.request.failed"`) {
		t.Errorf("expected http.request.failed log event, got: %s", logs)
	}
	if !strings.Contains(logs, `"http.response.status_code":200`) {
		t.Errorf("expected http.response.status_code attribute in logs, got: %s", logs)
	}
	if strings.Contains(logs, `"http.response.status":`) {
		t.Errorf("unexpected stale http.response.status key in logs: %s", logs)
	}
}

func TestGraphQL_DecimalMinPriceEndToEnd(t *testing.T) {
	handler, _ := setupTestHandler(t)

	// Setup has LST-001 at 450000 and LST-002 at 550000.
	// minPrice: 450000.5 must exclude LST-001 (450000) and return only LST-002 (550000).
	query := `query Search($input: SearchListingsInput!) {
		searchListings(input: $input) {
			__typename
			... on SearchListingsSuccess {
				listings { id price }
				pagination { totalResults }
			}
		}
	}`
	reqBody, _ := json.Marshal(map[string]any{
		"query": query,
		"variables": map[string]any{
			"input": map[string]any{
				"minPrice": 450000.5,
			},
		},
	})

	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d", w.Code)
	}
	body := w.Body.String()
	if !strings.Contains(body, "LST-002") || strings.Contains(body, "LST-001") {
		t.Errorf("expected only LST-002 when minPrice=450000.5, got: %s", body)
	}
}

func TestGraphQL_TraceparentPropagation(t *testing.T) {
	ctx := context.Background()
	exporter := tracetest.NewInMemoryExporter()
	processor := sdktrace.NewSimpleSpanProcessor(exporter)

	_ = telemetry.InitTelemetry(ctx, func(opts *telemetry.TelemetryOptions) {
		opts.SpanProcessors = []sdktrace.SpanProcessor{processor}
	})
	defer func() { _ = telemetry.ShutdownTelemetry(ctx) }()

	handler, _ := setupTestHandler(t)

	incomingTraceID := "11111111222222223333333344444444"
	incomingSpanID := "5555555566666666"
	traceparent := fmt.Sprintf("00-%s-%s-01", incomingTraceID, incomingSpanID)

	query := `query Search { searchListings { __typename } }`
	reqBody, _ := json.Marshal(map[string]any{"query": query})
	req := httptest.NewRequest(http.MethodPost, "/graphql", bytes.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("TraceParent", traceparent)

	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected HTTP 200, got %d", w.Code)
	}

	spans := exporter.GetSpans()
	var serverSpan *tracetest.SpanStub
	for _, s := range spans {
		if s.Name == "POST /graphql" {
			spanCopy := s
			serverSpan = &spanCopy
			break
		}
	}

	if serverSpan == nil {
		t.Fatalf("expected POST /graphql server span")
	}
	if serverSpan.SpanKind != trace.SpanKindServer {
		t.Errorf("expected SpanKind.SERVER, got %v", serverSpan.SpanKind)
	}
	if serverSpan.SpanContext.TraceID().String() != incomingTraceID {
		t.Errorf("expected traceId %s, got %s", incomingTraceID, serverSpan.SpanContext.TraceID().String())
	}
	if serverSpan.Parent.SpanID().String() != incomingSpanID {
		t.Errorf("expected parentSpanId %s, got %s", incomingSpanID, serverSpan.Parent.SpanID().String())
	}
}
