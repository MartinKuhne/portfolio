package com.realestate.api.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.execution.CoercedVariables;
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
}
