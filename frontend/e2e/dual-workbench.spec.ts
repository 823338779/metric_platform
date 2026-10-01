import { expect, test } from '@playwright/test';

const PROJECT = 'ecommerce_metrics';
const RELEASE = '6e707522';
const METRIC = `metric.${PROJECT}.gross_amount`;
const MODEL = `semantic_model.${PROJECT}.fct_order_items`;
const ROOT = `/dbt/projects/${PROJECT}/catalog?releaseId=${RELEASE}&resourceId=${METRIC}`;
const identity = {
  identityId: 'fixture',
  subject: 'fixture',
  displayName: '演示账号',
  authVersion: 1,
};
const resources = [
  ...['gross_amount', 'order_count', 'average_order_value'].map((name) => ({
    resourceId: `metric.${PROJECT}.${name}`,
    name,
    kind: 'METRIC',
    queryable: true,
  })),
  ...['brand', 'category', 'ordered_at'].map((name) => ({
    resourceId: `dimension.${PROJECT}.${name}`,
    name,
    kind: 'DIMENSION',
    queryable: false,
  })),
  ...['dim_products', 'fct_order_items'].map((name) => ({
    resourceId: `semantic_model.${PROJECT}.${name}`,
    name,
    kind: 'SEMANTIC_MODEL',
    queryable: false,
  })),
].map((resource) => ({
  ...resource,
  nativeId: resource.resourceId,
  definitionJson: '{}',
  sourcePath: null,
}));

test('dual workbench preserves the release and selected metric from definition to analysis', async ({
  page,
}) => {
  await page.addInitScript(
    (value) =>
      sessionStorage.setItem(
        'metric-platform.auth-session',
        JSON.stringify({
          accessToken: 'fixture-token',
          tokenType: 'Bearer',
          expiresIn: 36000,
          identity: value,
        }),
      ),
    identity,
  );
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: identity }));
  await page.route('**/api/v1/semantic-projects**', (route) => {
    const path = decodeURIComponent(new URL(route.request().url()).pathname);
    if (path.endsWith('/semantic-projects'))
      return route.fulfill({ json: [{ projectId: PROJECT, activeReleaseId: RELEASE }] });
    if (path.endsWith('/releases')) return route.fulfill({ json: [] });
    if (path.endsWith('/catalog'))
      return route.fulfill({
        json: { releaseId: RELEASE, resources, total: resources.length, page: 0, size: 20 },
      });
    if (path.endsWith('/presentation'))
      return route.fulfill({
        json: {
          releaseId: RELEASE,
          resourceId: METRIC,
          kind: 'METRIC',
          title: 'gross_amount',
          queryable: true,
          sections: [
            {
              title: '指标定义',
              fields: { description: '所有订单明细折后总额。', type: 'simple', agg: 'sum' },
            },
          ],
          nativeDetails: {
            type: 'simple',
            type_params: { metric_aggregation_params: { agg: 'sum' } },
          },
        },
      });
    if (path.endsWith('/lineage'))
      return route.fulfill({
        json: {
          releaseId: RELEASE,
          resourceId: METRIC,
          dependencies: [
            { upstreamResourceId: MODEL, downstreamResourceId: METRIC, kind: 'measure' },
          ],
        },
      });
    if (path.endsWith('/source'))
      return route.fulfill({
        json: {
          releaseId: RELEASE,
          buildSourceSha: RELEASE,
          sourcePath: 'models/metrics.yml',
          content: 'metrics:\n  - name: gross_amount\n    type: simple\n',
        },
      });
    if (path.endsWith('/query-options'))
      return route.fulfill({
        json: { releaseId: RELEASE, dimensions: [], timeDimensions: [], allowedFilters: [] },
      });
    return route.fulfill({
      json: resources.find((resource) => path.endsWith(resource.resourceId)) ?? {},
    });
  });
  await page.setViewportSize({ width: 1536, height: 1024 });
  await page.goto(ROOT);
  await expect(page.getByRole('heading', { name: 'gross_amount', exact: true })).toBeVisible();
  await expect(
    page.getByRole('region', { name: '数据类型' }).getByText('NUMBER · 数值', { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: '打开完整血缘' })).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: '../.impeccable/review/hero-repro.png',
    fullPage: false,
  });
  for (const width of [1440, 1264, 390]) {
    await page.setViewportSize({ width, height: 1024 });
    await expect(page.getByRole('button', { name: '开始分析' })).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      animations: 'disabled',
      path: `../.impeccable/review/${width === 1440 ? 'desktop' : width === 390 ? 'mobile' : 'user-1264'}.png`,
      fullPage: true,
    });
  }
  await page.setViewportSize({ width: 1440, height: 1024 });
  await page.getByRole('button', { name: '开始分析' }).click();
  await expect(page).toHaveURL(/query\?releaseId=6e707522&metric=gross_amount/);
  await expect(page.getByLabel('指标', { exact: true })).toBeVisible();
  await expect(page.getByText('gross_amount', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '运行查询', exact: true })).toBeEnabled();
  await page.screenshot({
    animations: 'disabled',
    path: '../.impeccable/review/analysis-desktop.png',
    fullPage: true,
  });
  const limitInput = page.getByRole('spinbutton', { name: '行数' });
  await limitInput.scrollIntoViewIfNeeded();
  await limitInput.fill('50');
  const inputBox = await limitInput.boundingBox();
  const actionBox = await page.getByRole('button', { name: '运行查询', exact: true }).boundingBox();
  expect(
    inputBox!.x + inputBox!.width <= actionBox!.x || inputBox!.y + inputBox!.height <= actionBox!.y,
  ).toBe(true);
  await page.screenshot({
    animations: 'disabled',
    path: '../.impeccable/review/analysis-settings-bottom.png',
    fullPage: true,
  });
});
