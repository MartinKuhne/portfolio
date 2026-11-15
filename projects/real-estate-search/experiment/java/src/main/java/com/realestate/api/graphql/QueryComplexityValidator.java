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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Enforces SEC-06 query complexity bounds (maximum root field aliases, selection nesting depth,
 * and fragment expansion traversal budget) with a bounded LRU cache for parsed/validated documents.
 */
public final class QueryComplexityValidator implements PreparsedDocumentProvider {

    public static final int MAX_ROOT_FIELDS = 10;
    public static final int MAX_QUERY_DEPTH = 15;
    public static final int MAX_TRAVERSAL_NODES = 1000;
    public static final int MAX_CACHE_ENTRIES = 256;
    private static final int MAX_CACHED_QUERY_LENGTH = 65_536;

    private final Map<String, PreparsedDocumentEntry> cache = Collections.synchronizedMap(
        new LinkedHashMap<>(MAX_CACHE_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PreparsedDocumentEntry> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        }
    );

    private static final class TraversalBudget {
        int visitedNodes;
        boolean exceeded;

        boolean visit() {
            if (++visitedNodes > MAX_TRAVERSAL_NODES) {
                exceeded = true;
                return false;
            }
            return true;
        }
    }

    int cacheSize() {
        return cache.size();
    }

    @Override
    public CompletableFuture<PreparsedDocumentEntry> getDocumentAsync(
        ExecutionInput executionInput,
        Function<ExecutionInput, PreparsedDocumentEntry> parseAndValidateFunction
    ) {
        String query = executionInput != null ? executionInput.getQuery() : null;
        if (query != null) {
            PreparsedDocumentEntry cached = cache.get(query);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }

        PreparsedDocumentEntry entry = parseAndValidateFunction.apply(executionInput);
        PreparsedDocumentEntry result = entry;
        if (!entry.hasErrors() && entry.getDocument() != null) {
            List<GraphQLError> complexityErrors = validateComplexity(entry.getDocument());
            if (!complexityErrors.isEmpty()) {
                result = new PreparsedDocumentEntry(complexityErrors);
            }
        }

        if (query != null && query.length() <= MAX_CACHED_QUERY_LENGTH) {
            cache.put(query, result);
        }
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Validates a parsed GraphQL {@link Document} against root field count, selection depth,
     * and fragment expansion traversal bounds.
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

        TraversalBudget budget = new TraversalBudget();
        for (OperationDefinition operation : document.getDefinitionsOfType(OperationDefinition.class)) {
            SelectionSet rootSelections = operation.getSelectionSet();
            if (rootSelections == null) {
                continue;
            }
            int rootFieldCount = countRootFields(rootSelections, fragmentsByName, new HashSet<>(), budget);
            if (rootFieldCount > MAX_ROOT_FIELDS) {
                errors.add(
                    GraphQLError.newError()
                        .message("Query exceeds maximum allowed root field count (" + MAX_ROOT_FIELDS + ").")
                        .errorType(ErrorType.ValidationError)
                        .location(operation.getSourceLocation())
                        .extensions(Map.of("code", "BAD_USER_INPUT"))
                        .build()
                );
            }
            int maxDepth = measureSelectionDepth(rootSelections, 1, fragmentsByName, new HashSet<>(), budget);
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
            if (budget.exceeded) {
                errors.add(
                    GraphQLError.newError()
                        .message("Query exceeds maximum allowed complexity (" + MAX_TRAVERSAL_NODES + " nodes).")
                        .errorType(ErrorType.ValidationError)
                        .location(operation.getSourceLocation())
                        .extensions(Map.of("code", "BAD_USER_INPUT"))
                        .build()
                );
                break;
            }
        }
        return errors;
    }

    static int countRootFields(
        SelectionSet selectionSet,
        Map<String, FragmentDefinition> fragmentsByName,
        Set<String> activeFragments,
        TraversalBudget budget
    ) {
        if (selectionSet == null || budget.exceeded) {
            return 0;
        }
        int count = 0;
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (!budget.visit()) {
                return count;
            }
            if (selection instanceof Field) {
                count++;
                if (count > MAX_ROOT_FIELDS) {
                    return count;
                }
            } else if (selection instanceof InlineFragment inline) {
                count += countRootFields(inline.getSelectionSet(), fragmentsByName, activeFragments, budget);
                if (count > MAX_ROOT_FIELDS || budget.exceeded) {
                    return count;
                }
            } else if (selection instanceof FragmentSpread spread) {
                String fragName = spread.getName();
                FragmentDefinition fragDef = fragmentsByName.get(fragName);
                if (fragDef != null && fragDef.getSelectionSet() != null && activeFragments.add(fragName)) {
                    try {
                        count += countRootFields(fragDef.getSelectionSet(), fragmentsByName, activeFragments, budget);
                    } finally {
                        activeFragments.remove(fragName);
                    }
                    if (count > MAX_ROOT_FIELDS || budget.exceeded) {
                        return count;
                    }
                }
            }
        }
        return count;
    }

    static int measureSelectionDepth(
        SelectionSet selectionSet,
        int depth,
        Map<String, FragmentDefinition> fragmentsByName,
        Set<String> activeFragments,
        TraversalBudget budget
    ) {
        if (selectionSet == null || budget.exceeded) {
            return depth;
        }
        int deepest = depth;
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (!budget.visit()) {
                return deepest;
            }
            if (selection instanceof Field field) {
                if (field.getSelectionSet() != null) {
                    deepest = Math.max(
                        deepest,
                        measureSelectionDepth(field.getSelectionSet(), depth + 1, fragmentsByName, activeFragments, budget)
                    );
                }
            } else if (selection instanceof InlineFragment inline) {
                if (inline.getSelectionSet() != null) {
                    deepest = Math.max(
                        deepest,
                        measureSelectionDepth(inline.getSelectionSet(), depth + 1, fragmentsByName, activeFragments, budget)
                    );
                }
            } else if (selection instanceof FragmentSpread spread) {
                String fragName = spread.getName();
                FragmentDefinition fragDef = fragmentsByName.get(fragName);
                if (fragDef != null && fragDef.getSelectionSet() != null && activeFragments.add(fragName)) {
                    try {
                        deepest = Math.max(
                            deepest,
                            measureSelectionDepth(fragDef.getSelectionSet(), depth + 1, fragmentsByName, activeFragments, budget)
                        );
                    } finally {
                        activeFragments.remove(fragName);
                    }
                }
            }
            if (deepest > MAX_QUERY_DEPTH || budget.exceeded) {
                return deepest;
            }
        }
        return deepest;
    }
}
