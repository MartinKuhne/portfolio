import {
  DEFAULT_PAGE,
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  type RawSearchInput,
  type SearchError,
  type ValidatedSearchInput,
} from './types.js';

export const MAX_PG_INT = 2_147_483_647;
export const MAX_CITY_LENGTH = 100;
export const MAX_KEYWORD_LENGTH = 255;

export type ValidationResult =
  | { ok: true; value: ValidatedSearchInput }
  | { ok: false; error: SearchError };

function validationError(field: string | null, message: string): ValidationResult {
  return {
    ok: false,
    error: {
      code: 'VALIDATION_ERROR',
      field,
      message,
    },
  };
}

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

function parseBoundedString(
  value: unknown,
  fieldName: string,
  maxLength: number
): { value?: string; error?: SearchError } {
  if (value === undefined || value === null) {
    return {};
  }
  if (typeof value !== 'string') {
    return {
      error: {
        code: 'VALIDATION_ERROR',
        field: fieldName,
        message: `${fieldName} must be a string.`,
      },
    };
  }
  if (value.includes('\0')) {
    return {
      error: {
        code: 'VALIDATION_ERROR',
        field: fieldName,
        message: `${fieldName} must not contain null characters.`,
      },
    };
  }
  const trimmed = value.trim();
  if (trimmed === '') {
    return {};
  }
  if (trimmed.length > maxLength) {
    return {
      error: {
        code: 'VALIDATION_ERROR',
        field: fieldName,
        message: `${fieldName} must not exceed ${maxLength} characters.`,
      },
    };
  }
  return { value: trimmed };
}

export function validateSearchInput(raw: RawSearchInput): ValidationResult {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    return validationError(null, 'Search input must be an object.');
  }

  // 1. minPrice
  let minPrice: number | undefined;
  const minPriceRes = parseNumeric(raw.minPrice, 'minPrice');
  if (minPriceRes.error) {
    return { ok: false, error: minPriceRes.error };
  }
  if (!minPriceRes.omitted) {
    if (minPriceRes.parsed! < 0) {
      return validationError('minPrice', 'minPrice must be greater than or equal to 0.');
    }
    if (minPriceRes.parsed! > MAX_PG_INT) {
      return validationError('minPrice', `minPrice must not exceed ${MAX_PG_INT}.`);
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
      return validationError('maxPrice', 'maxPrice must be greater than or equal to 0.');
    }
    if (maxPriceRes.parsed! > MAX_PG_INT) {
      return validationError('maxPrice', `maxPrice must not exceed ${MAX_PG_INT}.`);
    }
    maxPrice = maxPriceRes.parsed;
  }

  // 3. minPrice <= maxPrice
  if (minPrice !== undefined && maxPrice !== undefined && minPrice > maxPrice) {
    return validationError('minPrice', 'minPrice must not exceed maxPrice.');
  }

  // 4. minBedrooms
  let minBedrooms: number | undefined;
  const minBedroomsRes = parseNumeric(raw.minBedrooms, 'minBedrooms');
  if (minBedroomsRes.error) {
    return { ok: false, error: minBedroomsRes.error };
  }
  if (!minBedroomsRes.omitted) {
    if (
      !Number.isInteger(minBedroomsRes.parsed!) ||
      minBedroomsRes.parsed! < 0 ||
      minBedroomsRes.parsed! > MAX_PG_INT
    ) {
      return validationError(
        'minBedrooms',
        `minBedrooms must be an integer between 0 and ${MAX_PG_INT}.`
      );
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
    if (targetBudgetRes.parsed! <= 0 || targetBudgetRes.parsed! > Number.MAX_SAFE_INTEGER) {
      return validationError('targetBudget', 'targetBudget must be greater than 0 and within safe numeric limits.');
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
      return validationError(
        'pageSize',
        `pageSize must be an integer between 1 and ${MAX_PAGE_SIZE}.`
      );
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
    if (
      !Number.isInteger(pageRes.parsed!) ||
      pageRes.parsed! < 1 ||
      pageRes.parsed! > MAX_PG_INT
    ) {
      return validationError('page', `page must be an integer between 1 and ${MAX_PG_INT}.`);
    }
    page = pageRes.parsed!;
  }

  // 8. city
  const cityRes = parseBoundedString(raw.city, 'city', MAX_CITY_LENGTH);
  if (cityRes.error) {
    return { ok: false, error: cityRes.error };
  }

  // 9. keyword
  const keywordRes = parseBoundedString(raw.keyword, 'keyword', MAX_KEYWORD_LENGTH);
  if (keywordRes.error) {
    return { ok: false, error: keywordRes.error };
  }

  const value: ValidatedSearchInput = {
    page,
    pageSize,
  };

  if (minPrice !== undefined) value.minPrice = minPrice;
  if (maxPrice !== undefined) value.maxPrice = maxPrice;
  if (minBedrooms !== undefined) value.minBedrooms = minBedrooms;
  if (targetBudget !== undefined) value.targetBudget = targetBudget;
  if (cityRes.value !== undefined) value.city = cityRes.value;
  if (keywordRes.value !== undefined) value.keyword = keywordRes.value;

  return { ok: true, value };
}
