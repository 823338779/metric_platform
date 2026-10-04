import '@testing-library/jest-dom/vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { BranchChangesPanel } from './BranchChangesPanel';
import type { BranchDiff, PublishedCatalogApi } from '../../api/publishedCatalogApi';
afterEach(cleanup);
const diff = (branchId: string): BranchDiff => ({
  branchId,
  releaseId: 'r',
  baseCommitSha: 'base',
  targetCommitSha: branchId,
  productionAdvanced: true,
  resourceBaselineAvailable: false,
  truncated: true,
  files: [{ path: branchId + '.sql', status: 'M', diff: '+select 1' }],
  resources: [],
});
it('切换分支后忽略旧差异，明确标注截断与基线缺失', async () => {
  let finish!: (value: BranchDiff) => void;
  const api = {
    diff: vi
      .fn()
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve;
        }),
      )
      .mockResolvedValue(diff('b')),
  } as unknown as PublishedCatalogApi;
  const view = render(
    <BranchChangesPanel api={api} context={{ projectId: 'p', branchId: 'a', releaseId: 'r' }} />,
  );
  view.rerender(
    <BranchChangesPanel api={api} context={{ projectId: 'p', branchId: 'b', releaseId: 'r' }} />,
  );
  await screen.findByText(/b.sql/);
  await act(async () => finish(diff('a')));
  expect(screen.queryByText(/a.sql/)).not.toBeInTheDocument();
  expect(screen.getByText(/已截断/)).toBeInTheDocument();
  expect(screen.getByText(/资源基线不可用/)).toBeInTheDocument();
});
