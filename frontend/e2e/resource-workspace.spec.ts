import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A } from '../e2e-publication/build-fixture';

const METRIC = 'metric.shop.units_sold';
const MODEL = 'model.shop.fct_order_items';
const SEMANTIC = 'semantic_model.shop.order_items';
const BUILD = BUILD_A;
const ROOT = `/dbt/builds/${BUILD_A}/catalog`;
const SOURCE =
  'version: 2\n\nmetrics:\n  - name: units_sold\n    label: 商品销售数量\n    type: simple\n    type_params:\n      measure: units_sold\n';
const resources = [
  {
    resourceId: METRIC,
    nativeId: METRIC,
    kind: 'METRIC',
    name: 'units_sold',
    description: '商品销售数量',
    sourcePath: 'models/metrics.yml',
    queryable: true,
    definitionJson: '{}',
  },
  {
    resourceId: SEMANTIC,
    nativeId: SEMANTIC,
    kind: 'SEMANTIC_MODEL',
    name: 'order_items',
    description: '订单商品语义模型',
    sourcePath: 'models/semantic.yml',
    queryable: false,
    definitionJson: '{}',
  },
];
const dependencies = [
  { upstreamResourceId: MODEL, downstreamResourceId: SEMANTIC, kind: 'ref' },
  {
    upstreamResourceId: 'model.shop.very_long_resource_name_for_refunded_order_items',
    downstreamResourceId: SEMANTIC,
    kind: 'ref',
  },
  { upstreamResourceId: SEMANTIC, downstreamResourceId: METRIC, kind: 'measure' },
];

test('resource tree navigation, readable lineage and versioned YAML work on desktop and mobile', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await buildFixture(page, {
    source: SOURCE,
    dependencies,
    resources: resources.map((item) => ({
      ...item,
      displayName: item.name,
      capabilities: item.queryable ? ['QUERY'] : [],
      attributes: { type: 'simple', type_params: { measure: 'units_sold' } },
      nativeDetails: { type: 'simple' },
    })),
    resource: (resourceId) => ({
      resourceId,
      nativeId: resourceId,
      kind: 'TABLE',
      name: 'fct_order_items',
      sourcePath: 'models/fct_order_items.sql',
      capabilities: [],
      attributes: {},
      nativeDetails: {},
    }),
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(ROOT);
  await page.getByRole('treeitem', { name: /units_sold/ }).click();
  await expect(page).toHaveURL(/resourceId=metric.shop.units_sold/);
  await expect(page.getByRole('heading', { name: 'units_sold' })).toBeVisible();
  await page.getByRole('button', { name: '全部折叠' }).click();
  await expect(page.getByRole('treeitem', { name: /units_sold/ })).toHaveCount(0);
  await page.getByRole('button', { name: '定位当前资源' }).click();
  await expect(page.getByRole('treeitem', { name: /units_sold/ })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(page.getByText('商品销售数量', { exact: true })).toBeVisible();
  await page.screenshot({
    path: test.info().outputPath('metric-redesign-detail.png'),
    fullPage: true,
  });
  await page.getByRole('tab', { name: '血缘', exact: true }).click();
  const graph = page.getByRole('region', { name: '资源血缘图' });
  await expect(graph.locator('.lineage-node')).toHaveCount(4);
  const boxes = await graph.locator('.lineage-node').evaluateAll((nodes) =>
    nodes.map((node) => {
      const rect = node.getBoundingClientRect();
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
    }),
  );
  for (let i = 0; i < boxes.length; i++)
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i],
        b = boxes[j];
      expect(
        a.x + a.width <= b.x ||
          b.x + b.width <= a.x ||
          a.y + a.height <= b.y ||
          b.y + b.height <= a.y,
      ).toBe(true);
    }
  await page.screenshot({
    path: test.info().outputPath('metric-redesign-lineage.png'),
    fullPage: true,
  });
  await graph.getByRole('button', { name: MODEL, exact: true }).click();
  await graph.getByRole('button', { name: '查看资源' }).click();
  await expect(page.getByRole('heading', { name: 'fct_order_items' })).toBeVisible();
  await expect(page.getByRole('treeitem', { name: /fct_order_items$/ })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await page.getByRole('button', { name: '全部折叠' }).click();
  const splitter = page.getByRole('separator', { name: '调整资源树宽度' });
  await splitter.focus();
  await splitter.press('ArrowRight');
  await expect(splitter).toHaveAttribute('aria-valuenow', '308');
  await expect(page.getByRole('treeitem', { name: /fct_order_items$/ })).toHaveCount(0);
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'units_sold' })).toBeVisible();
  await page.getByRole('tab', { name: 'YAML 源码' }).click();
  await expect(page.getByText('models/metrics.yml', { exact: true }).last()).toBeVisible();
  await expect(page.getByRole('region', { name: 'YAML 源码内容' })).toContainText(
    'measure: units_sold',
  );
  await page.getByRole('button', { name: '定位定义' }).click();
  await expect(page.locator('.source-definition')).toContainText('name: units_sold');
  await page.screenshot({
    path: test.info().outputPath('metric-redesign-source.png'),
    fullPage: true,
  });
  await page.reload();
  await expect(page.getByRole('heading', { name: 'units_sold' })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('tab', { name: '血缘', exact: true }).click();
  await expect(graph.locator('.lineage-node')).toHaveCount(4);
  await graph.getByRole('button', { name: '适应画布' }).click();
  expect(
    await graph
      .locator('.lineage-viewport')
      .evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
  ).toBe(true);
  await graph.getByRole('button', { name: '放大' }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({
    path: test.info().outputPath('metric-redesign-mobile.png'),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});
