import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { BranchBuildPanel } from './BranchBuildPanel';
import type { Publication } from '../../api/publishedCatalogApi';
afterEach(cleanup);
it('旧版仍可用时摘要持续显示最新失败及无检查明细的错误原因', () => {
  const releases = [
    { releaseId: 'old', state: 'PUBLISHED', sourceSha: 'old-sha' },
    {
      releaseId: 'failed',
      state: 'FAILED',
      sourceSha: 'new-sha',
      errorCode: 'DBT_TEST_FAILED',
      validationSummary: { phase: 'TEST', checks: [], truncated: false },
    },
  ] as Publication[];
  const { container } = render(
    <BranchBuildPanel
      releases={releases}
      currentReleaseId="old"
      activeReleaseId="old"
      latestReleaseId="failed"
    />,
  );
  expect(container.querySelector('summary')).toHaveTextContent('最新构建 FAILED');
  expect(container.querySelector('details')).toHaveAttribute('open');
  expect(screen.getByText(/DBT_TEST_FAILED/)).toBeInTheDocument();
  expect(screen.getByText('执行阶段：TEST')).toBeInTheDocument();
});

it('最新构建失败与旧可用版本分别展示', () => {
  const release = {
    releaseId: 'failed',
    sourceSha: 'bad-sha',
    state: 'FAILED',
    validationSummary: { checks: [{ name: 'test_orders', status: 'FAILED' }], truncated: true },
  } as Publication;
  render(
    <BranchBuildPanel releases={[release]} currentReleaseId="failed" activeReleaseId="old-good" />,
  );
  expect(screen.getByText(/bad-sha/)).toBeInTheDocument();
  expect(screen.getByText(/old-good/)).toBeInTheDocument();
  expect(screen.getByText(/test_orders/)).toBeInTheDocument();
  expect(screen.getByText(/已截断/)).toBeInTheDocument();
});
