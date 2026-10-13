import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A, BUILD_B, BUILD_MAIN } from './build-fixture';
test('部署分支筛选隔离目录，复制URL保持构建，未部署不回退main', async ({ page }) => {
  const fixture = await buildFixture(page, {
    resources: (id) => [
      {
        resourceId: 'same',
        name: 'same',
        kind: 'METRIC',
        attributes: {
          definitionSummary: id === BUILD_MAIN ? '分支 main' : id === BUILD_B ? '分支 b' : '分支 a',
        },
      },
    ],
  });
  const environment = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  await page.route('**/api/dbt/deployments?**', async (route) => {
    const branch = new URL(route.request().url()).searchParams.get('branchName');
    const buildId = branch === 'main' ? BUILD_MAIN : branch === 'b' ? BUILD_B : BUILD_A;
    await route.fulfill({
      json: {
        items:
          branch === 'empty'
            ? []
            : [
                {
                  repository: 'repo/a',
                  branchName: branch,
                  environmentId: environment,
                  generation: 1,
                  buildId,
                  deploymentStatus: 'DEPLOYED',
                },
              ],
        nextCursor: null,
      },
    });
  });
  async function choose(branch: string) {
    await page.goto('/dbt/projects');
    await page.getByLabel('Git 仓库地址').fill('repo/a');
    await page.getByLabel('分支', { exact: true }).fill(branch);
    await page.getByLabel('环境 UUID').fill(environment);
    await page.getByRole('button', { name: '查看部署版本' }).click();
    if (branch !== 'empty') await page.getByRole('link', { name: '查看指标目录' }).click();
  }
  await choose('main');
  await expect(page).toHaveURL(new RegExp(BUILD_MAIN));
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 main', { exact: true })).toBeVisible();
  await choose('a');
  await expect(page).toHaveURL(new RegExp(BUILD_A));
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 a', { exact: true })).toBeVisible();
  const copied = page.url();
  await choose('b');
  await expect(page).toHaveURL(new RegExp(BUILD_B));
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 b', { exact: true })).toBeVisible();
  await choose('empty');
  await expect(page.getByRole('link', { name: '查看指标目录' })).toHaveCount(0);
  await expect(page.getByRole('treeitem', { name: /same/ })).toHaveCount(0);
  await page.goto(copied);
  await expect(page).toHaveURL(new RegExp(BUILD_A));
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 a', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '开始分析' }).click();
  await expect(page).toHaveURL(new RegExp(`/builds/${BUILD_A}/query`));
  expect(fixture.requests.every((item) => !item.path.includes('semantic-projects'))).toBe(true);
});
