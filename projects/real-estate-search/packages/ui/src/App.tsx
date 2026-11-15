import React, { useCallback, useEffect, useRef, useState } from 'react';
import { context, SpanStatusCode, trace } from '@opentelemetry/api';
import { Alert, Box, Container, MenuItem, TextField, Typography } from '@mui/material';
import {
  type Listing,
  type PaginationMeta,
  type RawSearchInput,
  type SearchResponse,
  validateSearchInput,
} from '@real-estate/shared';
import {
  API_BACKENDS,
  type ApiBackendId,
  fetchSearchListings,
  getApiBackendOption,
  type SearchRequestOptions,
} from './api/searchClient.js';
import { ListingResults } from './components/ListingResults.js';
import { PaginationBar } from './components/PaginationBar.js';
import { SearchForm, type SearchFormState } from './components/SearchForm.js';
import { getTracer, logUi } from './telemetry.js';

type SearchTriggerAction = 'search' | 'reset' | 'previous_page' | 'next_page';

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
    signal?: AbortSignal,
    options?: SearchRequestOptions
  ) => Promise<SearchResponse>;
}

export function App({
  searchFn = fetchSearchListings,
}: AppProps): React.JSX.Element {
  const [apiBackend, setApiBackend] = useState<ApiBackendId>('node');
  const [formState, setFormState] = useState<SearchFormState>(DEFAULT_FORM_STATE);
  const [listings, setListings] = useState<Listing[]>([]);
  const [pagination, setPagination] = useState<PaginationMeta | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasSearched, setHasSearched] = useState(false);

  const requestIdRef = useRef(0);
  const abortControllerRef = useRef<AbortController | null>(null);
  const activeFormRef = useRef<SearchFormState>(DEFAULT_FORM_STATE);
  const activeBackendRef = useRef<ApiBackendId>('node');

  const runSearch = useCallback(
    async (
      form: SearchFormState,
      page: number,
      action: SearchTriggerAction,
      backendId: ApiBackendId = activeBackendRef.current
    ) => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      const controller = new AbortController();
      abortControllerRef.current = controller;

      const requestId = ++requestIdRef.current;
      setLoading(true);

      const backendConfig = getApiBackendOption(backendId);
      const actionSpan = getTracer().startSpan('ui.user.search', {
        attributes: {
          'ui.action': action,
          'search.page': page,
          'api.backend': backendConfig.id,
          'url.path': backendConfig.endpoint,
        },
      });
      const actionCtx = trace.setSpan(context.active(), actionSpan);

      logUi(
        'INFO',
        'ui.search.submitted',
        { 'ui.action': action, 'search.page': page, 'api.backend': backendConfig.id },
        actionCtx
      );

      const rawInput = toRawSearchInput(form, page);
      const validation = validateSearchInput(rawInput);
      if (!validation.ok) {
        actionSpan.setStatus({ code: SpanStatusCode.ERROR, message: validation.error.message });
        logUi(
          'WARN',
          'ui.search.client_validation_failed',
          {
            'ui.action': action,
            'api.backend': backendConfig.id,
            'error.code': validation.error.code,
            'error.field': validation.error.field ?? '',
          },
          actionCtx
        );
        actionSpan.end();
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
        const response = await context.with(actionCtx, () =>
          backendConfig.id === 'node'
            ? searchFn(rawInput, controller.signal)
            : searchFn(rawInput, controller.signal, {
                backend: backendConfig.id,
                endpoint: backendConfig.endpoint,
              })
        );
        if (requestId !== requestIdRef.current) {
          logUi(
            'INFO',
            'ui.search.stale_discarded',
            { 'ui.action': action, 'search.page': page },
            actionCtx
          );
          return;
        }
        setHasSearched(true);
        if (response.ok) {
          setListings(response.listings);
          setPagination(response.pagination);
          setError(null);
          logUi(
            'INFO',
            'ui.search.results_rendered',
            { 'ui.action': action, 'search.returnedCount': response.listings.length },
            actionCtx
          );
        } else {
          setListings([]);
          setPagination(null);
          setError(response.error.message);
          actionSpan.setStatus({ code: SpanStatusCode.ERROR, message: response.error.message });
          logUi(
            'WARN',
            'ui.search.error_rendered',
            { 'ui.action': action, 'error.code': response.error.code },
            actionCtx
          );
        }
      } catch (err: unknown) {
        if ((err as { name?: string })?.name === 'AbortError') {
          logUi('INFO', 'ui.search.request_aborted', { 'ui.action': action }, actionCtx);
          return;
        }
        if (requestId !== requestIdRef.current) {
          logUi(
            'INFO',
            'ui.search.stale_discarded',
            { 'ui.action': action, 'search.page': page },
            actionCtx
          );
          return;
        }
        const message = err instanceof Error ? err.message : 'An unexpected error occurred.';
        const errorObj = err instanceof Error ? err : new Error(message);
        actionSpan.recordException(errorObj);
        actionSpan.setStatus({ code: SpanStatusCode.ERROR, message });
        logUi(
          'ERROR',
          'ui.search.unexpected_error',
          { 'ui.action': action, 'error.message': message },
          actionCtx
        );
        setHasSearched(true);
        setListings([]);
        setPagination(null);
        setError(message);
      } finally {
        actionSpan.end();
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

  const handleBackendChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setApiBackend(e.target.value as ApiBackendId);
  };

  const handleSearch = () => {
    activeFormRef.current = formState;
    activeBackendRef.current = apiBackend;
    runSearch(formState, 1, 'search', apiBackend);
  };

  const handleReset = () => {
    setFormState(DEFAULT_FORM_STATE);
    activeFormRef.current = DEFAULT_FORM_STATE;
    activeBackendRef.current = apiBackend;
    runSearch(DEFAULT_FORM_STATE, 1, 'reset', apiBackend);
  };

  const handlePreviousPage = () => {
    if (pagination && pagination.page > 1) {
      runSearch(activeFormRef.current, pagination.page - 1, 'previous_page');
    }
  };

  const handleNextPage = () => {
    if (pagination && pagination.page < pagination.totalPages) {
      runSearch(activeFormRef.current, pagination.page + 1, 'next_page');
    }
  };

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 2,
          mb: 3,
        }}
      >
        <Typography variant="h4" component="h1" fontWeight="bold">
          Real Estate Search
        </Typography>
        <TextField
          select
          size="small"
          id="api-backend"
          label="API Backend"
          value={apiBackend}
          onChange={handleBackendChange}
          sx={{ minWidth: 220 }}
        >
          {API_BACKENDS.map((backend) => (
            <MenuItem key={backend.id} value={backend.id}>
              {backend.label}
            </MenuItem>
          ))}
        </TextField>
      </Box>

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
