import { expect, test } from '@playwright/test';

test.skip(process.env.DBT_POSTGRES_E2E !== '1', '需启动 PostgreSQL profile 的平台与运行服务');
test.use({ actionTimeout: 10_000 });
test.setTimeout(240_000);

test('真实 PostgreSQL 发布目录、维度值和月粒度查询', async ({ page }) => {
  const username = process.env.DBT_POSTGRES_E2E_USER;
  const password = process.env.DBT_POSTGRES_E2E_PASSWORD;
  const projectId = process.env.DBT_POSTGRES_E2E_PROJECT ?? 'postgres_platform';
  if (!username || !password) throw new Error('缺少 DBT_POSTGRES_E2E_USER/PASSWORD');

  await page.goto('/login');
  await page.getByRole('textbox', { name: '账号' }).fill(username);
  await page.getByLabel('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  const row = page.getByRole('row').filter({ hasText: projectId });
  await expect(row.getByRole('link', { name: '查看目录' })).toBeVisible({ timeout: 180_000 });
  await row.getByRole('link', { name: '查看目录' }).click();
  await expect(page.getByText('dbt 只读目录')).toBeVisible();
  await expect(page.getByRole('cell', { name: 'revenue' })).toBeVisible();
  await page.getByText('revenue', { exact: true }).click();
  await expect(page.getByRole('tab', { name: '结构化详情' })).toBeVisible();
  await page.getByRole('tab', { name: 'YAML 源码' }).click();
  await expect(page.getByText(/来源提交：/)).toBeVisible();
  await page.getByRole('link', { name: '固定版本查询' }).first().click();
  await expect(page.getByRole('button', { name: '运行查询' })).toBeEnabled({ timeout: 30_000 });
  await page.getByRole('combobox', { name: '日期粒度' }).click();
  await page.getByText('metric_time · month').click();
  await page.getByRole('button', { name: '运行查询' }).click();
  await expect(page.getByText('状态：COMPLETED')).toBeVisible({ timeout: 120_000 });
  await expect(page.getByRole('img', { name: '时间序列图' })).toBeVisible();
  await page.getByRole('combobox', { name: '筛选维度' }).click();
  await page
    .getByText(/region/i)
    .last()
    .click();
  await page.getByRole('button', { name: '查询维度值' }).click();
  await expect(page.getByText('选择筛选值')).toBeVisible({ timeout: 120_000 });
});
