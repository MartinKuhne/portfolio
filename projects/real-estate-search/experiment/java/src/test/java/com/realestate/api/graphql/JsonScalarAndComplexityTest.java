package com.realestate.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.ExecutionInput;
import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.execution.CoercedVariables;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.Document;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.parser.Parser;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonScalarAndComplexityTest {

    private final QueryComplexityValidator complexityValidator = new QueryComplexityValidator();

    @Test
    void parsesAllJsonScalarAstKinds() {
        assertThat(JsonScalar.parseLiteral(StringValue.of("test"))).isEqualTo("test");
        assertThat(JsonScalar.parseLiteral(BooleanValue.of(true))).isEqualTo(true);
        assertThat(JsonScalar.parseLiteral(IntValue.newIntValue(BigInteger.valueOf(42)).build())).isEqualTo(42);
        assertThat(JsonScalar.parseLiteral(IntValue.newIntValue(BigInteger.valueOf(3_000_000_000L)).build()))
            .isEqualTo(3_000_000_000L);
        assertThat(JsonScalar.parseLiteral(FloatValue.newFloatValue(new BigDecimal("42.5")).build())).isEqualTo(42.5);
        assertThat(JsonScalar.parseLiteral(NullValue.of())).isNull();

        ArrayValue arr = ArrayValue.newArrayValue()
            .values(List.of(IntValue.newIntValue(BigInteger.ONE).build(), IntValue.newIntValue(BigInteger.TWO).build()))
            .build();
        assertThat(JsonScalar.parseLiteral(arr)).isEqualTo(List.of(1, 2));

        ObjectValue obj = ObjectValue.newObjectValue()
            .objectField(ObjectField.newObjectField().name("key").value(StringValue.of("val")).build())
            .build();
        assertThat(JsonScalar.parseLiteral(obj)).isEqualTo(Map.of("key", "val"));

        VariableReference varRef = VariableReference.newVariableReference().name("myVar").build();
        assertThat(JsonScalar.parseLiteral(varRef)).isNull();
        assertThat(JsonScalar.parseLiteral(varRef, CoercedVariables.of(Map.of("myVar", 123)))).isEqualTo(123);
    }

    @Test
    void rejectsDeeplyNestedAstLiteralsAndForbiddenKeys() {
        Value<?> deepList = ArrayValue.newArrayValue()
            .values(List.of(IntValue.newIntValue(BigInteger.ONE).build()))
            .build();
        for (int i = 0; i < 6; i++) {
            deepList = ArrayValue.newArrayValue().values(List.of(deepList)).build();
        }
        Value<?> tooDeep = deepList;
        assertThatThrownBy(() -> JsonScalar.parseLiteral(tooDeep))
            .isInstanceOf(CoercingParseLiteralException.class)
            .hasMessageContaining("nesting exceeds maximum allowed depth");

        for (String forbidden : List.of("__proto__", "constructor", "prototype")) {
            ObjectValue badObj = ObjectValue.newObjectValue()
                .objectField(ObjectField.newObjectField().name(forbidden).value(StringValue.of("bad")).build())
                .build();
            assertThatThrownBy(() -> JsonScalar.parseLiteral(badObj))
                .isInstanceOf(CoercingParseLiteralException.class)
                .hasMessageContaining("Invalid input object key");
        }
    }

    @Test
    void validatesRuntimeParseValueSerializeAndValueToLiteral() {
        var coercing = JsonScalar.INSTANCE.getCoercing();
        GraphQLContext ctx = GraphQLContext.getDefault();
        Locale locale = Locale.US;

        assertThat(coercing.serialize(123, ctx, locale)).isEqualTo(123);
        assertThat(coercing.parseValue("abc", ctx, locale)).isEqualTo("abc");
        assertThat(coercing.parseValue(Map.of("a", List.of(1, 2)), ctx, locale))
            .isEqualTo(Map.of("a", List.of(1, 2)));

        assertThatThrownBy(() -> coercing.parseValue(Map.of("__proto__", "evil"), ctx, locale))
            .isInstanceOf(CoercingParseValueException.class)
            .hasMessageContaining("Invalid input object key");

        List<Object> deepRuntimeList = List.of(1);
        for (int i = 0; i < 6; i++) {
            deepRuntimeList = List.of(deepRuntimeList);
        }
        List<Object> finalDeep = deepRuntimeList;
        assertThatThrownBy(() -> coercing.parseValue(finalDeep, ctx, locale))
            .isInstanceOf(CoercingParseValueException.class)
            .hasMessageContaining("nesting exceeds maximum allowed depth");

        Value<?> literal = coercing.valueToLiteral(Map.of("k", List.of(1, true, 2.5, "s")), ctx, locale);
        assertThat(literal).isInstanceOf(ObjectValue.class);
    }

    @Test
    void enforcesRootFieldLimitAndSelectionDepthAcrossInlineAndNamedFragments() {
        StringBuilder tenFields = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            tenFields.append("q").append(i).append(": searchListings { __typename } ");
        }
        Document validDoc = Parser.parse("query ValidTen { " + tenFields + " }");
        assertThat(complexityValidator.validateComplexity(validDoc)).isEmpty();

        StringBuilder elevenFields = new StringBuilder(tenFields);
        elevenFields.append("q10: searchListings { __typename }");
        Document tooManyDoc = Parser.parse("query TooMany { " + elevenFields + " }");
        List<GraphQLError> tooManyErrors = complexityValidator.validateComplexity(tooManyDoc);
        assertThat(tooManyErrors).hasSize(1);
        assertThat(tooManyErrors.get(0).getMessage()).contains("maximum allowed root field count (10)");

        String nested = "__typename";
        for (int i = 0; i < 16; i++) {
            nested = "... on SearchListingsResult { " + nested + " }";
        }
        Document tooDeepDoc = Parser.parse("query TooDeep { searchListings { " + nested + " } }");
        List<GraphQLError> depthErrors = complexityValidator.validateComplexity(tooDeepDoc);
        assertThat(depthErrors).hasSize(1);
        assertThat(depthErrors.get(0).getMessage()).contains("maximum allowed depth (15)");

        StringBuilder fragQuery = new StringBuilder("query FragDeep { searchListings { ...F0 } } ");
        for (int i = 0; i < 16; i++) {
            fragQuery.append("fragment F").append(i).append(" on SearchListingsResult { ");
            fragQuery.append(i == 15 ? "__typename" : "...F" + (i + 1));
            fragQuery.append(" } ");
        }
        Document fragDeepDoc = Parser.parse(fragQuery.toString());
        assertThat(complexityValidator.validateComplexity(fragDeepDoc))
            .anyMatch(err -> err.getMessage().contains("maximum allowed depth (15)"));
    }

    @Test
    void propagatesDepthAcrossLiteralAndVariableReferencesAndThrowsParseLiteralException() {
        var coercing = JsonScalar.INSTANCE.getCoercing();
        GraphQLContext ctx = GraphQLContext.getDefault();
        Locale locale = Locale.US;

        // Literal depth 3 ({a: {b: {c: $v}}}) + variable depth 3 ({d: {e: {f: 1}}}) = total depth 6 > 5
        VariableReference varRef = VariableReference.newVariableReference().name("v").build();
        ObjectValue nestedLiteral = ObjectValue.newObjectValue()
            .objectField(ObjectField.newObjectField()
                .name("a")
                .value(ObjectValue.newObjectValue()
                    .objectField(ObjectField.newObjectField()
                        .name("b")
                        .value(ObjectValue.newObjectValue()
                            .objectField(ObjectField.newObjectField().name("c").value(varRef).build())
                            .build())
                        .build())
                    .build())
                .build())
            .build();

        CoercedVariables tooDeepVars = CoercedVariables.of(Map.of(
            "v", Map.of("d", Map.of("e", Map.of("f", 1)))
        ));
        assertThatThrownBy(() -> coercing.parseLiteral(nestedLiteral, tooDeepVars, ctx, locale))
            .isInstanceOf(CoercingParseLiteralException.class)
            .hasMessageContaining("nesting exceeds maximum allowed depth");

        // Total depth 5 (3 literal + 2 variable) succeeds
        CoercedVariables okVars = CoercedVariables.of(Map.of(
            "v", Map.of("d", Map.of("e", 1))
        ));
        assertThat(coercing.parseLiteral(nestedLiteral, okVars, ctx, locale)).isInstanceOf(Map.class);

        // Forbidden key inside variable referenced from literal throws CoercingParseLiteralException
        CoercedVariables badKeyVars = CoercedVariables.of(Map.of(
            "v", Map.of("__proto__", "polluted")
        ));
        assertThatThrownBy(() -> coercing.parseLiteral(nestedLiteral, badKeyVars, ctx, locale))
            .isInstanceOf(CoercingParseLiteralException.class)
            .hasMessageContaining("Invalid input object key");
    }

    @Test
    void blocksRootFragmentBypassesCyclesDiamondAmplificationAndEnforcesBoundedLruCache() {
        // 1. Named fragment at root expanding to 11 fields
        StringBuilder rootFragFields = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            rootFragFields.append("q").append(i).append(": searchListings { __typename } ");
        }
        Document rootNamedFragDoc = Parser.parse(
            "query RootFragBypass { ...R } fragment R on Query { " + rootFragFields + " }"
        );
        assertThat(complexityValidator.validateComplexity(rootNamedFragDoc))
            .anyMatch(err -> err.getMessage().contains("maximum allowed root field count (10)"));

        // 2. Inline fragment at root expanding to 11 fields
        Document rootInlineFragDoc = Parser.parse(
            "query RootInlineBypass { ... on Query { " + rootFragFields + " } }"
        );
        assertThat(complexityValidator.validateComplexity(rootInlineFragDoc))
            .anyMatch(err -> err.getMessage().contains("maximum allowed root field count (10)"));

        // 3. Fragment cycle terminates safely without StackOverflowError
        Document cycleDoc = Parser.parse(
            "query Cycle { searchListings { ...A } } "
                + "fragment A on SearchListingsResult { ...B } "
                + "fragment B on SearchListingsResult { ...A }"
        );
        assertThat(complexityValidator.validateComplexity(cycleDoc)).isEmpty();

        // 4. Diamond fragment exponential amplification (4^6 = 4096 expansions, depth <= 10) is capped
        StringBuilder diamond = new StringBuilder(
            "query Diamond { searchListings { ... on SearchListingsSuccess { listings { ...D0 } } } } "
        );
        for (int i = 0; i < 6; i++) {
            diamond.append("fragment D").append(i).append(" on Listing { ")
                .append("...D").append(i + 1).append(' ')
                .append("...D").append(i + 1).append(' ')
                .append("...D").append(i + 1).append(' ')
                .append("...D").append(i + 1).append(" } ");
        }
        diamond.append("fragment D6 on Listing { id }");
        Document diamondDoc = Parser.parse(diamond.toString());
        assertThat(complexityValidator.validateComplexity(diamondDoc))
            .anyMatch(err -> err.getMessage().contains("maximum allowed complexity"));

        // 5. Bounded LRU cache (max 256 entries)
        QueryComplexityValidator cachedValidator = new QueryComplexityValidator();
        int[] parseCalls = { 0 };
        for (int i = 0; i < 300; i++) {
            String q = "query Q" + i + " { searchListings { __typename } }";
            ExecutionInput input = ExecutionInput.newExecutionInput().query(q).build();
            PreparsedDocumentEntry first = cachedValidator.getDocumentAsync(input, ei -> {
                parseCalls[0]++;
                return new PreparsedDocumentEntry(Parser.parse(ei.getQuery()));
            }).join();
            PreparsedDocumentEntry second = cachedValidator.getDocumentAsync(input, ei -> {
                parseCalls[0]++;
                return new PreparsedDocumentEntry(Parser.parse(ei.getQuery()));
            }).join();
            assertThat(first).isSameAs(second);
        }
        assertThat(parseCalls[0]).isEqualTo(300);
        assertThat(cachedValidator.cacheSize()).isEqualTo(QueryComplexityValidator.MAX_CACHE_ENTRIES);
    }
}
