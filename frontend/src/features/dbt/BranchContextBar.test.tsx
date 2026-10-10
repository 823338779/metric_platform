import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { BranchContextBar } from './BranchContextBar';
afterEach(cleanup);
it('显示真实分支、仓库和历史缓存来源，不伪装实时分支', () => {
  render(
    <BranchContextBar
      repository="repo/a"
      projectId="p"
      branchName="feature/shared"
      environment="PREVIEW"
      branches={{
        items: [{ branchName: 'feature/shared', headSha: 'sha' }],
        cached: true,
        source: 'AGENT_CACHE',
        observedAt: '2026-10-10T00:00:00Z',
      }}
      onSelect={vi.fn()}
    />,
  );
  expect(screen.getByText('repo/a')).toBeInTheDocument();
  expect(screen.getByText(/历史缓存/)).toBeInTheDocument();
  expect(screen.getByRole('option', { name: 'feature/shared' })).toBeInTheDocument();
});
