package repository

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"real-estate-search/internal/domain"
	"real-estate-search/internal/telemetry"
)

// PostgresRepository is a production PostgreSQL implementation of Repository using pgxpool.
type PostgresRepository struct {
	pool *pgxpool.Pool
}

// NewPostgresRepository initializes a Postgres repository with a connection pool.
func NewPostgresRepository(ctx context.Context, connectionString string) (*PostgresRepository, error) {
	config, err := pgxpool.ParseConfig(connectionString)
	if err != nil {
		return nil, fmt.Errorf("failed to parse database url: %w", err)
	}

	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, fmt.Errorf("failed to create pgx connection pool: %w", err)
	}

	repo := &PostgresRepository{pool: pool}
	if err := repo.EnsureSchema(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("failed to ensure schema: %w", err)
	}

	return repo, nil
}

// EnsureSchemaDDL defines the ADR-009 idempotent schema DDL matching client.ts.
const EnsureSchemaDDL = `
    CREATE TABLE IF NOT EXISTS listings (
      internal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id TEXT NOT NULL,
      source TEXT NOT NULL DEFAULT 'SEED',
      address TEXT NOT NULL,
      city TEXT NOT NULL,
      state TEXT,
      zip TEXT,
      price INTEGER NOT NULL,
      bedrooms INTEGER NOT NULL,
      bathrooms DOUBLE PRECISION,
      sqft INTEGER,
      latitude DOUBLE PRECISION,
      longitude DOUBLE PRECISION,
      location POINT,
      listed_date TIMESTAMPTZ NOT NULL,
      status TEXT NOT NULL DEFAULT 'active',
      description TEXT NOT NULL
    );

    CREATE UNIQUE INDEX IF NOT EXISTS listings_id_source_unique ON listings (id, source);
    CREATE INDEX IF NOT EXISTS listings_location_gist ON listings USING gist (location);
    CREATE INDEX IF NOT EXISTS listings_listed_date_desc ON listings (listed_date DESC);
    CREATE INDEX IF NOT EXISTS listings_price_idx ON listings (price);
    CREATE INDEX IF NOT EXISTS listings_bedrooms_idx ON listings (bedrooms);
    CREATE INDEX IF NOT EXISTS listings_city_lower_idx ON listings (LOWER(city));

    DO $$
    BEGIN
      IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_trgm') THEN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
        CREATE INDEX IF NOT EXISTS listings_description_trgm
          ON listings USING gin (description gin_trgm_ops);
      END IF;
    END $$;
	`

// EnsureSchema creates tables and indexes matching ADR-009 and client.ts.
func (p *PostgresRepository) EnsureSchema(ctx context.Context) error {
	_, err := p.pool.Exec(ctx, EnsureSchemaDDL)
	return err
}

func (p *PostgresRepository) CountMatchingListings(ctx context.Context, input domain.ValidatedSearchInput) (int, error) {
	tracer := telemetry.GetTracer()
	ctx, span := tracer.Start(ctx, "search.db.count",
		trace.WithSpanKind(trace.SpanKindClient),
		trace.WithAttributes(
			attribute.String("db.system", "postgresql"),
			attribute.String("db.operation.name", "SELECT"),
			attribute.String("db.collection.name", "listings"),
		),
	)
	defer span.End()

	query, args := BuildCountQuery(input)
	var count int
	err := p.pool.QueryRow(ctx, query, args...).Scan(&count)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		return 0, err
	}

	span.SetAttributes(attribute.Int("search.totalResults", count))
	return count, nil
}

func (p *PostgresRepository) FetchListingPage(ctx context.Context, input domain.ValidatedSearchInput, offset int) ([]domain.Listing, error) {
	tracer := telemetry.GetTracer()
	ctx, span := tracer.Start(ctx, "search.db.query",
		trace.WithSpanKind(trace.SpanKindClient),
		trace.WithAttributes(
			attribute.String("db.system", "postgresql"),
			attribute.String("db.operation.name", "SELECT"),
			attribute.String("db.collection.name", "listings"),
			attribute.Int("search.page", input.Page),
			attribute.Int("search.pageSize", input.PageSize),
		),
	)
	defer span.End()

	query, args := BuildSelectQuery(input, offset)
	rows, err := p.pool.Query(ctx, query, args...)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		return nil, err
	}
	defer rows.Close()

	listings := make([]domain.Listing, 0)
	for rows.Next() {
		var (
			internalID     *uuid.UUID
			id             string
			source         *string
			address        string
			city           string
			state          *string
			zip            *string
			price          int
			bedrooms       int
			bathrooms      *float64
			sqft           *int
			latitude       *float64
			longitude      *float64
			status         *string
			description    string
			listedDateRaw  any
			relevanceScore float64
		)

		err := rows.Scan(
			&internalID,
			&id,
			&source,
			&address,
			&city,
			&state,
			&zip,
			&price,
			&bedrooms,
			&bathrooms,
			&sqft,
			&latitude,
			&longitude,
			&status,
			&description,
			&listedDateRaw,
			&relevanceScore,
		)
		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, err.Error())
			return nil, err
		}

		var internalIDStr *string
		if internalID != nil {
			s := internalID.String()
			internalIDStr = &s
		}

		var listedDateStr string
		switch v := listedDateRaw.(type) {
		case time.Time:
			listedDateStr = v.UTC().Format("2006-01-02T15:04:05.000Z")
		case string:
			if t, err := time.Parse(time.RFC3339Nano, v); err == nil {
				listedDateStr = t.UTC().Format("2006-01-02T15:04:05.000Z")
			} else if t, err := time.Parse(time.RFC3339, v); err == nil {
				listedDateStr = t.UTC().Format("2006-01-02T15:04:05.000Z")
			} else {
				listedDateStr = v
			}
		default:
			listedDateStr = fmt.Sprint(v)
		}

		listings = append(listings, domain.Listing{
			InternalID:     internalIDStr,
			ID:             id,
			Source:         source,
			Address:        address,
			City:           city,
			State:          state,
			Zip:            zip,
			Price:          price,
			Bedrooms:       bedrooms,
			Bathrooms:      bathrooms,
			Sqft:           sqft,
			Latitude:       latitude,
			Longitude:      longitude,
			Status:         status,
			Description:    description,
			ListedDate:     listedDateStr,
			RelevanceScore: relevanceScore,
		})
	}

	if err := rows.Err(); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		return nil, err
	}

	span.SetAttributes(attribute.Int("search.returnedCount", len(listings)))
	return listings, nil
}

// InsertListingsIdempotentlySQL is the idempotent INSERT statement matching client.ts.
const InsertListingsIdempotentlySQL = `
    INSERT INTO listings (
      internal_id, id, source, address, city, state, zip, price, bedrooms,
      bathrooms, sqft, latitude, longitude, location, listed_date, status, description
    ) VALUES (
      COALESCE($1, gen_random_uuid()), $2, COALESCE($3, 'SEED'), $4, $5, $6, $7, $8, $9,
      $10, $11, $12, $13,
      CASE WHEN $12::double precision IS NOT NULL AND $13::double precision IS NOT NULL
           THEN point($13, $12)
           ELSE NULL END,
      $14, COALESCE($15, 'active'), $16
    )
    ON CONFLICT (id, source) DO NOTHING
`

// BuildInsertListingArgs builds the ordered SQL parameter slice for InsertListingsIdempotentlySQL.
func BuildInsertListingArgs(rec domain.Listing) []any {
	var internalUUID *uuid.UUID
	if rec.InternalID != nil && *rec.InternalID != "" {
		if u, err := uuid.Parse(*rec.InternalID); err == nil {
			internalUUID = &u
		}
	}

	source := "SEED"
	if rec.Source != nil && *rec.Source != "" {
		source = *rec.Source
	}

	status := "active"
	if rec.Status != nil && *rec.Status != "" {
		status = *rec.Status
	}

	return []any{
		internalUUID,
		strings.TrimSpace(rec.ID),
		source,
		strings.TrimSpace(rec.Address),
		strings.TrimSpace(rec.City),
		rec.State,
		rec.Zip,
		rec.Price,
		rec.Bedrooms,
		rec.Bathrooms,
		rec.Sqft,
		rec.Latitude,
		rec.Longitude,
		rec.ListedDate,
		status,
		rec.Description,
	}
}

// InsertListingsIdempotently inserts listing records by (id, source), skipping rows that already exist.
func (p *PostgresRepository) InsertListingsIdempotently(ctx context.Context, records []domain.Listing) (int, error) {
	if len(records) == 0 {
		return 0, nil
	}

	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)

	batch := &pgx.Batch{}
	for _, rec := range records {
		batch.Queue(InsertListingsIdempotentlySQL, BuildInsertListingArgs(rec)...)
	}

	br := tx.SendBatch(ctx, batch)
	inserted := 0
	for i := 0; i < len(records); i++ {
		tag, err := br.Exec()
		if err != nil {
			_ = br.Close()
			return 0, err
		}
		inserted += int(tag.RowsAffected())
	}
	if err := br.Close(); err != nil {
		return 0, err
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}

	return inserted, nil
}

func (p *PostgresRepository) Close() error {
	p.pool.Close()
	return nil
}
