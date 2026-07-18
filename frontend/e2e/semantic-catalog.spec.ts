import { expect, test } from '@playwright/test';

test('navigates resource centers and explains a two-fact query', async ({ page }) => {
  await page.route('**/api/v1/datasets?*', (route) =>
    route.fulfill({ json: { items: [], total: 0, page: 0, size: 20 } }),
  );
  await page.route('**/api/v1/semantic-objects:search', async (route) => {
    const body = route.request().postDataJSON();
    const q = String(body.query);
    await route.fulfill({
      json: q.includes('region')
        ? [
            {
              type: 'DIMENSION',
              id: 'd-region',
              datasetId: 'regions',
              name: 'region',
              displayName: 'Region',
            },
          ]
        : q.includes('tickets')
          ? [
              {
                type: 'METRIC',
                id: 'm-tickets',
                datasetId: 'tickets',
                name: 'tickets',
                displayName: 'Tickets',
              },
            ]
          : [
              {
                type: 'METRIC',
                id: 'm-sales',
                datasetId: 'orders',
                name: 'sales',
                displayName: 'Sales',
              },
            ],
    });
  });
  await page.route('**/api/v1/queries:validate', (route) =>
    route.fulfill({
      json: {
        valid: true,
        metrics: [],
        dimensions: [],
        aggregates: [
          {
            datasetId: 'orders',
            metricIds: ['m-sales'],
            dimensionIds: ['d-region'],
            relationshipIds: ['r1'],
          },
          {
            datasetId: 'tickets',
            metricIds: ['m-tickets'],
            dimensionIds: ['d-region'],
            relationshipIds: ['r2'],
          },
        ],
        resolvedRelationshipIds: ['r1', 'r2'],
        parameterCount: 0,
      },
    }),
  );
  await page.route('**/api/v1/queries', (route) =>
    route.fulfill({
      json: {
        columns: [
          { name: 'region', type: 'VARCHAR' },
          { name: 'sales', type: 'DECIMAL' },
          { name: 'tickets', type: 'BIGINT' },
        ],
        rows: [
          ['East', 100, null],
          ['West', null, 3],
        ],
        metrics: [],
        dimensions: [],
      },
    }),
  );
  await page.goto('/query');
  await expect(page.getByRole('main').getByText('查询工作台', { exact: true })).toBeVisible();
  const metricBox = page.getByRole('combobox').first();
  await metricBox.fill('sales');
  await page.locator('.ant-select-item-option-content').filter({ hasText: 'Sales' }).click();
  await metricBox.fill('tickets');
  await page.locator('.ant-select-item-option-content').filter({ hasText: 'Tickets' }).click();
  const dimensionBox = page.getByRole('combobox').nth(1);
  await dimensionBox.fill('region');
  await page.locator('.ant-select-item-option-content').filter({ hasText: 'Region' }).click();
  await page.keyboard.press('Escape');
  await expect(page.getByText('聚合子查询 1')).toBeVisible();
  await expect(page.getByText('聚合子查询 2')).toBeVisible();
  await page.getByRole('button', { name: '执行查询' }).click();
  await expect(page.getByText('East')).toBeVisible();
  await expect(page.getByText('West')).toBeVisible();
  await expect(page.getByText('NULL').first()).toBeVisible();
});
