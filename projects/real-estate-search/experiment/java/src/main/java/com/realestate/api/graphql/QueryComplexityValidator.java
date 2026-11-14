package com.realestate.api.graphql;

import graphql.ErrorType;
import graphql.ExecutionInput;
import graphql.GraphQLError;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.execution.preparsed.PreparsedDocumentProvider;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Enforces SEC-06 query complexity bounds (maximum root field aliases and selection nesting depth).
 */
public final class QueryComplexityValidator implements PreparsedDocumentProvider {

    public static final int MAX_ROOT_FIELDS = 10;
    public static final int MAX_QUERY_DEPTH = 15;

    @Override
    public CompletableFuture<PreparsedDocumentEntry> getDocumentAsync(
        ExecutionInput executionInput,
        Function<ExecutionInput, PreparsedDocumentEntry> parseAndValidateFunction
    ) {
        PreparsedDocumentEntry entry = parseAndValidateFunction.apply(executionInput);
        if (entry.hasErrors() || entry.getDocument() == null) {
            return CompletableFuture.completedFuture(entry);
        }

        List<GraphQLError> complexityErrors = validateComplexity(entry.getDocument());
        if (!complexityErrors.isEmpty()) {
            return CompletableFuture.completedFuture(new PreparsedDocumentEntry(complexityErrors));
        }
        return CompletableFuture.completedFuture(entry);
    }

    /**
     * Validates a parsed GraphQL {@link Document} against root field count and selection depth bounds.
     */
    public List<GraphQLError> validateComplexity(Document document) {
        List<GraphQLError> errors = new ArrayList<>();
        if (document == null) {
            return errors;
        }

        Map<String, FragmentDefinition> fragmentsByName = new HashMap<>();
        for (FragmentDefinition frag : document.getDefinitionsOfType(FragmentDefinition.class)) {
            fragmentsByName.put(frag.getName(), frag);
        }

        for (OperationDefinition operation : document.getDefinitionsOfType(OperationDefinition.class)) {
            SelectionSet rootSelections = operation.getSelectionSet();
            if (rootSelections == null) {
                continue;
            }
            if (rootSelections.getSelections().size() > MAX_ROOT_FIELDS) {
                errors.add(
                    GraphQLError.newError()
                        .message("Query exceeds maximum allowed root field count (" + MAX_ROOT_FIELDS + ").")
                        .errorType(ErrorType.ValidationError)
                        .location(operation.getSourceLocation())
                        .extensions(Map.of("code", "BAD_USER_INPUT"))
                        .build()
                );
            }
            int maxDepth = measureSelectionDepth(rootSelections, 1, fragmentsByName, new HashSet<>());
            if (maxDepth > MAX_QUERY_DEPTH) {
                errors.add(
                    GraphQLError.newError()
                        .message("Query exceeds maximum allowed depth (" + MAX_QUERY_DEPTH + ").")
                        .errorType(ErrorType.ValidationError)
                        .location(operation.getSourceLocation())
                        .extensions(Map.of("code", "BAD_USER_INPUT"))
                        .build()
                );
            }
        }
        return errors;
    }

    static int measureSelectionDepth(
        SelectionSet selectionSet,
        int depth,
        Map<String, FragmentDefinition> fragmentsByName,
        Set<String> activeFragments
    ) {
        if (selectionSet == null) {
            return depth;
        }
        int deepest = depth;
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field field) {
                if (field.getSelectionSet() != null) {
                    deepest = Math.max(
                        deepest,
                        measureSelectionDepth(field.getSelectionSet(), depth + 1, fragmentsByName, activeFragments)
                    );
                }
            } else if (selection instanceof InlineFragment inline) {
                if (inline.getSelectionSet() != null) {
                    deepest = Math.max(
                        deepest,
                        measureSelectionDepth(inline.getSelectionSet(), depth + 1, fragmentsByName, activeFragments)
                    );
                }
            } else if (selection instanceof FragmentSpread spread) {
                String fragName = spread.getName();
                FragmentDefinition fragDef = fragmentsByName.get(fragName);
                if (fragDef != null && fragDef.getSelectionSet() != null && activeFragments.add(fragName)) {
                    try {
                        deepest = Math.max(
                            deepest,
                            measureSelectionDepth(fragDef.getSelectionSet(), depth + 1, fragmentsByName, activeFragments)
                        );
                    } finally {
                        activeFragments.remove(fragName);
                    }
                }
            }
        }
        return deepest;
    }
}
