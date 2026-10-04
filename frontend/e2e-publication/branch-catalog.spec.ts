import { expect, test } from '@playwright/test';
import { branchFixture } from './branch-fixture';

test('线上与两个分支同名资源隔离，失败 head 保留旧版本且复制 URL 固定身份', async ({ page }) => {
  await branchFixture(page);
  await page.goto('/dbt/projects/p/catalog');
  await expect(page).toHaveURL(/branchId=main/);
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 main', { exact: true })).toBeVisible();
  await page.getByLabel('项目分支').selectOption('a');
  await expect(page).toHaveURL(/releaseId=r-a/);
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 a', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '探索数据' })).toHaveAttribute('href', /branchId=a/);
  const copied = page.url();
  await page.getByLabel('项目分支').selectOption('b');
  await page.getByRole('treeitem', { name: /same/ }).click();
  await expect(page.getByText('分支 b', { exact: true })).toBeVisible();
  await expect(page.getByText('available-b', { exact: true }).first()).toBeVisible();
  await expect(page.getByText(/最新构建 FAILED/)).toBeVisible();
  await expect(page.getByText('test_b：FAILED')).toBeVisible();
  await page.goto(copied);
  await expect(page.getByLabel('项目分支')).toHaveValue('a');
  await page.getByRole('button', { name: '开始分析' }).click();
  await expect(page).toHaveURL(/branchId=a/);
});
