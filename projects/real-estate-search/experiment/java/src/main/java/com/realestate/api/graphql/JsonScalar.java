package com.realestate.api.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pass-through {@code JSONScalar} preserving uncoerced inputs while enforcing SEC-07 nesting and key bounds.
 */
public final class JsonScalar {

    public static final int MAX_LITERAL_DEPTH = 5;
    public static final Set<String> FORBIDDEN_OBJECT_KEYS = Set.of("__proto__", "constructor", "prototype");

    public static final GraphQLScalarType INSTANCE = GraphQLScalarType.newScalar()
        .name("JSONScalar")
        .description("Pass-through scalar for arbitrary JSON primitives or structures without coercion")
        .coercing(new JsonCoercing())
        .build();

    private JsonScalar() {
    }

    /**
     * Parses a GraphQL AST literal with SEC-07 depth and forbidden-key checks.
     */
    public static Object parseLiteral(Value<?> ast) {
        return parseLiteral(ast, CoercedVariables.emptyVariables());
    }

    /**
     * Parses a GraphQL AST literal with variable resolution and SEC-07 depth/key checks.
     */
    public static Object parseLiteral(Value<?> ast, CoercedVariables variables) {
        return parseLiteralNode(ast, variables, 0);
    }

    private static Object parseLiteralNode(Value<?> ast, CoercedVariables variables, int depth) {
        if (depth > MAX_LITERAL_DEPTH) {
            throw new CoercingParseLiteralException("Input literal nesting exceeds maximum allowed depth.");
        }
        if (ast == null || ast instanceof NullValue) {
            return null;
        }
        if (ast instanceof StringValue stringVal) {
            return stringVal.getValue();
        }
        if (ast instanceof BooleanValue boolVal) {
            return boolVal.isValue();
        }
        if (ast instanceof IntValue intVal) {
            return normalizeBigInteger(intVal.getValue());
        }
        if (ast instanceof FloatValue floatVal) {
            return floatVal.getValue().doubleValue();
        }
        if (ast instanceof ArrayValue arrayVal) {
            List<Object> list = new ArrayList<>(arrayVal.getValues().size());
            for (Value<?> item : arrayVal.getValues()) {
                list.add(parseLiteralNode(item, variables, depth + 1));
            }
            return list;
        }
        if (ast instanceof ObjectValue objVal) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (ObjectField field : objVal.getObjectFields()) {
                String key = field.getName();
                if (FORBIDDEN_OBJECT_KEYS.contains(key)) {
                    throw new CoercingParseLiteralException("Invalid input object key.");
                }
                map.put(key, parseLiteralNode(field.getValue(), variables, depth + 1));
            }
            return map;
        }
        if (ast instanceof VariableReference varRef) {
            if (variables != null && variables.containsKey(varRef.getName())) {
                return validateRuntimeValue(variables.get(varRef.getName()), depth, true);
            }
            return null;
        }
        throw new CoercingParseLiteralException("Unsupported JSONScalar literal type: " + ast.getClass().getSimpleName());
    }

    static Object validateRuntimeValue(Object input, int depth) {
        return validateRuntimeValue(input, depth, false);
    }

    private static Object validateRuntimeValue(Object input, int depth, boolean fromLiteral) {
        if (depth > MAX_LITERAL_DEPTH) {
            throw inputException(fromLiteral, "Input literal nesting exceeds maximum allowed depth.");
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (FORBIDDEN_OBJECT_KEYS.contains(key)) {
                    throw inputException(fromLiteral, "Invalid input object key.");
                }
                sanitized.put(key, validateRuntimeValue(entry.getValue(), depth + 1, fromLiteral));
            }
            return sanitized;
        }
        if (input instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            for (Object item : list) {
                sanitized.add(validateRuntimeValue(item, depth + 1, fromLiteral));
            }
            return sanitized;
        }
        return input;
    }

    private static RuntimeException inputException(boolean fromLiteral, String message) {
        return fromLiteral
            ? new CoercingParseLiteralException(message)
            : new CoercingParseValueException(message);
    }

    private static Number normalizeBigInteger(BigInteger value) {
        if (value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0
            && value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
            return value.intValue();
        }
        if (value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
            && value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
            return value.longValue();
        }
        return value;
    }

    private static final class JsonCoercing implements Coercing<Object, Object> {

        @Override
        public Object serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale)
            throws CoercingSerializeException {
            return dataFetcherResult;
        }

        @Override
        public Object parseValue(Object input, GraphQLContext graphQLContext, Locale locale)
            throws CoercingParseValueException {
            return validateRuntimeValue(input, 0);
        }

        @Override
        public Object parseLiteral(
            Value<?> input,
            CoercedVariables variables,
            GraphQLContext graphQLContext,
            Locale locale
        ) throws CoercingParseLiteralException {
            return JsonScalar.parseLiteral(input, variables);
        }

        @Override
        public Value<?> valueToLiteral(Object input, GraphQLContext graphQLContext, Locale locale) {
            return toLiteral(input, 0);
        }

        private Value<?> toLiteral(Object input, int depth) {
            if (depth > MAX_LITERAL_DEPTH) {
                throw new CoercingParseValueException("Input literal nesting exceeds maximum allowed depth.");
            }
            if (input == null) {
                return NullValue.of();
            }
            if (input instanceof String str) {
                return StringValue.of(str);
            }
            if (input instanceof Boolean bool) {
                return BooleanValue.of(bool);
            }
            if (input instanceof Integer || input instanceof Long || input instanceof BigInteger) {
                return IntValue.newIntValue(new BigInteger(input.toString())).build();
            }
            if (input instanceof Number num) {
                return FloatValue.newFloatValue(BigDecimal.valueOf(num.doubleValue())).build();
            }
            if (input instanceof List<?> list) {
                List<Value> values = new ArrayList<>(list.size());
                for (Object item : list) {
                    values.add(toLiteral(item, depth + 1));
                }
                return ArrayValue.newArrayValue().values(values).build();
            }
            if (input instanceof Map<?, ?> map) {
                List<ObjectField> fields = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (FORBIDDEN_OBJECT_KEYS.contains(key)) {
                        throw new CoercingParseValueException("Invalid input object key.");
                    }
                    fields.add(
                        ObjectField.newObjectField()
                            .name(key)
                            .value(toLiteral(entry.getValue(), depth + 1))
                            .build()
                    );
                }
                return ObjectValue.newObjectValue().objectFields(fields).build();
            }
            return StringValue.of(input.toString());
        }
    }
}
