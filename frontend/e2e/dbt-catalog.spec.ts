import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A } from '../e2e-publication/build-fixture';
test('目录分页、固定构建查询、时间序列与SQL解释', async ({ page }) => {
  const sql = 'select sum(amount) as revenue from orders';
  const fixture = await buildFixture(page, {
    pageSize: 1,
    resources: [
      { resourceId: 'metric-revenue', kind: 'METRIC', name: 'revenue' },
      { resourceId: 'metric-count', kind: 'METRIC', name: 'order_count' },
    ],
    options: [
      {
        optionId: 'region',
        resourceId: null,
        displayName: 'Region',
        granularities: [],
        operators: ['='],
      },
      {
        optionId: 'time',
        resourceId: null,
        displayName: '日期',
        granularities: ['month'],
        operators: [],
      },
    ],
    result: (body) =>
      body.mode === 'EXPLAIN'
        ? { sql, columns: [], rows: [] }
        : {
            columns: body.groupBy.length
              ? [
                  { name: 'metric_time__month', type: 'DATE' },
                  { name: 'region', type: 'TEXT' },
                  { name: 'revenue', type: 'DECIMAL' },
                ]
              : [{ name: 'revenue', type: 'DECIMAL' }],
            rows: body.groupBy.length
              ? [
                  ['2026-09-01', 'North', '123.45'],
                  ['2026-09-01', 'South', '100.00'],
                ]
              : [['123.45']],
          },
  });
  await page.goto(`/dbt/builds/${BUILD_A}/catalog`);
  await page.getByRole('button', { name: '加载更多资源' }).click();
  await expect(page.getByRole('treeitem', { name: /order_count/ })).toBeVisible();
  await page.getByRole('treeitem', { name: /revenue/ }).click();
  await page.getByRole('button', { name: '开始分析' }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('cell', { name: '123.45', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '指标数值' })).toBeVisible();
  const groups = page.getByRole('group', { name: '分组维度', exact: true });
  await groups.getByRole('button', { name: 'Region', exact: true }).click();
  await page.getByRole('button', { name: '添加分组维度' }).click();
  await page
    .getByRole('region', { name: '选择分组维度' })
    .getByRole('button', { name: '日期', exact: true })
    .click();
  await page.getByRole('button', { name: '日期时间粒度' }).click();
  await page.getByRole('menuitem', { name: '月', exact: true }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('img', { name: '时间序列图' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'North', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'South', exact: true })).toBeVisible();
  expect(
    fixture.requests.filter((item) => item.body?.mode === 'QUERY').at(-1)?.body?.groupBy,
  ).toEqual([{ optionId: 'region' }, { optionId: 'time', grain: 'month' }]);
  await page.getByRole('radio', { name: '仅明细' }).check();
  await expect(page.getByRole('img', { name: '时间序列图' })).toHaveCount(0);
  await page.getByRole('button', { name: /查看 SQL/ }).click();
  await expect(page.getByLabel('生成的 SQL')).toHaveText(sql);
  await expect(page.getByRole('table')).toHaveCount(0);
  await page.goto('/metrics');
  await expect(page).toHaveURL(/\/dbt\/projects$/);
});
