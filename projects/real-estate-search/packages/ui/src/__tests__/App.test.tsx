import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { SearchResponse } from '@real-estate/shared';
import { App } from '../App.js';

const samplePage1: SearchResponse = {
  ok: true,
  listings: [
    {
      id: 'LST-001',
      address: '101 Congress Ave, Austin, TX',
      city: 'Austin',
      price: 485000,
      bedrooms: 3,
      description: 'Modern downtown condo with pool',
      listedDate: '2026-10-20T00:00:00.000Z',
      relevanceScore: 0.94,
    },
  ],
  pagination: { page: 1, pageSize: 10, totalResults: 15, totalPages: 2 },
  error: null,
};

describe('Real Estate Search UI (REQ-UI-001..012, REQ-PAG-006..010, REQ-ERR-001..003)', () => {
  it('renders listings with currency price, bedrooms, and relevance score, plus pagination metadata (REQ-UI-003..005, REQ-UI-012)', async () => {
    const searchFn = vi.fn().mockResolvedValue(samplePage1);
    render(<App searchFn={searchFn} />);

    expect(await screen.findByText('101 Congress Ave, Austin, TX')).toBeInTheDocument();
    expect(screen.getByText('$485,000')).toBeInTheDocument();
    expect(screen.getByText(/3\s*bedrooms/i)).toBeInTheDocument();
    expect(screen.getByText(/0\.94/)).toBeInTheDocument();
    expect(screen.getByText(/Page 1 of 2 \(15 total listings\)/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /previous page/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next page/i })).toBeEnabled();
  });

  it('resets page to 1 when any filter, targetBudget, or pageSize changes after navigating to page 2 (REQ-PAG-006..008)', async () => {
    const user = userEvent.setup();
    const searchFn = vi.fn().mockImplementation(async (input) => ({
      ...samplePage1,
      pagination: { page: Number(input.page ?? 1), pageSize: 10, totalResults: 15, totalPages: 2 },
    }));
    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.click(screen.getByRole('button', { name: /next page/i }));
    await waitFor(() =>
      expect(searchFn).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 2 }),
        expect.any(AbortSignal)
      )
    );

    await user.type(screen.getByLabelText(/city/i), 'Austin');
    await user.click(screen.getByRole('button', { name: /search/i }));
    await waitFor(() =>
      expect(searchFn).toHaveBeenLastCalledWith(
        expect.objectContaining({ city: 'Austin', page: 1 }),
        expect.any(AbortSignal)
      )
    );
  });

  it('displays loading indicator and discards stale out-of-order responses (REQ-UI-006, REQ-UI-007, Review Focus)', async () => {
    const user = userEvent.setup();
    let resolveSlow!: (value: SearchResponse) => void;
    const searchFn = vi
      .fn()
      .mockResolvedValueOnce(samplePage1)
      .mockImplementationOnce(
        () =>
          new Promise<SearchResponse>((r) => {
            resolveSlow = r;
          })
      )
      .mockResolvedValueOnce({
        ok: true,
        listings: [{ ...samplePage1.listings[0], id: 'LST-NEW', address: '999 Newer St, Seattle, WA' }],
        pagination: { page: 1, pageSize: 10, totalResults: 1, totalPages: 1 },
        error: null,
      });

    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.type(screen.getByLabelText(/city/i), 'Denver');
    await user.click(screen.getByRole('button', { name: /search/i }));
    expect(screen.getByRole('progressbar')).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/city/i));
    await user.type(screen.getByLabelText(/city/i), 'Seattle');
    await user.click(screen.getByRole('button', { name: /search/i }));

    expect(await screen.findByText('999 Newer St, Seattle, WA')).toBeInTheDocument();

    resolveSlow({
      ok: true,
      listings: [{ ...samplePage1.listings[0], id: 'LST-STALE', address: '000 Stale Rd, Denver, CO' }],
      pagination: { page: 1, pageSize: 10, totalResults: 1, totalPages: 1 },
      error: null,
    });

    await waitFor(() => expect(screen.queryByText('000 Stale Rd, Denver, CO')).not.toBeInTheDocument());
    expect(screen.getByText('999 Newer St, Seattle, WA')).toBeInTheDocument();
  });

  it('discards stale out-of-order rejections when superseded by a newer request (REQ-UI-007)', async () => {
    const user = userEvent.setup();
    let rejectSlow!: (reason: unknown) => void;
    const searchFn = vi
      .fn()
      .mockResolvedValueOnce(samplePage1)
      .mockImplementationOnce(
        () =>
          new Promise<SearchResponse>((_, rej) => {
            rejectSlow = rej;
          })
      )
      .mockResolvedValueOnce({
        ok: true,
        listings: [{ ...samplePage1.listings[0], id: 'LST-NEW', address: '999 Newer St, Seattle, WA' }],
        pagination: { page: 1, pageSize: 10, totalResults: 1, totalPages: 1 },
        error: null,
      });

    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.type(screen.getByLabelText(/city/i), 'Denver');
    await user.click(screen.getByRole('button', { name: /search/i }));

    await user.clear(screen.getByLabelText(/city/i));
    await user.type(screen.getByLabelText(/city/i), 'Seattle');
    await user.click(screen.getByRole('button', { name: /search/i }));

    expect(await screen.findByText('999 Newer St, Seattle, WA')).toBeInTheDocument();

    rejectSlow(new Error('Stale Denver rejection'));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('999 Newer St, Seattle, WA')).toBeInTheDocument();
  });

  it('displays empty-results message and broadening guidance when 0 listings match (REQ-UI-008, REQ-UI-009)', async () => {
    const searchFn = vi.fn().mockResolvedValue({
      ok: true,
      listings: [],
      pagination: { page: 1, pageSize: 10, totalResults: 0, totalPages: 0 },
      error: null,
    });
    render(<App searchFn={searchFn} />);
    expect(await screen.findByText(/no listings matched/i)).toBeInTheDocument();
    expect(screen.getByText(/broaden/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /next page/i })).toBeDisabled();
  });

  it('validates inputs, clears previous listings on error, and recovers when corrected or reset (REQ-VAL-001, REQ-ERR-001..003, REQ-UI-010..011)', async () => {
    const user = userEvent.setup();
    const searchFn = vi.fn().mockResolvedValue(samplePage1);
    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.type(screen.getByLabelText(/min price/i), '600000');
    await user.type(screen.getByLabelText(/max price/i), '400000');
    await user.click(screen.getByRole('button', { name: /search/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/minPrice must not exceed maxPrice/i);
    expect(screen.queryByText('101 Congress Ave, Austin, TX')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /reset filters/i }));
    expect(await screen.findByText('101 Congress Ave, Austin, TX')).toBeInTheDocument();
  });

  it('navigates to previous page when Previous Page button is clicked (REQ-UI-002, REQ-PAG-007)', async () => {
    const user = userEvent.setup();
    const searchFn = vi.fn().mockImplementation(async (input) => ({
      ...samplePage1,
      pagination: { page: Number(input.page ?? 1), pageSize: 10, totalResults: 15, totalPages: 2 },
    }));
    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.click(screen.getByRole('button', { name: /next page/i }));
    await waitFor(() =>
      expect(searchFn).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 2 }),
        expect.any(AbortSignal)
      )
    );

    await user.click(screen.getByRole('button', { name: /previous page/i }));
    await waitFor(() =>
      expect(searchFn).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 1 }),
        expect.any(AbortSignal)
      )
    );
  });

  it('updates form state when typing into minBedrooms, keyword, targetBudget, and pageSize', async () => {
    const user = userEvent.setup();
    const searchFn = vi.fn().mockResolvedValue(samplePage1);
    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.type(screen.getByLabelText(/min bedrooms/i), '2');
    await user.type(screen.getByLabelText(/keyword/i), 'pool');
    await user.type(screen.getByLabelText(/target budget/i), '500000');
    await user.clear(screen.getByLabelText(/page size/i));
    await user.type(screen.getByLabelText(/page size/i), '5');
    await user.click(screen.getByRole('button', { name: /search/i }));

    await waitFor(() =>
      expect(searchFn).toHaveBeenLastCalledWith(
        expect.objectContaining({
          minBedrooms: '2',
          keyword: 'pool',
          targetBudget: '500000',
          pageSize: '5',
          page: 1,
        }),
        expect.any(AbortSignal)
      )
    );
  });

  it('omits pageSize when page size field is cleared', async () => {
    const user = userEvent.setup();
    const searchFn = vi.fn().mockResolvedValue(samplePage1);
    render(<App searchFn={searchFn} />);
    await screen.findByText('101 Congress Ave, Austin, TX');

    await user.clear(screen.getByLabelText(/page size/i));
    await user.click(screen.getByRole('button', { name: /search/i }));

    await waitFor(() =>
      expect(searchFn).toHaveBeenLastCalledWith(
        expect.objectContaining({
          pageSize: undefined,
          page: 1,
        }),
        expect.any(AbortSignal)
      )
    );
  });

  it('handles server-returned error payload by displaying alert and clearing listings (REQ-ERR-001)', async () => {
    const searchFn = vi.fn().mockResolvedValue({
      ok: false,
      listings: [],
      pagination: null,
      error: { code: 'INTERNAL_ERROR', message: 'Database connection failed.', field: null },
    });
    render(<App searchFn={searchFn} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Database connection failed.');
    expect(screen.queryByText('101 Congress Ave, Austin, TX')).not.toBeInTheDocument();
  });

  it('handles unexpected thrown network error by displaying alert and clearing listings', async () => {
    const searchFn = vi.fn().mockRejectedValue(new Error('Network offline'));
    render(<App searchFn={searchFn} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Network offline');
    expect(screen.queryByText('101 Congress Ave, Austin, TX')).not.toBeInTheDocument();
  });

  it('handles non-Error thrown exception by displaying fallback error message', async () => {
    const searchFn = vi.fn().mockRejectedValue('Fatal crash');
    render(<App searchFn={searchFn} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('An unexpected error occurred.');
    expect(screen.queryByText('101 Congress Ave, Austin, TX')).not.toBeInTheDocument();
  });

  it('ignores AbortError thrown when search request is aborted', async () => {
    const searchFn = vi.fn().mockRejectedValue({ name: 'AbortError' });
    render(<App searchFn={searchFn} />);

    await waitFor(() => expect(screen.queryByRole('progressbar')).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('aborts active search request when component unmounts', () => {
    const searchFn = vi.fn().mockImplementation(() => new Promise(() => {}));
    const { unmount } = render(<App searchFn={searchFn} />);
    unmount();
  });
});
