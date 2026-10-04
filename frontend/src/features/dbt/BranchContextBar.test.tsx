import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { BranchContextBar } from './BranchContextBar';
import type { BranchView } from '../../api/publishedCatalogApi';
afterEach(cleanup);

export const branchFixture: BranchView = {
  projectId: 'p',
  branchId: 'a',
  gitRef: 'refs/heads/dev',
  mode: 'PREVIEW',
  status: 'ACTIVE',
  baseCommitSha: 'base-sha',
  baseReleaseId: null,
  observedHeadSha: 'latest-sha',
  activeReleaseId: 'old-release',
  latestReleaseId: 'new-release',
  version: 1,
  mergeRequestUrl: null,
};
it('显示分支身份和两个 SHA，切换只能选择登记实例', () => {
  const onSelect = vi.fn();
  render(
    <BranchContextBar
      branches={[branchFixture, { ...branchFixture, branchId: 'b', gitRef: 'refs/heads/other' }]}
      branch={branchFixture}
      availableSha="available-sha"
      onSelect={onSelect}
    />,
  );
  expect(screen.getByText(/available-sha/)).toBeInTheDocument();
  expect(screen.getByText(/latest-sha/)).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('项目分支'), { target: { value: 'b' } });
  expect(onSelect).toHaveBeenCalledWith('b');
});
