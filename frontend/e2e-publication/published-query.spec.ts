import { expect, test } from '@playwright/test';

test('uses published resource identities and keeps a replaced query readable', async ({ page }) => {
  const identity = {
    identityId: 'admin',
    subject: 'admin',
    displayName: 'Administrator',
    authVersion: 1,
  };
  await page.addInitScript((user) => {
    sessionStorage.setItem(
      'metric-platform.auth-session',
      JSON.stringify({
        accessToken: 'test-token',
        tokenType: 'Bearer',
        expiresIn: 36000,
        identity: user,
      }),
    );
  }, identity);
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: identity }));
  let active = 'A';
  let submitted: Record<string, unknown> | undefined;
  await page.route('**/api/v2/semantic-projects/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/publication'))
      return route.fulfill({ json: { projectId: 'p', activePublication: { releaseId: active } } });
    if (url.pathname.endsWith('/catalog')) {
      if (!url.pathname.includes(`/releases/${active}/`))
        return route.fulfill({ status: 410, json: { status: 410 } });
      return route.fulfill({
        json: {
          releaseId: active,
          page: 1,
          size: 50,
          total: 1,
          resources: [
            {
              resourceId: 'metric.p.revenue',
              nativeId: 'metric.p.revenue',
              kind: 'METRIC',
              name: 'revenue',
              displayName: `销售额 ${active}`,
              capabilities: ['QUERY'],
              attributes: {},
              nativeDetails: {},
            },
          ],
        },
      });
    }
    if (url.pathname.endsWith('/query-options'))
      return route.fulfill({
        json: {
          releaseId: active,
          metricResourceIds: ['metric.p.revenue'],
          options: [
            {
              optionId: 'opaque-region',
              resourceId: null,
              displayName: '地区',
              granularities: [],
              operators: ['='],
            },
          ],
        },
      });
    if (url.pathname.endsWith('/queries')) {
      submitted = route.request().postDataJSON();
      active = 'B';
      return route.fulfill({
        status: 202,
        json: { projectId: 'p', releaseId: 'A', queryId: 'Q', state: 'QUEUED' },
      });
    }
    if (url.pathname.endsWith('/queries/Q'))
      return route.fulfill({
        json: {
          projectId: 'p',
          releaseId: 'A',
          queryId: 'Q',
          state: 'READY',
          columns: [{ name: '销售额', type: 'DECIMAL' }],
          rows: [['31.00']],
        },
      });
    return route.fulfill({ status: 404, json: { status: 404 } });
  });
  await page.goto('/dbt/projects/p/query?releaseId=A');
  await page.getByLabel('指标', { exact: true }).selectOption('metric.p.revenue');
  await page.getByLabel('分组维度', { exact: true }).selectOption('opaque-region');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('cell', { name: '31.00' })).toBeVisible();
  expect(submitted).toMatchObject({
    releaseId: 'A',
    metricResourceIds: ['metric.p.revenue'],
    groupBy: [{ optionId: 'opaque-region' }],
    idempotencyKey: expect.any(String),
  });
  await page.getByRole('button', { name: '刷新已发布版本' }).click();
  await expect(page).toHaveURL(/releaseId=B/);
  await expect(
    page.getByLabel('指标', { exact: true }).getByRole('option', { name: '销售额 B' }),
  ).toBeAttached();
  await expect(page.getByRole('button', { name: '查询', exact: true })).toBeDisabled();
});
