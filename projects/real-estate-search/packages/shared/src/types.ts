export type SearchErrorCode = 'VALIDATION_ERROR' | 'OUT_OF_RANGE' | 'INTERNAL_ERROR';

export interface SearchError {
  code: SearchErrorCode;
  message: string;
  field: string | null;
  totalPages?: number;
}

export interface RawSearchInput {
  minPrice?: unknown;
  maxPrice?: unknown;
  minBedrooms?: unknown;
  city?: unknown;
  keyword?: unknown;
  targetBudget?: unknown;
  page?: unknown;
  pageSize?: unknown;
}

export interface ValidatedSearchInput {
  minPrice?: number;
  maxPrice?: number;
  minBedrooms?: number;
  city?: string;
  keyword?: string;
  targetBudget?: number;
  page: number;
  pageSize: number;
}

export interface Listing {
  id: string;
  address: string;
  city: string;
  price: number;
  bedrooms: number;
  description: string;
  listedDate: string;
  relevanceScore: number;
}

export interface PaginationMeta {
  page: number;
  pageSize: number;
  totalResults: number;
  totalPages: number;
}

export interface SearchSuccessPayload {
  ok: true;
  listings: Listing[];
  pagination: PaginationMeta;
  error: null;
}

export interface SearchErrorPayload {
  ok: false;
  listings: Listing[];
  pagination: PaginationMeta | null;
  error: SearchError;
}

export type SearchResponse = SearchSuccessPayload | SearchErrorPayload;

export const DEFAULT_PAGE = 1;
export const DEFAULT_PAGE_SIZE = 10;
export const MAX_PAGE_SIZE = 100;
