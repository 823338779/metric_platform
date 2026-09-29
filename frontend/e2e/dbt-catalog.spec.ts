import { expect, test } from '@playwright/test';

test('opens a published dbt catalog and queries its fixed release', async ({ page }) => {
  let groupBy: string[] = [];
  let explaining = false;
  const sql = 'select sum(amount) as revenue from orders';
  await page.addInitScript(() => {
    sessionStorage.setItem(
      'metric-platform.auth-session',
      JSON.stringify({
        accessToken: 'test-token',
        tokenType: 'Bearer',
        expiresIn: 36000,
        identity: {
          identityId: 'admin',
          subject: 'admin',
          displayName: 'Administrator',
          authVersion: 1,
        },
      }),
    );
  });
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: { identityId: 'admin', subject: 'admin', displayName: 'Administrator', authVersion: 1 },
    }),
  );
  await page.route(/\/api\/v1\/semantic-projects(?:\/|\?|$)/, (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/queries/query-1') && explaining)
      return route.fulfill({
        json: {
          queryId: 'query-1',
          releaseId: 'release-1',
          targetCommitSha: 'abc123',
          mode: 'EXPLAIN',
          status: 'COMPLETED',
          columns: [],
          rows: [],
          sql,
        },
      });
    if (url.pathname.endsWith('/queries/query-1'))
      return route.fulfill({
        json: {
          queryId: 'query-1',
          releaseId: 'release-1',
          targetCommitSha: 'abc123',
          mode: 'QUERY',
          status: 'COMPLETED',
          columns: groupBy.length
            ? [
                { name: 'metric_time__month', type: 'DATE' },
                { name: 'region', type: 'str' },
                { name: 'revenue', type: 'DECIMAL' },
              ]
            : [{ name: 'revenue', type: 'DECIMAL' }],
          rows: groupBy.length
            ? [
                ['2026-09-01', 'North', '123.45'],
                ['2026-09-01', 'South', '100.00'],
              ]
            : [['123.45']],
          errorCode: null,
          truncated: false,
          durationMs: 3,
        },
      });
    if (url.pathname.endsWith('/query-options'))
      return route.fulfill({
        json: {
          releaseId: 'release-1',
          metrics: [{ name: 'revenue' }],
          dimensions: [{ token: 'region', name: 'Region' }],
          timeDimensions: [{ token: 'metric_time__month', granularity: 'month' }],
          allowedFilters: ['='],
        },
      });
    if (url.pathname.endsWith('/queries') || url.pathname.endsWith('/queries:explain')) {
      explaining = url.pathname.endsWith('/queries:explain');
      groupBy = route.request().postDataJSON().groupBy;
      return route.fulfill({
        status: 202,
        json: { queryId: 'query-1', releaseId: 'release-1', status: 'PENDING' },
      });
    }
    if (url.pathname.endsWith('/catalog'))
      return route.fulfill({
        json: {
          releaseId: 'release-1',
          page: 0,
          size: 20,
          total: 1,
          resources: [
            {
              resourceId: 'metric-revenue',
              nativeId: 'metric.revenue',
              kind: 'METRIC',
              name: 'revenue',
              definitionJson: '{}',
              sourcePath: 'models/metrics.yml',
              queryable: true,
            },
          ],
        },
      });
    return route.fulfill({
      json: [
        {
          projectId: 'sales',
          observedSha: 'abc123',
          activeReleaseId: 'release-1',
          lastObservationStatus: 'PUBLISHED',
        },
      ],
    });
  });

  await page.goto('/');
  await page.getByRole('link', { name: '查看目录' }).click();
  await expect(page.getByText('资源目录')).toBeVisible();
  await page.getByRole('tab', { name: '指标', exact: true }).click();
  await expect(page).toHaveURL(/kind=METRIC/);
  await page.getByRole('link', { name: '探索数据' }).click();
  await expect(page.getByText('releaseId：release-1')).toBeVisible();
  await page.getByRole('button', { name: '运行查询' }).click();
  await expect(page.getByRole('cell', { name: '123.45', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '指标数值' })).toBeVisible();
  await page.getByRole('combobox', { name: '维度', exact: true }).click();
  await page.getByText('Region', { exact: true }).click();
  await page.getByRole('combobox', { name: '维度', exact: true }).press('Escape');
  await page.getByRole('combobox', { name: '日期粒度' }).click();
  await page.getByText('月', { exact: true }).click();
  await page.getByRole('button', { name: '运行查询' }).click();
  await expect(page.getByRole('img', { name: '时间序列图' })).toBeVisible();
  expect(groupBy).toEqual(['region', 'metric_time__month']);
  await expect(page.getByRole('cell', { name: 'North', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'South', exact: true })).toBeVisible();
  await page.getByRole('radio', { name: '仅明细' }).check();
  await expect(page.getByRole('img', { name: '时间序列图' })).toHaveCount(0);
  await page.getByRole('button', { name: '解释查询' }).click();
  await expect(page.getByLabel('生成的 SQL')).toHaveText(sql);
  await expect(page.getByRole('table')).toHaveCount(0);
  await page.goto('/metrics');
  await expect(page).toHaveURL(/\/dbt\/projects$/);
});
