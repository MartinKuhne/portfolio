package com.realestate.api.db;

import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.scoring.DefaultRelevanceScorer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parameterized PostgreSQL SQL query builder matching {@code packages/api/src/search/searchQuery.ts}
 * and {@code executeSearch.ts} (ADR-004).
 */
public final class SearchQueryBuilder {

    /**
     * Parameterized SQL fragment paired with ordered JDBC bind parameters.
     */
    public record SqlQuery(String sql, List<Object> parameters) {
        public SqlQuery {
            Objects.requireNonNull(sql, "sql must not be null");
            parameters = List.copyOf(parameters);
        }
    }

    private SearchQueryBuilder() {
    }

    /**
     * Escapes LIKE/ILIKE pattern metacharacters ({@code \}, {@code %}, {@code _}) for {@code ESCAPE '\'}.
     */
    public static String escapeLike(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Builds a parameterized {@code WHERE} clause (including the {@code WHERE} keyword when non-empty).
     */
    public static SqlQuery buildWhereClause(SearchCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (criteria.minPrice() != null) {
            conditions.add("price >= ?");
            params.add(BigDecimal.valueOf(criteria.minPrice()));
        }
        if (criteria.maxPrice() != null) {
            conditions.add("price <= ?");
            params.add(BigDecimal.valueOf(criteria.maxPrice()));
        }
        if (criteria.minBedrooms() != null) {
            conditions.add("bedrooms >= ?");
            params.add(criteria.minBedrooms());
        }
        if (criteria.city() != null && !criteria.city().isBlank()) {
            conditions.add("LOWER(city) = LOWER(?)");
            params.add(criteria.city().trim());
        }
        if (criteria.keyword() != null && !criteria.keyword().isBlank()) {
            conditions.add("description ILIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(criteria.keyword().trim()) + "%");
        }

        if (conditions.isEmpty()) {
            return new SqlQuery("", List.of());
        }
        return new SqlQuery("WHERE " + String.join(" AND ", conditions), params);
    }

    /**
     * Builds the parameterized SQL relevance score expression normalized to {@code [0.00, 1.00]}
     * and rounded to 2 decimal places, matching {@link DefaultRelevanceScorer}.
     */
    public static SqlQuery buildRelevanceScoreExpr(Double targetBudget) {
        List<Object> params = new ArrayList<>();
        String recencySql =
            "GREATEST(0.0, LEAST(1.0, 1.0 - GREATEST(0.0, EXTRACT(EPOCH FROM (?::timestamptz - listed_date)) / 86400.0) / ?::double precision))";

        String rawSql;
        if (targetBudget != null) {
            rawSql = "(?::double precision * GREATEST(0.0, LEAST(1.0, 1.0 - ABS(price::double precision - ?::double precision) / ?::double precision)) + ?::double precision * " + recencySql + ")";
            params.add(DefaultRelevanceScorer.BUDGET_WEIGHT);
            params.add(targetBudget);
            params.add(targetBudget);
            params.add(DefaultRelevanceScorer.RECENCY_WEIGHT);
        } else {
            rawSql = recencySql;
        }

        params.add(DefaultRelevanceScorer.REFERENCE_DATE_ISO);
        params.add((double) DefaultRelevanceScorer.MAX_RECENCY_DAYS);

        String expr = "ROUND(GREATEST(0.0, LEAST(1.0, " + rawSql + "))::numeric, 2)::double precision";
        return new SqlQuery(expr, params);
    }

    /**
     * Builds the parameterized {@code COUNT(*)::int} query for matching listings.
     */
    public static SqlQuery buildCountQuery(SearchCriteria criteria) {
        SqlQuery where = buildWhereClause(criteria);
        String sql = where.sql().isEmpty()
            ? "SELECT COUNT(*)::int FROM listings"
            : "SELECT COUNT(*)::int FROM listings " + where.sql();
        return new SqlQuery(sql, where.parameters());
    }

    /**
     * Builds the parameterized ranked page query with deterministic locale-independent tie-breaking.
     * When {@code targetBudget} is omitted, omits the score expression from {@code ORDER BY} so
     * PostgreSQL can utilize {@code listings_listed_date_desc} (ADR-004).
     */
    public static SqlQuery buildPageQuery(SearchCriteria criteria, int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        SqlQuery scoreExpr = buildRelevanceScoreExpr(criteria.targetBudget());
        SqlQuery where = buildWhereClause(criteria);

        List<Object> params = new ArrayList<>();
        params.addAll(scoreExpr.parameters());
        params.addAll(where.parameters());
        params.add(criteria.pageSize());
        params.add(offset);

        String orderBy = criteria.targetBudget() != null
            ? "ORDER BY relevance_score DESC, listed_date DESC, id COLLATE \"C\" ASC, source COLLATE \"C\" ASC, internal_id ASC"
            : "ORDER BY listed_date DESC, id COLLATE \"C\" ASC, source COLLATE \"C\" ASC, internal_id ASC";

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT internal_id, id, source, address, city, state, zip, ")
           .append("price, bedrooms, bathrooms, sqft, latitude, longitude, ")
           .append("status, description, listed_date, ")
           .append(scoreExpr.sql()).append(" AS relevance_score ")
           .append("FROM listings ");
        if (!where.sql().isEmpty()) {
            sql.append(where.sql()).append(' ');
        }
        sql.append(orderBy).append(" LIMIT ? OFFSET ?");

        return new SqlQuery(sql.toString(), params);
    }
}
