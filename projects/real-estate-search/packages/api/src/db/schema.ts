import { integer, pgTable, text } from 'drizzle-orm/pg-core';

export const listingsTable = pgTable('listings', {
  id: text('id').primaryKey(),
  address: text('address').notNull(),
  city: text('city').notNull(),
  price: integer('price').notNull(),
  bedrooms: integer('bedrooms').notNull(),
  description: text('description').notNull(),
  listedDate: text('listed_date').notNull(),
});

export type ListingRecord = typeof listingsTable.$inferSelect;
export type NewListingRecord = typeof listingsTable.$inferInsert;
