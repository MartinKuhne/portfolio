import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Container, Typography } from '@mui/material';
import {
  type Listing,
  type PaginationMeta,
  type RawSearchInput,
  type SearchResponse,
  validateSearchInput,
} from '@real-estate/shared';
import { fetchSearchListings } from './api/searchClient.js';
import { ListingResults } from './components/ListingResults.js';
import { PaginationBar } from './components/PaginationBar.js';
import { SearchForm, type SearchFormState } from './components/SearchForm.js';

const DEFAULT_FORM_STATE: SearchFormState = {
  minPrice: '',
  maxPrice: '',
  minBedrooms: '',
  city: '',
  keyword: '',
  targetBudget: '',
  pageSize: '10',
};

function toRawSearchInput(form: SearchFormState, page: number): RawSearchInput {
  const raw: RawSearchInput = {
    page,
    pageSize: form.pageSize,
  };
  if (form.minPrice !== '') raw.minPrice = form.minPrice;
  if (form.maxPrice !== '') raw.maxPrice = form.maxPrice;
  if (form.minBedrooms !== '') raw.minBedrooms = form.minBedrooms;
  if (form.city !== '') raw.city = form.city;
  if (form.keyword !== '') raw.keyword = form.keyword;
  if (form.targetBudget !== '') raw.targetBudget = form.targetBudget;
  return raw;
}

export interface AppProps {
  searchFn?: (
    rawInput: RawSearchInput,
    signal?: AbortSignal
  ) => Promise<SearchResponse>;
}

export function App({
  searchFn = fetchSearchListings,
}: AppProps): React.JSX.Element {
  const [formState, setFormState] = useState<SearchFormState>(DEFAULT_FORM_STATE);
  const [listings, setListings] = useState<Listing[]>([]);
  const [pagination, setPagination] = useState<PaginationMeta | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasSearched, setHasSearched] = useState(false);

  const requestIdRef = useRef(0);
  const abortControllerRef = useRef<AbortController | null>(null);
  const activeFormRef = useRef<SearchFormState>(DEFAULT_FORM_STATE);

  const runSearch = useCallback(
    async (form: SearchFormState, page: number) => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      const controller = new AbortController();
      abortControllerRef.current = controller;

      const requestId = ++requestIdRef.current;
      setLoading(true);

      const rawInput = toRawSearchInput(form, page);
      const validation = validateSearchInput(rawInput);
      if (!validation.ok) {
        if (requestId === requestIdRef.current) {
          setListings([]);
          setPagination(null);
          setError(validation.error.message);
          setHasSearched(true);
          setLoading(false);
        }
        return;
      }

      try {
        const response = await searchFn(rawInput, controller.signal);
        if (requestId !== requestIdRef.current) {
          return;
        }
        setHasSearched(true);
        if (response.ok) {
          setListings(response.listings);
          setPagination(response.pagination);
          setError(null);
        } else {
          setListings([]);
          setPagination(null);
          setError(response.error.message);
        }
      } catch (err: unknown) {
        if ((err as { name?: string })?.name === 'AbortError') {
          return;
        }
        if (requestId !== requestIdRef.current) {
          return;
        }
        setHasSearched(true);
        setListings([]);
        setPagination(null);
        setError(err instanceof Error ? err.message : 'An unexpected error occurred.');
      } finally {
        if (requestId === requestIdRef.current) {
          setLoading(false);
        }
      }
    },
    [searchFn]
  );

  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, []);

  const handleFieldChange = (field: keyof SearchFormState, value: string) => {
    setFormState((prev) => ({ ...prev, [field]: value }));
  };

  const handleSearch = () => {
    activeFormRef.current = formState;
    runSearch(formState, 1);
  };

  const handleReset = () => {
    setFormState(DEFAULT_FORM_STATE);
    activeFormRef.current = DEFAULT_FORM_STATE;
    runSearch(DEFAULT_FORM_STATE, 1);
  };

  const handlePreviousPage = () => {
    if (pagination && pagination.page > 1) {
      runSearch(activeFormRef.current, pagination.page - 1);
    }
  };

  const handleNextPage = () => {
    if (pagination && pagination.page < pagination.totalPages) {
      runSearch(activeFormRef.current, pagination.page + 1);
    }
  };

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Typography variant="h4" component="h1" gutterBottom fontWeight="bold">
        Real Estate Search
      </Typography>

      {/* Search remains enabled during loading so users can submit a new search and cancel in-flight stale requests */}
      <SearchForm
        values={formState}
        onChange={handleFieldChange}
        onSubmit={handleSearch}
        onReset={handleReset}
      />

      {error && (
        <Alert severity="error" role="alert" sx={{ mb: 3 }}>
          {error}
        </Alert>
      )}

      <ListingResults
        listings={listings}
        loading={loading}
        hasSearched={hasSearched}
        hasError={Boolean(error)}
      />

      {pagination && (
        <PaginationBar
          pagination={pagination}
          onPreviousPage={handlePreviousPage}
          onNextPage={handleNextPage}
          loading={loading}
        />
      )}
    </Container>
  );
}
