import { sql } from 'drizzle-orm';
import {
  doublePrecision,
  index,
  integer,
  pgTable,
  point,
  text,
  timestamp,
  uniqueIndex,
  uuid,
} from 'drizzle-orm/pg-core';

export const listingsTable = pgTable(
  'listings',
  {
    internalId: uuid('internal_id').defaultRandom().primaryKey(),
    id: text('id').notNull(),
    source: text('source').notNull().default('SEED'),
    address: text('address').notNull(),
    city: text('city').notNull(),
    state: text('state'),
    zip: text('zip'),
    price: integer('price').notNull(),
    bedrooms: integer('bedrooms').notNull(),
    bathrooms: doublePrecision('bathrooms'),
    sqft: integer('sqft'),
    latitude: doublePrecision('latitude'),
    longitude: doublePrecision('longitude'),
    // GeoJSON/OGC axis order: x = longitude, y = latitude
    location: point('location', { mode: 'xy' }),
    listedDate: timestamp('listed_date', { withTimezone: true, mode: 'string' }).notNull(),
    status: text('status').notNull().default('active'),
    description: text('description').notNull(),
  },
  (table) => [
    uniqueIndex('listings_id_source_unique').on(table.id, table.source),
    index('listings_location_gist').using('gist', table.location),
    index('listings_listed_date_desc').on(table.listedDate.desc()),
    index('listings_price_idx').on(table.price),
    index('listings_bedrooms_idx').on(table.bedrooms),
    index('listings_city_lower_idx').on(sql`LOWER(${table.city})`),
    index('listings_description_trgm').using('gin', table.description.op('gin_trgm_ops')),
  ]
);

export type ListingRecord = typeof listingsTable.$inferSelect;
export type NewListingRecord = typeof listingsTable.$inferInsert;
