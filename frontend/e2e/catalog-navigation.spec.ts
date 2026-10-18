import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A } from '../e2e-publication/build-fixture';

const ROOT = `/dbt/builds/${BUILD_A}/catalog`;
const TABLE = 'order_demo_orders';
const SOURCE = 'models/order_business/orders.sql';
const OUTPUTS = ['desktop.png', 'user-1736.png', 'narrow-desktop.png', 'mobile.png'];
const resources = [
  ...['accounts', 'channels', 'customers', 'orders', 'regions', 'statuses'].map((name) => ({
    resourceId: `model.order_demo_${name}`,
    kind: 'TABLE',
    name: `order_demo_${name}`,
    sourcePath: SOURCE,
    description: '用于页面验收的订单业务示例资源。',
    capabilities: ['QUERY'],
    attributes: { columns: [{ name: 'id', data_type: 'INTEGER' }] },
  })),
  ...Array.from({ length: 18 }, (_, index) => ({
    resourceId: `model.long_${index}`,
    kind: 'TABLE',
    name: `order_demo_long_resource_name_for_navigation_${index}`,
    sourcePath: SOURCE,
  })),
  {
    resourceId: 'metric.units_sold',
    kind: 'METRIC',
    name: 'units_sold',
    sourcePath: 'models/metrics.yml',
  },
];

test('两级目录、来源切换、版本侧栏和响应式工作区保留固定版本', async ({ page }) => {
  await buildFixture(page, { resources });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto(ROOT);
  const tree = page.getByRole('tree', { name: '资源树' });
  const orders = tree.getByRole('treeitem', { name: TABLE, exact: true });
  await expect(orders).toHaveAttribute('aria-level', '2');
  await orders.getByText(TABLE, { exact: true }).click();
  await expect(page.getByRole('heading', { name: TABLE, exact: true })).toBeVisible();
  const context = page.getByRole('complementary', { name: '版本上下文' });
  await expect(context).toBeVisible();
  await page.getByText('按来源', { exact: true }).click();
  await expect(tree.getByRole('treeitem', { name: /orders.sql/ }).first()).toHaveAttribute(
    'aria-level',
    '1',
  );
  await expect(orders).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('button', { name: '全部折叠' }).click();
  await expect(orders).toHaveCount(0);
  await page.getByRole('button', { name: '定位当前资源' }).click();
  await expect(orders).toBeVisible();
  await page.getByText('按类型', { exact: true }).click();
  await expect(orders).toBeVisible();
  await expect(page).toHaveURL(/resourceId=model.order_demo_orders/);
  for (const [index, width] of [1440, 1736, 1280, 390].entries()) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 });
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(page.getByRole('heading', { name: TABLE, exact: true })).toBeAttached();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    if (width > 760) {
      const header = page.locator('.catalog-explorer-header');
      const before = await header.boundingBox();
      await page.locator('.resource-tree-body').evaluate((element) => {
        element.scrollTop = element.scrollHeight;
      });
      expect((await header.boundingBox())?.y).toBe(before?.y);
      await page.getByRole('button', { name: '定位当前资源' }).click();
    } else await expect(context).toHaveCount(0);
    await page.mouse.move(0, 0);
    await expect(page.getByRole('tooltip')).toHaveCount(0);
    await page.locator('.resource-tree-body').evaluate((element) => {
      element.scrollTop = 0;
    });
    const capture = await page.screenshot({
      path: test.info().outputPath(OUTPUTS[index]),
      fullPage: true,
      animations: 'disabled',
    });
    expect(capture.readUInt32BE(16)).toBe(width);
  }
  await page.getByRole('button', { name: '版本上下文' }).click();
  await expect(context).toBeVisible();
  await page.getByRole('button', { name: '收起版本上下文' }).click();
  await page.getByText('按来源', { exact: true }).click();
  const all = page.getByRole('tab', { name: '全部资源', exact: true });
  await all.focus();
  await all.press('ArrowRight');
  await expect(page.getByRole('tab', { name: '指标', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(tree.getByText('units_sold', { exact: true })).toBeVisible();
  await expect(page.getByRole('radio', { name: '按来源', exact: true })).toBeChecked();
  await expect(page).toHaveURL(new RegExp(`${BUILD_A}/catalog\\?kind=METRIC`));
});
