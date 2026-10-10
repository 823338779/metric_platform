import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A } from './build-fixture';

const identity = {
  identityId: 'fixture',
  subject: 'fixture',
  displayName: '演示账号',
  authVersion: 1,
};
const VERSION = 'release-a';
const BRANCH = 'production-branch';
const resource = {
  resourceId: 'metric-opaque-id',
  nativeId: 'metric.native.sales',
  kind: 'METRIC',
  name: 'gross_amount',
  displayName: '销售总额',
  description: '所有订单明细折后总额。',
  sourcePath: 'models/metrics.yml',
  capabilities: ['QUERY'],
  attributes: { metricType: 'simple', definitionSummary: '按订单明细聚合' },
  nativeDetails: { data_type: 'NUMBER' },
};

test('published catalog uses the shared workspace and carries opaque identities into analysis', async ({
  page,
}) => {
  await buildFixture(page, { resources: [resource] });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`/dbt/builds/${BUILD_A}/catalog`);
  await page.getByRole('treeitem', { name: /gross_amount/ }).click();
  await expect(page.getByRole('heading', { name: 'gross_amount', exact: true })).toBeVisible();
  await expect(
    page.getByRole('region', { name: '数据类型' }).getByText('NUMBER', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('published-desktop.png'),
    fullPage: true,
  });
  await page.getByRole('tab', { name: 'YAML 源码', exact: true }).click();
  await expect(page.getByRole('region', { name: 'YAML 源码内容' })).toContainText('gross_amount');
  await page.getByRole('button', { name: '开始分析' }).click();
  await expect(page).toHaveURL(/metricResourceId=metric-opaque-id/);
  await expect(page.getByRole('checkbox', { name: resource.displayName })).toBeChecked();
  await expect(page.getByRole('button', { name: '查询', exact: true })).toBeEnabled();
  await page.screenshot({
    animations: 'disabled',
    path: test.info().outputPath('published-analysis.png'),
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
    path: test.info().outputPath('published-settings-bottom.png'),
    fullPage: true,
  });
});
