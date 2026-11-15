import { expect, test, type Page } from '@playwright/test';

const BACKENDS = [
  { label: 'TypeScript (Node.js)', serviceName: 'real-estate-api', endpoint: '/graphql' },
  { label: 'Go', serviceName: 'real-estate-api-go', endpoint: '/api/go/graphql' },
  { label: 'Java', serviceName: 'real-estate-api-java', endpoint: '/api/java/graphql' },
] as const;

async function selectBackend(page: Page, label: string): Promise<void> {
  await page.getByLabel(/api backend/i).click();
  await page.getByRole('option', { name: label, exact: true }).click();
}

for (const backend of BACKENDS) {
  test.describe(`Real Estate Search E2E Journeys [${backend.label}]`, () => {
    test('initial load shows no results until user presses Search, then displays ranked listings and pagination metadata', async ({
      page,
    }) => {
      await page.goto('/');
      await selectBackend(page, backend.label);
      await expect(page.getByText(/Page 1 of/i)).not.toBeVisible();
      await expect(page.getByText(/no listings matched/i)).not.toBeVisible();

      const [req] = await Promise.all([
        page.waitForRequest((r) => r.url().endsWith(backend.endpoint) && r.method() === 'POST'),
        page.getByRole('button', { name: /search/i }).click(),
      ]);
      expect((await req.response())?.status()).toBe(200);

      await expect(page.getByText(/Page 1 of/i)).toBeVisible();
      await expect(page.getByRole('button', { name: /previous page/i })).toBeDisabled();
      await expect(page.getByText(/\$\d{1,3}(,\d{3})*/).first()).toBeVisible();
      await expect(page.getByText(/Relevance:\s*\d+\.\d{2}/i).first()).toBeVisible();
    });

    test('filtering by city, price range, bedrooms, keyword, and targetBudget updates ranked results', async ({
      page,
    }) => {
      await page.goto('/');
      await selectBackend(page, backend.label);
      await page.getByLabel(/min price/i).fill('400000');
      await page.getByLabel(/max price/i).fill('600000');
      await page.getByLabel(/city/i).fill('Austin');
      await page.getByLabel(/keyword/i).fill('pool');
      await page.getByLabel(/min bedrooms/i).click();
      await page.getByRole('option', { name: '3', exact: true }).click();
      await page.getByLabel(/target budget/i).fill('500000');

      const [req] = await Promise.all([
        page.waitForRequest((r) => r.url().endsWith(backend.endpoint) && r.method() === 'POST'),
        page.getByRole('button', { name: /search/i }).click(),
      ]);
      expect((await req.response())?.status()).toBe(200);

      await expect(page.getByText(/Austin/i).first()).toBeVisible();
      await expect(page.getByText(/pool/i).first()).toBeVisible();
      await expect(page.getByText(/Relevance:\s*\d+\.\d{2}/i).first()).toBeVisible();
    });

    test('pagination navigation preserves filters via Page Size dropdown and resets to page 1 on filter modification', async ({
      page,
    }) => {
      await page.goto('/');
      await selectBackend(page, backend.label);
      await page.getByLabel(/page size/i).click();
      await page.getByRole('option', { name: '5', exact: true }).click();
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
      await selectBackend(page, backend.label);
      await page.getByLabel(/city/i).fill('Atlantis');
      await page.getByRole('button', { name: /search/i }).click();

      await expect(page.getByText(/no listings matched/i)).toBeVisible();
      await expect(page.getByText(/broaden/i)).toBeVisible();
    });

    test('validation error clears stale results and recovers on Reset Filters', async ({ page }) => {
      await page.goto('/');
      await selectBackend(page, backend.label);
      await page.getByLabel(/min price/i).fill('800000');
      await page.getByLabel(/max price/i).fill('200000');
      await page.getByLabel(/min bedrooms/i).click();
      await page.getByRole('option', { name: '3', exact: true }).click();
      await page.getByRole('button', { name: /search/i }).click();

      await expect(page.getByRole('alert')).toContainText(/minPrice must not exceed maxPrice/i);

      await page.getByRole('button', { name: /reset filters/i }).click();
      await expect(page.getByLabel(/min bedrooms/i)).toHaveText('Any');
      await expect(page.getByText(/Page 1 of/i)).toBeVisible();
    });

    test('network or API failure displays alert, clears listings, and recovers upon retry', async ({
      page,
    }) => {
      await page.goto('/');
      await selectBackend(page, backend.label);
      await page.getByRole('button', { name: /search/i }).click();
      await expect(page.getByText(/Page 1 of/i)).toBeVisible();

      // Intercept GraphQL requests to simulate network failure
      await page.route('**/graphql', (route) => route.abort('failed'));
      await page.getByRole('button', { name: /search/i }).click();

      await expect(page.getByRole('alert')).toBeVisible();

      await page.unroute('**/graphql');
      await page.getByRole('button', { name: /reset filters/i }).click();

      await expect(page.getByRole('alert')).not.toBeVisible();
      await expect(page.getByText(/Page 1 of/i)).toBeVisible();
    });
  });
}

test('switching between all three APIs sends linked W3C distributed traces to Jaeger', async ({
  page,
  request,
}) => {
  await page.goto('/');

  const capturedTraces: Array<{ traceId: string; serviceName: string }> = [];

  for (const backend of BACKENDS) {
    await selectBackend(page, backend.label);
    await page.getByLabel(/city/i).fill('Austin');
    await page.getByLabel(/target budget/i).fill('500000');

    const [graphqlRequest] = await Promise.all([
      page.waitForRequest(
        (req) => req.url().endsWith(backend.endpoint) && req.method() === 'POST'
      ),
      page.getByRole('button', { name: /search/i }).click(),
    ]);

    const traceparent = graphqlRequest.headers().traceparent;
    expect(traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
    const traceId = traceparent.split('-')[1];
    capturedTraces.push({ traceId, serviceName: backend.serviceName });

    const response = await graphqlRequest.response();
    expect(response?.status()).toBe(200);
    await expect(page.getByText(/Austin/i).first()).toBeVisible();
    await expect(page.getByText(/Relevance:\s*\d+\.\d{2}/i).first()).toBeVisible();
  }

  await expect
    .poll(
      async () => {
        const servicesRes = await request.get('http://127.0.0.1:16686/api/services');
        if (!servicesRes.ok()) return [];
        const body = (await servicesRes.json()) as { data?: string[] };
        return body.data ?? [];
      },
      { timeout: 10000, intervals: [500, 1000] }
    )
    .toEqual(
      expect.arrayContaining([
        'real-estate-ui',
        'real-estate-api',
        'real-estate-api-go',
        'real-estate-api-java',
      ])
    );

  for (const { traceId, serviceName } of capturedTraces) {
    await expect
      .poll(
        async () => {
          const traceRes = await request.get(`http://127.0.0.1:16686/api/traces/${traceId}`);
          if (!traceRes.ok()) return [];
          const traceBody = (await traceRes.json()) as {
            data?: Array<{
              processes: Record<string, { serviceName: string }>;
            }>;
          };
          const trace = traceBody.data?.[0];
          if (!trace) return [];
          return Object.values(trace.processes).map((p) => p.serviceName);
        },
        { timeout: 10000, intervals: [500, 1000] }
      )
      .toEqual(expect.arrayContaining(['real-estate-ui', serviceName]));
  }
});
