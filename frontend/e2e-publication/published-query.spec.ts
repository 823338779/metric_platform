import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A, BUILD_B } from './build-fixture';
test('历史查询始终固定原构建，新部署只在显式刷新后切换并清空选择', async ({ page }) => {
  const fixture = await buildFixture(page, {
    resources: (id) => [
      {
        resourceId: 'metric',
        kind: 'METRIC',
        name: 'revenue',
        displayName: id === BUILD_A ? '销售额 A' : '销售额 B',
      },
    ],
    options: [
      {
        optionId: 'opaque-region',
        resourceId: null,
        displayName: '地区',
        granularities: [],
        operators: ['='],
      },
    ],
  });
  await page.goto(`/dbt/builds/${BUILD_A}/query`);
  await page.getByRole('checkbox', { name: '销售额 A' }).click();
  await page
    .getByRole('group', { name: '分组维度', exact: true })
    .getByRole('button', { name: '地区', exact: true })
    .click();
  fixture.advance();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('cell', { name: '62.00' })).toBeVisible();
  const request = fixture.requests.find((item) => item.body?.mode);
  expect(request?.path).toContain(`/builds/${BUILD_A}/queries`);
  expect(request?.body).toMatchObject({
    metrics: ['revenue'],
    groupBy: [{ optionId: 'opaque-region' }],
  });
  expect(request?.body).not.toHaveProperty('buildId');
  await expect(page.getByText(/有效部署已更新/)).toBeVisible({ timeout: 8000 });
  await expect(page).toHaveURL(new RegExp(BUILD_A));
  await page.getByRole('button', { name: '刷新已发布版本' }).click();
  await expect(page).toHaveURL(new RegExp(BUILD_B));
  await expect(page.getByRole('checkbox', { name: '销售额 B' })).not.toBeChecked();
  await expect(page.getByRole('button', { name: '查询', exact: true })).toBeDisabled();
});
