import {
  DEFAULT_PAGE,
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  type RawSearchInput,
  type SearchError,
  type ValidatedSearchInput,
} from './types.js';

export type ValidationResult =
  | { ok: true; value: ValidatedSearchInput }
  | { ok: false; error: SearchError };

function parseNumeric(
  value: unknown,
  fieldName: string,
): { parsed?: number; omitted: boolean; error?: SearchError } {
  if (value === undefined || value === null) {
    return { omitted: true };
  }

  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (trimmed === '') {
      return { omitted: true };
    }
    if (!/^-?\d+(\.\d+)?$/.test(trimmed)) {
      return {
        omitted: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: fieldName,
          message: `${fieldName} must be a valid number.`,
        },
      };
    }
    const num = Number(trimmed);
    if (!Number.isFinite(num)) {
      return {
        omitted: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: fieldName,
          message: `${fieldName} must be a finite number.`,
        },
      };
    }
    return { omitted: false, parsed: num };
  }

  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      return {
        omitted: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: fieldName,
          message: `${fieldName} must be a finite number.`,
        },
      };
    }
    return { omitted: false, parsed: value };
  }

  return {
    omitted: false,
    error: {
      code: 'VALIDATION_ERROR',
      field: fieldName,
      message: `${fieldName} must be a number or numeric string.`,
    },
  };
}

export function validateSearchInput(raw: RawSearchInput): ValidationResult {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    return {
      ok: false,
      error: {
        code: 'VALIDATION_ERROR',
        field: null,
        message: 'Search input must be an object.',
      },
    };
  }

  // 1. minPrice
  let minPrice: number | undefined;
  const minPriceRes = parseNumeric(raw.minPrice, 'minPrice');
  if (minPriceRes.error) {
    return { ok: false, error: minPriceRes.error };
  }
  if (!minPriceRes.omitted) {
    if (minPriceRes.parsed! < 0) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: 'minPrice',
          message: 'minPrice must be greater than or equal to 0.',
        },
      };
    }
    minPrice = minPriceRes.parsed;
  }

  // 2. maxPrice
  let maxPrice: number | undefined;
  const maxPriceRes = parseNumeric(raw.maxPrice, 'maxPrice');
  if (maxPriceRes.error) {
    return { ok: false, error: maxPriceRes.error };
  }
  if (!maxPriceRes.omitted) {
    if (maxPriceRes.parsed! < 0) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: 'maxPrice',
          message: 'maxPrice must be greater than or equal to 0.',
        },
      };
    }
    maxPrice = maxPriceRes.parsed;
  }

  // 3. minPrice <= maxPrice
  if (minPrice !== undefined && maxPrice !== undefined && minPrice > maxPrice) {
    return {
      ok: false,
      error: {
        code: 'VALIDATION_ERROR',
        field: 'minPrice',
        message: 'minPrice must not exceed maxPrice.',
      },
    };
  }

  // 4. minBedrooms
  let minBedrooms: number | undefined;
  const minBedroomsRes = parseNumeric(raw.minBedrooms, 'minBedrooms');
  if (minBedroomsRes.error) {
    return { ok: false, error: minBedroomsRes.error };
  }
  if (!minBedroomsRes.omitted) {
    if (!Number.isInteger(minBedroomsRes.parsed!) || minBedroomsRes.parsed! < 0) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: 'minBedrooms',
          message: 'minBedrooms must be an integer greater than or equal to 0.',
        },
      };
    }
    minBedrooms = minBedroomsRes.parsed;
  }

  // 5. targetBudget
  let targetBudget: number | undefined;
  const targetBudgetRes = parseNumeric(raw.targetBudget, 'targetBudget');
  if (targetBudgetRes.error) {
    return { ok: false, error: targetBudgetRes.error };
  }
  if (!targetBudgetRes.omitted) {
    if (targetBudgetRes.parsed! <= 0) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: 'targetBudget',
          message: 'targetBudget must be greater than 0.',
        },
      };
    }
    targetBudget = targetBudgetRes.parsed;
  }

  // 6. pageSize
  let pageSize: number = DEFAULT_PAGE_SIZE;
  const pageSizeRes = parseNumeric(raw.pageSize, 'pageSize');
  if (pageSizeRes.error) {
    return { ok: false, error: pageSizeRes.error };
  }
  if (!pageSizeRes.omitted) {
    if (
      !Number.isInteger(pageSizeRes.parsed!) ||
      pageSizeRes.parsed! <= 0 ||
      pageSizeRes.parsed! > MAX_PAGE_SIZE
    ) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: 'pageSize',
          message: `pageSize must be an integer between 1 and ${MAX_PAGE_SIZE}.`,
        },
      };
    }
    pageSize = pageSizeRes.parsed!;
  }

  // 7. page
  let page: number = DEFAULT_PAGE;
  const pageRes = parseNumeric(raw.page, 'page');
  if (pageRes.error) {
    return { ok: false, error: pageRes.error };
  }
  if (!pageRes.omitted) {
    if (!Number.isInteger(pageRes.parsed!) || pageRes.parsed! < 1) {
      return {
        ok: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: 'page',
          message: 'page must be an integer greater than or equal to 1.',
        },
      };
    }
    page = pageRes.parsed!;
  }

  // 8. city
  let city: string | undefined;
  if (raw.city !== undefined && raw.city !== null) {
    if (typeof raw.city !== 'string') {
      return {
        ok: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: 'city',
          message: 'city must be a string.',
        },
      };
    }
    const trimmed = raw.city.trim();
    if (trimmed !== '') {
      city = trimmed;
    }
  }

  // 9. keyword
  let keyword: string | undefined;
  if (raw.keyword !== undefined && raw.keyword !== null) {
    if (typeof raw.keyword !== 'string') {
      return {
        ok: false,
        error: {
          code: 'VALIDATION_ERROR',
          field: 'keyword',
          message: 'keyword must be a string.',
        },
      };
    }
    const trimmed = raw.keyword.trim();
    if (trimmed !== '') {
      keyword = trimmed;
    }
  }

  const value: ValidatedSearchInput = {
    page,
    pageSize,
  };

  if (minPrice !== undefined) value.minPrice = minPrice;
  if (maxPrice !== undefined) value.maxPrice = maxPrice;
  if (minBedrooms !== undefined) value.minBedrooms = minBedrooms;
  if (targetBudget !== undefined) value.targetBudget = targetBudget;
  if (city !== undefined) value.city = city;
  if (keyword !== undefined) value.keyword = keyword;

  return { ok: true, value };
}
