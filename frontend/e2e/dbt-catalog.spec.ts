import { expect, test } from '@playwright/test';

test('opens a published dbt catalog and queries its fixed release', async ({ page }) => {
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
    if (url.pathname.endsWith('/queries/query-1'))
      return route.fulfill({
        json: {
          queryId: 'query-1',
          releaseId: 'release-1',
          targetCommitSha: 'abc123',
          mode: 'QUERY',
          status: 'COMPLETED',
          columns: [{ name: 'revenue', type: 'DECIMAL' }],
          rows: [['123.45']],
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
    if (url.pathname.endsWith('/queries'))
      return route.fulfill({
        status: 202,
        json: { queryId: 'query-1', releaseId: 'release-1', status: 'PENDING' },
      });
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
  await expect(page.getByText('dbt 只读目录')).toBeVisible();
  await page.getByRole('link', { name: '固定版本查询' }).click();
  await expect(page.getByText('releaseId：release-1')).toBeVisible();
  await page.getByRole('button', { name: '运行查询' }).click();
  await expect(page.getByText('123.45')).toBeVisible();
  await page.goto('/metrics');
  await expect(page).toHaveURL(/\/dbt\/projects$/);
});
