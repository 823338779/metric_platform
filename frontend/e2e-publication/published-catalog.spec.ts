import { expect, test } from '@playwright/test';

const identity = {
  identityId: 'fixture',
  subject: 'fixture',
  displayName: '演示账号',
  authVersion: 1,
};
const VERSION = 'release-a';
const BRANCH = 'production-branch';
const resource = {
  branchId: BRANCH,
  releaseId: VERSION,
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
  await page.addInitScript(
    (user) =>
      sessionStorage.setItem(
        'metric-platform.auth-session',
        JSON.stringify({
          accessToken: 'fixture-token',
          tokenType: 'Bearer',
          expiresIn: 36000,
          identity: user,
        }),
      ),
    identity,
  );
  await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: identity }));
  await page.route('**/api/v2/semantic-projects/**', (route) => {
    const url = new URL(route.request().url());
    // 增量契约 fixture：旧生产链接现解析登记分支，原页面及查询断言保持。
    const branch = {
      projectId: 'ecommerce_metrics',
      branchId: BRANCH,
      gitRef: 'refs/heads/main',
      mode: 'PRODUCTION',
      status: 'ACTIVE',
      activeReleaseId: VERSION,
      latestReleaseId: VERSION,
      observedHeadSha: 'sha',
      version: 1,
    };
    if (url.pathname.endsWith('/branches')) return route.fulfill({ json: [branch] });
    if (url.pathname.endsWith(`/branches/${BRANCH}`)) return route.fulfill({ json: branch });
    if (url.pathname.endsWith(`/releases/${VERSION}`))
      return route.fulfill({
        json: { branchId: BRANCH, releaseId: VERSION, sourceSha: 'sha', state: 'PUBLISHED' },
      });
    if (url.pathname.endsWith('/releases'))
      return route.fulfill({
        json: [{ branchId: BRANCH, releaseId: VERSION, sourceSha: 'sha', state: 'PUBLISHED' }],
      });
    if (url.pathname.endsWith('/catalog'))
      return route.fulfill({
        json: {
          branchId: BRANCH,
          releaseId: VERSION,
          page: 1,
          size: 50,
          total: 1,
          resources: [resource],
        },
      });
    if (url.pathname.endsWith('/lineage'))
      return route.fulfill({
        json: {
          branchId: BRANCH,
          releaseId: VERSION,
          resourceId: resource.resourceId,
          dependencies: [],
        },
      });
    if (url.pathname.endsWith('/source'))
      return route.fulfill({
        json: {
          branchId: BRANCH,
          releaseId: VERSION,
          resourceId: resource.resourceId,
          path: resource.sourcePath,
          content: 'metrics:\n  - name: gross_amount\n    type: simple\n',
        },
      });
    if (url.pathname.endsWith('/query-options'))
      return route.fulfill({ json: { branchId: BRANCH, releaseId: VERSION, options: [] } });
    return route.fulfill({ json: resource });
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`/dbt/projects/ecommerce_metrics/catalog?releaseId=${VERSION}`);
  await page.getByRole('treeitem', { name: /gross_amount/ }).click();
  await expect(page.getByRole('heading', { name: 'gross_amount', exact: true })).toBeVisible();
  await expect(
    page.getByRole('region', { name: '数据类型' }).getByText('NUMBER', { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: '../.impeccable/review/published-desktop.png',
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
    path: '../.impeccable/review/published-analysis.png',
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
    path: '../.impeccable/review/published-settings-bottom.png',
    fullPage: true,
  });
});
