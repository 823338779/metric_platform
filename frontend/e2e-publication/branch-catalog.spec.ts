import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A, BUILD_B, BUILD_MAIN } from './build-fixture';
test('固定构建隔离分支目录，复制URL保持构建', async ({ page }) => {
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
  async function choose(branch: string) {
    const buildId = branch === 'main' ? BUILD_MAIN : branch === 'b' ? BUILD_B : BUILD_A;
    await page.goto(`/dbt/builds/${buildId}/catalog`);
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
  await page.goto(copied);
  await expect(page).toHaveURL(new RegExp(BUILD_A));
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 a', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '开始分析' }).click();
  await expect(page).toHaveURL(new RegExp(`/builds/${BUILD_A}/query`));
  expect(fixture.requests.every((item) => !item.path.includes('semantic-projects'))).toBe(true);
});
