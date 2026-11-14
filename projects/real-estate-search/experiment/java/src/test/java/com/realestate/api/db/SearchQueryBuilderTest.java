package com.realestate.api.db;

import com.realestate.api.domain.SearchCriteria;
import com.realestate.api.scoring.DefaultRelevanceScorer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryBuilderTest {

    @Test
    void escapeLikeEscapesBackslashPercentAndUnderscore() {
        assertThat(SearchQueryBuilder.escapeLike(null)).isEmpty();
        assertThat(SearchQueryBuilder.escapeLike("")).isEmpty();
        assertThat(SearchQueryBuilder.escapeLike("pool")).isEqualTo("pool");
        assertThat(SearchQueryBuilder.escapeLike("100%_a\\b")).isEqualTo("100\\%\\_a\\\\b");
    }

    @Test
    void buildWhereClauseReturnsEmptyWhenNoFiltersActive() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, "   ", "\t", null, 1, 10);
        SearchQueryBuilder.SqlQuery where = SearchQueryBuilder.buildWhereClause(criteria);

        assertThat(where.sql()).isEmpty();
        assertThat(where.parameters()).isEmpty();
    }

    @Test
    void buildWhereClauseBindsAllFiltersAndEscapesKeywordWildcards() {
        SearchCriteria criteria = new SearchCriteria(
            250000.5,
            600000.75,
            3,
            "  austin  ",
            "  100%_pool\\view ",
            500000.0,
            1,
            10
        );

        SearchQueryBuilder.SqlQuery where = SearchQueryBuilder.buildWhereClause(criteria);

        assertThat(where.sql()).isEqualTo(
            "WHERE price >= ? AND price <= ? AND bedrooms >= ? AND LOWER(city) = LOWER(?) AND description ILIKE ? ESCAPE '\\'"
        );
        assertThat(where.parameters()).containsExactly(
            BigDecimal.valueOf(250000.5),
            BigDecimal.valueOf(600000.75),
            3,
            "austin",
            "%100\\%\\_pool\\\\view%"
        );
    }

    @Test
    void buildRelevanceScoreExprWithTargetBudgetIncludesWeightedTermsAndConstants() {
        SearchQueryBuilder.SqlQuery expr = SearchQueryBuilder.buildRelevanceScoreExpr(500000.0);

        assertThat(expr.sql()).contains("ROUND(GREATEST(0.0, LEAST(1.0,");
        assertThat(expr.sql()).contains("ABS(price::double precision - ?::double precision)");
        assertThat(expr.sql()).contains("EXTRACT(EPOCH FROM (?::timestamptz - listed_date))");
        assertThat(expr.parameters()).containsExactly(
            DefaultRelevanceScorer.BUDGET_WEIGHT,
            500000.0,
            500000.0,
            DefaultRelevanceScorer.RECENCY_WEIGHT,
            DefaultRelevanceScorer.REFERENCE_DATE_ISO,
            (double) DefaultRelevanceScorer.MAX_RECENCY_DAYS
        );
    }

    @Test
    void buildRelevanceScoreExprWithoutTargetBudgetUsesRecencyOnly() {
        SearchQueryBuilder.SqlQuery expr = SearchQueryBuilder.buildRelevanceScoreExpr(null);

        assertThat(expr.sql()).doesNotContain("ABS(");
        assertThat(expr.parameters()).containsExactly(
            DefaultRelevanceScorer.REFERENCE_DATE_ISO,
            (double) DefaultRelevanceScorer.MAX_RECENCY_DAYS
        );
    }

    @Test
    void buildCountQueryHandlesUnfilteredAndFilteredCriteria() {
        SearchQueryBuilder.SqlQuery unfiltered = SearchQueryBuilder.buildCountQuery(
            new SearchCriteria(null, null, null, null, null, null, 1, 10)
        );
        assertThat(unfiltered.sql()).isEqualTo("SELECT COUNT(*)::int FROM listings");
        assertThat(unfiltered.parameters()).isEmpty();

        SearchQueryBuilder.SqlQuery filtered = SearchQueryBuilder.buildCountQuery(
            new SearchCriteria(400000.0, null, 2, "Denver", null, null, 1, 10)
        );
        assertThat(filtered.sql()).isEqualTo(
            "SELECT COUNT(*)::int FROM listings WHERE price >= ? AND bedrooms >= ? AND LOWER(city) = LOWER(?)"
        );
        assertThat(filtered.parameters()).containsExactly(BigDecimal.valueOf(400000.0), 2, "Denver");
    }

    @Test
    void buildPageQueryOrdersByScoreWhenTargetBudgetPresentAndOmitsScoreOrderWhenOmitted() {
        SearchCriteria withBudget = new SearchCriteria(null, null, null, "Denver", null, 500000.0, 2, 5);
        SearchQueryBuilder.SqlQuery budgetQuery = SearchQueryBuilder.buildPageQuery(withBudget, 5);

        assertThat(budgetQuery.sql()).contains(
            "ORDER BY relevance_score DESC, listed_date DESC, id COLLATE \"C\" ASC, source COLLATE \"C\" ASC, internal_id ASC LIMIT ? OFFSET ?"
        );
        assertThat(budgetQuery.parameters()).containsExactly(
            DefaultRelevanceScorer.BUDGET_WEIGHT,
            500000.0,
            500000.0,
            DefaultRelevanceScorer.RECENCY_WEIGHT,
            DefaultRelevanceScorer.REFERENCE_DATE_ISO,
            (double) DefaultRelevanceScorer.MAX_RECENCY_DAYS,
            "Denver",
            5,
            5
        );

        SearchCriteria withoutBudget = new SearchCriteria(null, null, null, null, null, null, 1, 10);
        SearchQueryBuilder.SqlQuery recencyQuery = SearchQueryBuilder.buildPageQuery(withoutBudget, 0);

        assertThat(recencyQuery.sql()).contains(
            "ORDER BY listed_date DESC, id COLLATE \"C\" ASC, source COLLATE \"C\" ASC, internal_id ASC LIMIT ? OFFSET ?"
        );
        assertThat(recencyQuery.sql()).doesNotContain("ORDER BY relevance_score DESC");
        assertThat(recencyQuery.parameters()).containsExactly(
            DefaultRelevanceScorer.REFERENCE_DATE_ISO,
            (double) DefaultRelevanceScorer.MAX_RECENCY_DAYS,
            10,
            0
        );
    }

    @Test
    void buildPageQueryRejectsNegativeOffset() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null, null, null, 1, 10);
        assertThatThrownBy(() -> SearchQueryBuilder.buildPageQuery(criteria, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("offset");
    }
}
