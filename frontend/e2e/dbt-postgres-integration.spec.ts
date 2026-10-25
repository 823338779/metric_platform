import { expect, test } from '@playwright/test';

const QUERY = 'QUERY';
const DIMENSION_VALUES = 'DIMENSION_VALUES';
const MONTH = 'month';
const METRIC = 'revenue';
const RESULT_TIMEOUT = 120_000;
test.skip(
  process.env.DBT_POSTGRES_E2E !== '1',
  '需提供真实 PostgreSQL 服务和已成功构建的 DBT_POSTGRES_E2E_BUILD_ID',
);
// 真实身份凭据不得被失败 trace 记录。
test.use({ actionTimeout: 10_000, trace: 'off', video: 'off', screenshot: 'off' });
test.setTimeout(240_000);
test('真实 PostgreSQL 固定构建目录、维度值和月粒度查询', async ({ page }) => {
  const username = process.env.DBT_POSTGRES_E2E_USER;
  const password = process.env.DBT_POSTGRES_E2E_PASSWORD;
  const buildId = process.env.DBT_POSTGRES_E2E_BUILD_ID;
  if (!username || !password || !buildId)
    throw new Error('缺少 DBT_POSTGRES_E2E_USER/PASSWORD/BUILD_ID');
  const catalogPath = `/dbt/builds/${buildId}/catalog?kind=METRIC`;
  await page.goto(`/login?returnTo=${encodeURIComponent(catalogPath)}`);
  await page.getByRole('textbox', { name: '账号' }).fill(username);
  await page.getByLabel('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await page.waitForURL((url) => url.pathname === `/dbt/builds/${buildId}/catalog`, {
    timeout: 10_000,
  });
  await page.getByRole('treeitem', { name: /revenue/ }).click();
  await page.getByRole('tab', { name: 'YAML 源码' }).click();
  await expect(page.getByRole('region', { name: 'YAML 源码内容' })).toContainText('revenue');
  // 使用服务实际返回的 opaque optionId 和显示名选择月粒度，避免猜测维度名称。
  const availableDimensionsResponse = page.waitForResponse(
    async (response) =>
      response.url().includes('/available-dimensions') &&
      response.ok() &&
      Array.isArray((await response.json()).dimensions),
    { timeout: RESULT_TIMEOUT },
  );
  await page.getByRole('button', { name: '开始分析' }).click();
  const availableDimensions = await (await availableDimensionsResponse).json();
  expect(availableDimensions.buildId).toBe(buildId);
  const timeOption = availableDimensions.dimensions.find((option: { granularities: string[] }) =>
    option.granularities.includes(MONTH),
  );
  expect(timeOption).toBeTruthy();
  await page
    .getByRole('group', { name: '分组维度', exact: true })
    .getByRole('button', { name: timeOption.displayName, exact: true })
    .click();
  const grain = page.getByRole('button', { name: /时间粒度/ });
  await grain.click();
  await page.getByRole('menuitem', { name: '月', exact: true }).click();
  // 每次按本次 POST 响应观察结果，不能把前次 COMPLETED 当作本次成功。
  const execute = async (mode: string, label: string) => {
    const acceptedResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === `/api/dbt/builds/${buildId}/queries` &&
        response.request().postDataJSON().mode === mode,
      { timeout: RESULT_TIMEOUT },
    );
    await page.getByRole('button', { name: label, exact: true }).click();
    const response = await acceptedResponse;
    expect(response.status()).toBe(200);
    const result = await response.json();
    expect(result.mode).toBe(mode);
    expect(result).not.toHaveProperty('queryId');
    expect(result).not.toHaveProperty('state');
    expect(result.buildId).toBe(buildId);
    const request = response.request().postDataJSON();
    expect(request.metrics).toEqual([METRIC]);
    if (mode === DIMENSION_VALUES) {
      expect(request.dimensionOptionId).toBe(timeOption.optionId);
      expect(request).not.toHaveProperty('groupBy');
      expect(request).not.toHaveProperty('orderBy');
    } else expect(request.groupBy).toEqual([{ optionId: timeOption.optionId, grain: MONTH }]);
    expect(request).not.toHaveProperty('idempotencyKey');
    expect(result.rows.length).toBeGreaterThan(0);
    await expect(page.getByText(/COMPLETED/)).toBeVisible();
    return result;
  };
  const result = await execute(QUERY, '查询');
  const metricIndex = result.columns.findIndex(
    (column: { name: string }) => column.name === METRIC,
  );
  expect(metricIndex).toBeGreaterThanOrEqual(0);
  expect(
    result.rows
      .map((row: unknown[]) => Number(row[metricIndex]))
      .sort((a: number, b: number) => a - b),
  ).toEqual([10.25, 20.75]);
  await expect(page.getByRole('img', { name: '时间序列图' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '10.25', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: '20.75', exact: true })).toBeVisible();
  const dimensions = await execute(DIMENSION_VALUES, '维度值');
  expect(dimensions.mode).toBe(DIMENSION_VALUES);
  expect(dimensions.rows.flat().some((value: unknown) => String(value).startsWith('2024-01'))).toBe(
    true,
  );
  expect(dimensions.rows.flat().some((value: unknown) => String(value).startsWith('2024-02'))).toBe(
    true,
  );
});
