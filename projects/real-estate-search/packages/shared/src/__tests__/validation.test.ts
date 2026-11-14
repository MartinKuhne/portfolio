import { describe, expect, it } from 'vitest';
import * as sharedIndex from '../index.js';
import {
  DEFAULT_PAGE,
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  validateSearchInput,
} from '../validation.js';

describe('validateSearchInput', () => {
  it('applies defaults page=1 and pageSize=10 when omitted (REQ-PAG-002, REQ-PAG-003)', () => {
    const res = validateSearchInput({});
    expect(res).toEqual({ ok: true, value: { page: 1, pageSize: 10 } });
  });

  it('trims city and keyword and drops whitespace-only strings (REQ-FLT-005, REQ-FLT-009)', () => {
    const res = validateSearchInput({ city: '  Austin ', keyword: '   ', minPrice: '' });
    expect(res).toEqual({ ok: true, value: { city: 'Austin', page: 1, pageSize: 10 } });
  });

  it('accepts valid non-empty keyword and city', () => {
    const res = validateSearchInput({ city: 'Austin', keyword: 'pool' });
    expect(res).toEqual({
      ok: true,
      value: { city: 'Austin', keyword: 'pool', page: 1, pageSize: 10 },
    });
  });

  it('accepts valid numeric boundaries 0 and valid numeric strings (REQ-VAL-004, REQ-VAL-005, REQ-VAL-007, REQ-VAL-009, REQ-VAL-010)', () => {
    const res = validateSearchInput({
      minPrice: 0,
      maxPrice: '0',
      minBedrooms: 0,
      pageSize: 100,
      page: 1,
      targetBudget: '500000',
    });
    expect(res).toEqual({
      ok: true,
      value: {
        minPrice: 0,
        maxPrice: 0,
        minBedrooms: 0,
        pageSize: 100,
        page: 1,
        targetBudget: 500000,
      },
    });
  });

  it('accepts valid trimmed numeric strings (REQ-VAL-004, REQ-VAL-005)', () => {
    const res = validateSearchInput({
      minPrice: ' 250000 ',
      maxPrice: ' 500000 ',
      minBedrooms: ' 3 ',
      pageSize: ' 20 ',
      page: ' 2 ',
      targetBudget: ' 400000 ',
    });
    expect(res).toEqual({
      ok: true,
      value: {
        minPrice: 250000,
        maxPrice: 500000,
        minBedrooms: 3,
        pageSize: 20,
        page: 2,
        targetBudget: 400000,
      },
    });
  });

  it('treats undefined, null, and empty or whitespace strings as omitted for optional fields (REQ-FLT-009)', () => {
    const res = validateSearchInput({
      minPrice: null,
      maxPrice: undefined,
      minBedrooms: '',
      targetBudget: '   ',
      city: null,
      keyword: undefined,
      page: undefined,
      pageSize: null,
    });
    expect(res).toEqual({ ok: true, value: { page: 1, pageSize: 10 } });
  });

  it('rejects non-numeric, partial numeric strings, NaN, Infinity, or negative minPrice/maxPrice without coercion (REQ-VAL-003, REQ-VAL-004, REQ-VAL-005)', () => {
    for (const bad of ['abc', '100abc', -1, NaN, Infinity, -Infinity, true, false, {}, []]) {
      const minRes = validateSearchInput({ minPrice: bad });
      expect(minRes.ok).toBe(false);
      if (!minRes.ok) {
        expect(minRes.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'minPrice' });
        expect(minRes.error.message.toLowerCase()).toContain('minprice');
      }

      const maxRes = validateSearchInput({ maxPrice: bad });
      expect(maxRes.ok).toBe(false);
      if (!maxRes.ok) {
        expect(maxRes.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'maxPrice' });
        expect(maxRes.error.message.toLowerCase()).toContain('maxprice');
      }
    }
  });

  it('rejects overflowing numeric strings as not finite', () => {
    const res = validateSearchInput({ minPrice: '9'.repeat(400) });
    expect(res.ok).toBe(false);
    if (!res.ok) {
      expect(res.error).toMatchObject({
        code: 'VALIDATION_ERROR',
        field: 'minPrice',
      });
      expect(res.error.message).toContain('finite');
    }
  });

  it('rejects minPrice > maxPrice (REQ-VAL-006)', () => {
    const res = validateSearchInput({ minPrice: 500000, maxPrice: 400000 });
    expect(res).toEqual({
      ok: false,
      error: {
        code: 'VALIDATION_ERROR',
        field: 'minPrice',
        message: 'minPrice must not exceed maxPrice.',
      },
    });
  });

  it('rejects non-integer or negative minBedrooms (REQ-VAL-007)', () => {
    for (const bad of [-1, 2.5, 'two', '2beds', NaN, Infinity, true, {}, []]) {
      const res = validateSearchInput({ minBedrooms: bad });
      expect(res.ok).toBe(false);
      if (!res.ok) {
        expect(res.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'minBedrooms' });
        expect(res.error.message.toLowerCase()).toContain('minbedrooms');
      }
    }
  });

  it('rejects targetBudget <= 0 or non-numeric (REQ-VAL-008)', () => {
    for (const bad of [0, -100, 'abc', '100k', NaN, Infinity, -Infinity, true, {}, []]) {
      const res = validateSearchInput({ targetBudget: bad });
      expect(res.ok).toBe(false);
      if (!res.ok) {
        expect(res.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'targetBudget' });
        expect(res.error.message.toLowerCase()).toContain('targetbudget');
      }
    }
  });

  it('rejects pageSize <= 0, non-integer, or > MAX_PAGE_SIZE (REQ-VAL-009, REQ-VAL-010)', () => {
    for (const bad of [0, -5, 1.5, 101, 'ten', '10items', NaN, Infinity, true, {}, []]) {
      const res = validateSearchInput({ pageSize: bad });
      expect(res.ok).toBe(false);
      if (!res.ok) {
        expect(res.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'pageSize' });
        expect(res.error.message.toLowerCase()).toContain('pagesize');
      }
    }
  });

  it('rejects page < 1 or non-integer (REQ-VAL-011)', () => {
    for (const bad of [0, -1, 1.2, 'first', '1st', NaN, Infinity, true, {}, []]) {
      const res = validateSearchInput({ page: bad });
      expect(res.ok).toBe(false);
      if (!res.ok) {
        expect(res.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'page' });
        expect(res.error.message.toLowerCase()).toContain('page');
      }
    }
  });

  it('rejects non-string city or keyword (REQ-VAL-003, REQ-ERR-005)', () => {
    for (const bad of [123, true, {}, []]) {
      const cityRes = validateSearchInput({ city: bad });
      expect(cityRes.ok).toBe(false);
      if (!cityRes.ok) {
        expect(cityRes.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'city' });
        expect(cityRes.error.message.toLowerCase()).toContain('city');
      }

      const keywordRes = validateSearchInput({ keyword: bad });
      expect(keywordRes.ok).toBe(false);
      if (!keywordRes.ok) {
        expect(keywordRes.error).toMatchObject({ code: 'VALIDATION_ERROR', field: 'keyword' });
        expect(keywordRes.error.message.toLowerCase()).toContain('keyword');
      }
    }
  });

  it('rejects non-object raw search inputs', () => {
    for (const bad of [null, undefined, 'string', 123, true, []]) {
      const res = validateSearchInput(bad as any);
      expect(res.ok).toBe(false);
      if (!res.ok) {
        expect(res.error).toMatchObject({
          code: 'VALIDATION_ERROR',
          field: null,
          message: 'Search input must be an object.',
        });
      }
    }
  });

  it('formats error objects according to REQ-ERR-005', () => {
    const res = validateSearchInput({ minPrice: -50 });
    expect(res.ok).toBe(false);
    if (!res.ok) {
      expect(res.error).toEqual({
        code: 'VALIDATION_ERROR',
        field: 'minPrice',
        message: expect.stringContaining('minPrice'),
      });
    }
  });
});

describe('index exports', () => {
  it('exports types, constants, and validateSearchInput', () => {
    expect(sharedIndex.validateSearchInput).toBe(validateSearchInput);
    expect(sharedIndex.DEFAULT_PAGE).toBe(DEFAULT_PAGE);
    expect(sharedIndex.DEFAULT_PAGE_SIZE).toBe(DEFAULT_PAGE_SIZE);
    expect(sharedIndex.MAX_PAGE_SIZE).toBe(MAX_PAGE_SIZE);
  });
});
