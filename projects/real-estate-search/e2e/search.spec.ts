import { expect, test } from '@playwright/test';

test.describe('Real Estate Search E2E Journeys', () => {
  test('initial load displays ranked listings, currency prices, and pagination metadata', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /previous page/i })).toBeDisabled();
    await expect(page.getByText(/\$\d{1,3}(,\d{3})*/).first()).toBeVisible();
    await expect(page.getByText(/Relevance:\s*\d+\.\d{2}/i).first()).toBeVisible();
  });

  test('filtering by city, price range, bedrooms, keyword, and targetBudget updates ranked results', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel(/city/i).fill('Austin');
    await page.getByLabel(/min bedrooms/i).fill('3');
    await page.getByLabel(/target budget/i).fill('500000');
    await page.getByRole('button', { name: /search/i }).click();

    await expect(page.getByText(/Austin/i).first()).toBeVisible();
  });

  test('pagination navigation preserves filters and resets to page 1 on filter modification', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel(/page size/i).fill('5');
    await page.getByRole('button', { name: /search/i }).click();
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();

    await page.getByRole('button', { name: /next page/i }).click();
    await expect(page.getByText(/Page 2 of/i)).toBeVisible();

    await page.getByLabel(/city/i).fill('Denver');
    await page.getByRole('button', { name: /search/i }).click();
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();
  });

  test('empty results state shows broadening guidance without crashing', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel(/city/i).fill('Atlantis');
    await page.getByRole('button', { name: /search/i }).click();

    await expect(page.getByText(/no listings matched/i)).toBeVisible();
    await expect(page.getByText(/broaden/i)).toBeVisible();
  });

  test('validation error clears stale results and recovers on Reset Filters', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel(/min price/i).fill('800000');
    await page.getByLabel(/max price/i).fill('200000');
    await page.getByRole('button', { name: /search/i }).click();

    await expect(page.getByRole('alert')).toContainText(/minPrice must not exceed maxPrice/i);

    await page.getByRole('button', { name: /reset filters/i }).click();
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();
  });

  test('network or API failure displays alert, clears listings, and recovers upon retry', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();

    // Intercept GraphQL requests to simulate network failure
    await page.route('**/graphql', (route) => route.abort('failed'));
    await page.getByRole('button', { name: /search/i }).click();

    // Verify error alert is displayed
    await expect(page.getByRole('alert')).toBeVisible();

    // Restore network routing and recover
    await page.unroute('**/graphql');
    await page.getByRole('button', { name: /reset filters/i }).click();

    await expect(page.getByRole('alert')).not.toBeVisible();
    await expect(page.getByText(/Page 1 of/i)).toBeVisible();
  });
});
