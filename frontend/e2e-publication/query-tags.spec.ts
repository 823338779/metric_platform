import { expect, test } from '@playwright/test';
import { buildFixture, BUILD_A } from './build-fixture';

const IDENTITY = {
  identityId: 'demo',
  subject: 'demo',
  displayName: '演示账号',
  authVersion: 1,
  tenant_id: 'tn_00001',
  role: 'TENANT_MANAGER' as const,
};
const OPTIONS = [
  {
    optionId: 'time-option',
    dimensionType: 'time',
    displayName: '下单时间',
    granularities: ['day', 'week', 'month'],
    operators: [],
  },
  {
    optionId: 'region-option',
    dimensionType: 'categorical',
    displayName: '地区',
    granularities: [],
    operators: ['=', 'IN'],
  },
  {
    optionId: 'unknown-option',
    dimensionType: 'categorical',
    displayName: '未声明类型的维度',
    granularities: [],
    operators: [],
  },
];

for (const width of [1440, 390]) {
  test(`query tags preserve types, grain and sort priority at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const fixture = await buildFixture(page, {
      options: OPTIONS,
      resources: [
        {
          resourceId: 'revenue-id',
          kind: 'METRIC',
          name: 'revenue',
          displayName: '销售总额',
          description: '所有订单明细的折后总额',
        },
      ],
      resource: (resourceId) => ({
        resourceId,
        kind: 'DIMENSION',
        name: resourceId,
        attributes: { dataType: resourceId === 'time-resource' ? 'DATE' : 'VARCHAR(64)' },
      }),
      result: () => ({
        columns: [
          { name: '下单时间', type: 'DATE' },
          { name: '地区', type: 'VARCHAR' },
          { name: 'revenue', type: 'DECIMAL' },
        ],
        rows: [
          ['2026-09-01', '华东', '12580.50'],
          ['2026-10-01', '华东', '14325.20'],
        ],
      }),
    });
    await page.goto(`/dbt/builds/${BUILD_A}/query`);
    await page.getByRole('checkbox', { name: '销售总额' }).click();
    await expect(page.getByText('time', { exact: true })).toBeVisible();
    await expect(page.getByText('categorical', { exact: true }).first()).toBeVisible();
    await page
      .getByRole('group', { name: '分组维度', exact: true })
      .getByRole('button', { name: '下单时间', exact: true })
      .click();
    await page.getByRole('button', { name: '下单时间时间粒度' }).click();
    await page.getByRole('menuitem', { name: '月', exact: true }).click();
    await expect(page.getByRole('menu')).not.toBeVisible();
    await expect(page.getByRole('button', { name: '下单时间时间粒度' })).toContainText('月');
    await page.getByRole('button', { name: '添加分组维度' }).click();
    await expect(page.getByRole('textbox', { name: '搜索分组维度' })).toBeFocused();
    const menuBounds = await page.getByRole('region', { name: '选择分组维度' }).boundingBox();
    expect(menuBounds).not.toBeNull();
    expect(menuBounds!.x).toBeGreaterThanOrEqual(0);
    expect(menuBounds!.x + menuBounds!.width).toBeLessThanOrEqual(width);
    await page.screenshot({
      path: test.info().outputPath(`dimension-menu-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    });
    await page.getByRole('textbox', { name: '搜索分组维度' }).fill('地区');
    await page
      .getByRole('region', { name: '选择分组维度' })
      .getByRole('button', { name: '地区', exact: true })
      .click();
    for (const label of ['下单时间', '销售总额']) {
      await page.getByRole('button', { name: '添加排序字段' }).click();
      await page
        .getByRole('region', { name: '选择排序字段' })
        .getByRole('button', { name: label, exact: true })
        .click();
    }
    await page.getByRole('button', { name: '销售总额排序方向：升序' }).click();
    await page.getByRole('button', { name: '添加排序字段' }).click();
    await page.getByRole('textbox', { name: '搜索排序字段' }).press('Escape');
    await expect(page.getByRole('button', { name: '添加排序字段' })).toBeFocused();
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await expect(page.getByRole('cell', { name: '12580.50' })).toBeVisible();
    expect(fixture.requests.find((item) => item.body?.mode)?.body).toMatchObject({
      metrics: ['revenue'],
      groupBy: [{ optionId: 'time-option', grain: 'month' }, { optionId: 'region-option' }],
      orderBy: [
        { fieldId: 'time-option', direction: 'ASC' },
        { fieldId: 'revenue', direction: 'DESC' },
      ],
    });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: test.info().outputPath(`query-tags-${width}.png`),
      fullPage: true,
      animations: 'disabled',
    });
  });
}
