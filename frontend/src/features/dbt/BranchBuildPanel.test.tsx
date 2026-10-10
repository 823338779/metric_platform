import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { BranchBuildPanel } from './BranchBuildPanel';
import type { BuildView } from '../../api/buildCatalogApi';
afterEach(cleanup);
it('最新构建失败不替换旧有效部署，保留诊断和投影时间', () => {
  const items = [
    {
      buildId: '11111111-1111-4111-8111-111111111111',
      buildStatus: 'SUCCEEDED',
      catalogAvailable: true,
    },
    {
      buildId: '22222222-2222-4222-8222-222222222222',
      buildStatus: 'FAILED',
      error: { code: 'COMPILE_FAILED', message: 'bad sql' },
      catalogAvailable: false,
    },
  ] as BuildView[];
  render(
    <BranchBuildPanel
      history={{ items, nextCursor: null, source: 'SERVICE_CHANGE_FEED', syncedAt: '2026-10-10' }}
      deployments={[]}
      currentBuildId={items[0].buildId}
      activeBuildId={items[0].buildId}
      onSelect={vi.fn()}
    />,
  );
  expect(screen.getByText('当前有效部署')).toBeInTheDocument();
  expect(screen.getByText('FAILED')).toBeInTheDocument();
  expect(screen.getByText(/COMPILE_FAILED/)).toBeInTheDocument();
});
