import { expect, test } from '@playwright/test';
import { branchFixture } from './branch-fixture';

test('不同分支结果独立，丢失受理响应后原键恢复相同查询', async ({ page }) => {
  const fixture = await branchFixture(page);
  await page.goto('/dbt/projects/p/query?branchId=a&releaseId=r-a');
  await page.getByRole('checkbox', { name: '销售额 a' }).click();
  fixture.loseNextReceipt();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText(/读取或查询失败/)).toBeVisible();
  fixture.advance('a');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('cell', { name: '62.00' })).toBeVisible();
  const posts = fixture.requests.filter((item) => item.path.endsWith('/queries'));
  expect(posts[0].body).toEqual(posts[1].body);
  expect(fixture.queries.size).toBe(1);
  await page.goto('/dbt/projects/p/query?branchId=b&releaseId=r-b');
  await page.getByRole('checkbox', { name: '销售额 b' }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('cell', { name: '31.00' })).toBeVisible();
  expect(
    fixture.requests
      .filter((item) => item.path.endsWith('/queries'))
      .every((item) => item.path.includes('/branches/')),
  ).toBe(true);
});
