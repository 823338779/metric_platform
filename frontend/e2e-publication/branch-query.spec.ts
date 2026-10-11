import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A, BUILD_B } from './build-fixture';
test('不同分支结果独立，丢失响应后固定原构建重新执行', async ({ page }) => {
  const fixture = await buildFixture(page);
  await page.goto(`/dbt/builds/${BUILD_A}/query`);
  await page.getByRole('checkbox', { name: '销售额 a' }).click();
  fixture.loseNextReceipt();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText(/读取或查询失败/)).toBeVisible();
  fixture.advance();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('cell', { name: '62.00' })).toBeVisible();
  const posts = fixture.requests.filter((item) => item.path.endsWith('/queries'));
  expect(posts[0].body).toEqual(posts[1].body);
  expect(posts[1].body).not.toHaveProperty('idempotencyKey');
  await page.goto(`/dbt/builds/${BUILD_B}/query`);
  await page.getByRole('checkbox', { name: '销售额 b' }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('cell', { name: '31.00' })).toBeVisible();
  expect(
    fixture.requests
      .filter((item) => item.path.endsWith('/queries'))
      .every((item) => /\/builds\/[0-9a-f-]+\/queries$/.test(item.path)),
  ).toBe(true);
});
