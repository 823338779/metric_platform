import { expect, test } from '@playwright/test';

// 浏览器位于洛杉矶时，查询仍必须按项目确认的北京时间解释输入。
test.use({ timezoneId: 'America/Los_Angeles' });

const RELEASE = 'release-analysis-demo';
const identity = {
  identityId: 'fixture',
  subject: 'fixture',
  displayName: '演示账号',
  authVersion: 1,
};
const metrics = [
  ['metric-total', 'gross_amount', '销售总额', '所有订单明细的折后总额'],
  ['metric-orders', 'order_count', '订单数量', '按订单去重计算的成交数量'],
  ['metric-average', 'average_order_value', '客单价', '每笔成交订单的平均金额'],
  ['metric-units', 'units_sold', '商品销量', '已售商品件数'],
].map(([resourceId, name, displayName, description]) => ({
  resourceId,
  name,
  displayName,
  description,
  kind: 'METRIC',
  capabilities: ['QUERY'],
  attributes: {},
  nativeDetails: {},
}));
const options = [
  { optionId: 'opaque-brand', displayName: '品牌', granularities: [], operators: ['=', 'IN'] },
  { optionId: 'opaque-category', displayName: '商品类别', granularities: [], operators: ['='] },
  { optionId: 'opaque-channel', displayName: '销售渠道', granularities: [], operators: ['='] },
  {
    optionId: 'opaque-time',
    displayName: '下单时间',
    granularities: ['day', 'week', 'month'],
    operators: [],
  },
];

test('builds an analysis with direct choices, fixed identities and no setup dropdowns', async ({
  page,
}) => {
  let submitted: Record<string, unknown> | undefined;
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
  await page.route('**/api/v2/semantic-projects/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/catalog'))
      return route.fulfill({
        json: { releaseId: RELEASE, page: 1, size: 50, total: metrics.length, resources: metrics },
      });
    if (path.endsWith('/query-options'))
      return route.fulfill({ json: { releaseId: RELEASE, options } });
    if (path.endsWith('/queries')) {
      submitted = route.request().postDataJSON();
      return route.fulfill({
        status: 202,
        json: { releaseId: RELEASE, queryId: 'Q-demo', state: 'QUEUED' },
      });
    }
    if (path.endsWith('/queries/Q-demo'))
      return route.fulfill({
        json: {
          releaseId: RELEASE,
          queryId: 'Q-demo',
          state: 'READY',
          columns: [
            { name: 'ordered_at', type: 'DATE' },
            { name: 'brand', type: 'TEXT' },
            { name: 'gross_amount', type: 'DECIMAL' },
          ],
          rows: [
            ['2026-09-01', 'North', '12580.50'],
            ['2026-09-02', 'North', '14325.20'],
            ['2026-09-03', 'North', '11980.00'],
            ['2026-09-04', 'North', '16820.75'],
          ],
        },
      });
    return route.fulfill({ status: 404, json: { status: 404 } });
  });
  await page.setViewportSize({ width: 1440, height: 1050 });
  await page.goto(`/dbt/projects/ecommerce_metrics/query?releaseId=${RELEASE}`);
  await page.getByRole('checkbox', { name: '销售总额' }).click();
  await page.getByRole('textbox', { name: '搜索指标' }).fill('订单');
  await page.getByRole('checkbox', { name: '订单数量' }).click();
  await expect(page.getByRole('button', { name: '移除指标 销售总额' })).toBeVisible();
  await page.getByRole('button', { name: '移除指标 订单数量' }).click();
  await page.getByRole('textbox', { name: '搜索指标' }).fill('');
  await page
    .getByRole('group', { name: '分组维度' })
    .getByRole('button', { name: '品牌', exact: true })
    .click();
  await page
    .getByRole('group', { name: '分组维度' })
    .getByRole('button', { name: '下单时间', exact: true })
    .click();
  await page
    .getByRole('group', { name: '下单时间时间粒度' })
    .getByRole('button', { name: '日', exact: true })
    .click();
  await page.getByRole('button', { name: '近 7 天', exact: true }).click();
  await expect(page.getByLabel('开始时间', { exact: true })).not.toHaveValue('');
  await page.getByLabel('开始时间', { exact: true }).fill('2026-09-01T00:00');
  await page.getByLabel('结束时间', { exact: true }).fill('2026-09-30T23:59');
  await page.getByText('筛选与排序', { exact: false }).click();
  await page
    .getByRole('group', { name: '筛选维度' })
    .getByRole('button', { name: '品牌', exact: true })
    .click();
  await page
    .getByRole('group', { name: '操作符' })
    .getByRole('button', { name: 'IN', exact: true })
    .click();
  await page.getByLabel('筛选值').fill('North,South');
  await page
    .getByRole('group', { name: '排序字段' })
    .getByRole('button', { name: '销售总额' })
    .click();
  await page.getByRole('group', { name: '排序方向' }).getByRole('button', { name: '降序' }).click();
  await expect(page.getByRole('region', { name: '查询设置' }).getByRole('combobox')).toHaveCount(0);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByRole('cell', { name: '12580.50', exact: true })).toBeVisible();
  expect(submitted).toMatchObject({
    releaseId: RELEASE,
    metricResourceIds: ['metric-total'],
    groupBy: [{ optionId: 'opaque-brand' }, { optionId: 'opaque-time', grain: 'day' }],
    filters: [{ optionId: 'opaque-brand', operator: 'IN', value: ['North', 'South'] }],
    orderBy: [{ fieldId: 'metric-total', direction: 'DESC' }],
    idempotencyKey: expect.any(String),
    startTime: '2026-08-31T16:00:00.000Z',
    endTime: '2026-09-30T15:59:00.000Z',
  });
  await page.getByText('筛选与排序', { exact: false }).click();
  await page.evaluate(() => window.scrollTo(0, 0));
  for (const width of [1440, 1264, 390]) {
    await page.setViewportSize({ width, height: 1050 });
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.screenshot({
      animations: 'disabled',
      path: `../.impeccable/review/query-studio-${width}.png`,
      fullPage: true,
    });
  }
});
