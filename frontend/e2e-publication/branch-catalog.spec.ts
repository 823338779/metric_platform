import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A, BUILD_B, BUILD_MAIN } from './build-fixture';
test('真实分支选择隔离目录，复制URL保持构建，未部署不回退main', async ({ page }) => {
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
  await page.goto('/dbt/projects/p/catalog?repository=repo%2Fa');
  await expect(page.getByText('请选择项目分支')).toBeVisible();
  await expect(page.getByRole('treeitem', { name: /same/ })).toHaveCount(0);
  await page.getByLabel('项目分支').selectOption('main');
  await expect(page).toHaveURL(new RegExp(BUILD_MAIN));
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 main', { exact: true })).toBeVisible();
  await page.getByLabel('项目分支').selectOption('a');
  await expect(page).toHaveURL(new RegExp(BUILD_A));
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 a', { exact: true })).toBeVisible();
  const copied = page.url();
  await page.getByLabel('项目分支').selectOption('b');
  await expect(page).toHaveURL(new RegExp(BUILD_B));
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 b', { exact: true })).toBeVisible();
  await page.getByLabel('项目分支').selectOption('empty');
  await expect(page.getByText('当前分支尚未部署')).toBeVisible();
  await expect(page.getByRole('treeitem', { name: /same/ })).toHaveCount(0);
  await page.goto(copied);
  await expect(page.getByLabel('项目分支')).toHaveValue('a');
  await page.getByRole('button', { name: '开始分析' }).click();
  await expect(page).toHaveURL(new RegExp(`/builds/${BUILD_A}/query`));
  expect(fixture.requests.every((item) => !item.path.includes('semantic-projects'))).toBe(true);
});
