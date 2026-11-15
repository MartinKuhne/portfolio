package repository

import (
	"fmt"
	"strings"

	"real-estate-search/internal/domain"
)

// EscapeLike escapes SQL LIKE/ILIKE wildcards (\, %, _) with a preceding backslash.
func EscapeLike(text string) string {
	var builder strings.Builder
	for _, r := range text {
		switch r {
		case '\\', '%', '_':
			builder.WriteRune('\\')
			builder.WriteRune(r)
		default:
			builder.WriteRune(r)
		}
	}
	return builder.String()
}

// BuildWhereClause constructs parameterized WHERE conditions and arguments.
func BuildWhereClause(input domain.ValidatedSearchInput, argStart int) (string, []any, int) {
	var conditions []string
	var args []any
	argIndex := argStart

	if input.MinPrice != nil {
		conditions = append(conditions, fmt.Sprintf("price >= $%d::numeric", argIndex))
		args = append(args, *input.MinPrice)
		argIndex++
	}

	if input.MaxPrice != nil {
		conditions = append(conditions, fmt.Sprintf("price <= $%d::numeric", argIndex))
		args = append(args, *input.MaxPrice)
		argIndex++
	}

	if input.MinBedrooms != nil {
		conditions = append(conditions, fmt.Sprintf("bedrooms >= $%d", argIndex))
		args = append(args, *input.MinBedrooms)
		argIndex++
	}

	if input.City != nil && *input.City != "" {
		conditions = append(conditions, fmt.Sprintf("LOWER(city) = LOWER($%d)", argIndex))
		args = append(args, *input.City)
		argIndex++
	}

	if input.Keyword != nil && *input.Keyword != "" {
		conditions = append(conditions, fmt.Sprintf("description ILIKE $%d ESCAPE '\\'", argIndex))
		args = append(args, "%"+EscapeLike(*input.Keyword)+"%")
		argIndex++
	}

	if len(conditions) == 0 {
		return "", args, argIndex
	}

	return "WHERE " + strings.Join(conditions, " AND "), args, argIndex
}

// BuildRelevanceScoreExpr generates the SQL relevance scoring expression.
func BuildRelevanceScoreExpr(targetBudget *float64, argIndex int) (string, []any, int) {
	recencyExpr := fmt.Sprintf(
		"GREATEST(0.0, LEAST(1.0, 1.0 - GREATEST(0.0, EXTRACT(EPOCH FROM ('%s'::timestamptz - listed_date)) / 86400.0) / %.1f::double precision))",
		domain.ReferenceDateISO,
		domain.MaxRecencyDays,
	)

	var args []any
	nextArgIndex := argIndex

	var rawExpr string
	if targetBudget != nil {
		rawExpr = fmt.Sprintf(
			"(%.2f::double precision * (1.0 / (1.0 + ABS(price - $%d)::double precision / $%d::double precision)) + %.2f::double precision * %s)",
			domain.BudgetWeight,
			argIndex,
			argIndex,
			domain.RecencyWeight,
			recencyExpr,
		)
		args = append(args, *targetBudget)
		nextArgIndex++
	} else {
		rawExpr = recencyExpr
	}

	fullExpr := fmt.Sprintf("ROUND(GREATEST(0.0, LEAST(1.0, %s))::numeric, 2)::double precision", rawExpr)
	return fullExpr, args, nextArgIndex
}

// BuildCountQuery builds the SELECT COUNT(*)::int query.
func BuildCountQuery(input domain.ValidatedSearchInput) (string, []any) {
	whereClause, args, _ := BuildWhereClause(input, 1)
	query := "SELECT COUNT(*)::int FROM listings"
	if whereClause != "" {
		query += " " + whereClause
	}
	return query, args
}

// BuildSelectQuery builds the ranked SELECT query with pagination and deterministic tie-breaking.
func BuildSelectQuery(input domain.ValidatedSearchInput, offset int) (string, []any) {
	scoreExpr, scoreArgs, nextArg := BuildRelevanceScoreExpr(input.TargetBudget, 1)
	whereClause, whereArgs, nextArgAfterWhere := BuildWhereClause(input, nextArg)

	args := append(scoreArgs, whereArgs...)
	limitArgIdx := nextArgAfterWhere
	offsetArgIdx := nextArgAfterWhere + 1
	args = append(args, input.PageSize, offset)

	query := fmt.Sprintf(
		`SELECT internal_id, id, source, address, city, state, zip, price, bedrooms, bathrooms, sqft, latitude, longitude, status, description, listed_date, %s AS score FROM listings`,
		scoreExpr,
	)

	if whereClause != "" {
		query += " " + whereClause
	}

	var orderClause string
	if input.TargetBudget != nil {
		orderClause = `ORDER BY score DESC, listed_date DESC, id COLLATE "C" ASC, source COLLATE "C" ASC, internal_id ASC`
	} else {
		orderClause = `ORDER BY listed_date DESC, id COLLATE "C" ASC, source COLLATE "C" ASC, internal_id ASC`
	}

	query += fmt.Sprintf(
		` %s LIMIT $%d OFFSET $%d`,
		orderClause,
		limitArgIdx,
		offsetArgIdx,
	)

	return query, args
}
