import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A } from '../e2e-publication/build-fixture';

const PROJECT = 'ecommerce_metrics';
const BUILD = BUILD_A;
const METRIC = `metric.${PROJECT}.gross_amount`;
const MODEL = `semantic_model.${PROJECT}.fct_order_items`;
const ROOT = `/dbt/builds/${BUILD_A}/catalog` + `?resourceId=${METRIC}`;
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
  await buildFixture(page, {
    resources: resources.map((item) => ({
      ...item,
      displayName: item.name,
      capabilities: item.queryable ? ['QUERY'] : [],
      attributes: { description: '所有订单明细折后总额。', type: 'simple', agg: 'sum' },
      nativeDetails: { type: 'simple', type_params: { metric_aggregation_params: { agg: 'sum' } } },
    })),
    dependencies: [{ upstreamResourceId: MODEL, downstreamResourceId: METRIC, kind: 'measure' }],
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
    path: test.info().outputPath('hero-repro.png'),
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
      path: test.info().outputPath(`layout-${width}.png`),
      fullPage: true,
    });
  }
  await page.setViewportSize({ width: 1440, height: 1024 });
  await page.getByRole('button', { name: '开始分析' }).click();
  await expect(page).toHaveURL(
    /query\?buildId=.*&metricResourceId=metric.ecommerce_metrics.gross_amount/,
  );
  await expect(page.getByRole('group', { name: '指标', exact: true })).toBeVisible();
  await expect(page.getByText('gross_amount', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '查询', exact: true })).toBeEnabled();
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('analysis-desktop.png'),
    fullPage: true,
  });
  const limitInput = page.getByRole('spinbutton', { name: '行数' });
  await limitInput.scrollIntoViewIfNeeded();
  await limitInput.fill('50');
  const inputBox = await limitInput.boundingBox();
  const actionBox = await page.getByRole('button', { name: '查询', exact: true }).boundingBox();
  expect(
    inputBox!.x + inputBox!.width <= actionBox!.x || inputBox!.y + inputBox!.height <= actionBox!.y,
  ).toBe(true);
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('analysis-settings-bottom.png'),
    fullPage: true,
  });
});
