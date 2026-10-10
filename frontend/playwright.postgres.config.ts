import { defineConfig, devices } from '@playwright/test';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOST = '127.0.0.1';
const PORT = Number(process.env.DBT_POSTGRES_E2E_FRONTEND_PORT ?? 4175);
const BASE_URL = `http://${HOST}:${PORT}`;
const PLATFORM_URL = process.env.DBT_POSTGRES_E2E_PLATFORM_URL;
if (!PLATFORM_URL || process.env.DBT_POSTGRES_E2E !== '1')
  throw new Error('真实浏览器验证需要 DBT_POSTGRES_E2E=1 和 DBT_POSTGRES_E2E_PLATFORM_URL');

// 每轮独占前端服务器；真实认证请求不得进入 Playwright trace 或视频。
export default defineConfig({
  testDir: './e2e',
  testMatch: 'dbt-postgres-integration.spec.ts',
  workers: 1,
  retries: 0,
  outputDir:
    process.env.DBT_POSTGRES_E2E_OUTPUT_DIR ??
    mkdtempSync(join(tmpdir(), 'platform-postgres-e2e-')),
  use: { baseURL: BASE_URL, trace: 'off', video: 'off', screenshot: 'off' },
  webServer: {
    command: `npm run dev -- --host ${HOST} --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    env: { VITE_API_PROXY_TARGET: PLATFORM_URL },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
});
