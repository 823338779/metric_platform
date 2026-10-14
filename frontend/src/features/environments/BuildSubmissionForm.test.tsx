import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { BuildSubmissionForm } from './BuildSubmissionForm';

afterEach(cleanup);
const repository = 'http://127.0.0.1:3000/agent-admin/metrics.git';
const environment = { environmentId: 'environment', configVersion: 3 };

it('展示服务器绑定的仓库，仅允许选择实际分支并提交', async () => {
  const api = {
    repositoryBranches: vi
      .fn()
      .mockResolvedValue({ repository, branches: ['feature/report', 'main'] }),
    submitBuild: vi.fn().mockResolvedValue({}),
  };
  render(
    <BuildSubmissionForm
      environment={environment as never}
      api={api as never}
      onSubmitted={vi.fn()}
    />,
  );
  expect(screen.getByRole('button', { name: '发起构建' })).toBeDisabled();
  await screen.findByRole('option', { name: 'feature/report' });
  expect(screen.getByLabelText('Git 仓库地址')).toHaveValue(repository);
  expect(screen.getByLabelText('Git 仓库地址')).toHaveAttribute('readonly');
  fireEvent.change(screen.getByRole('combobox', { name: '构建分支' }), {
    target: { value: 'feature/report' },
  });
  fireEvent.click(screen.getByRole('button', { name: '发起构建' }));
  await waitFor(() =>
    expect(api.submitBuild).toHaveBeenCalledWith(
      'environment',
      expect.objectContaining({
        repository,
        branchName: 'feature/report',
        expectedConfigVersion: 3,
      }),
    ),
  );
});

it('读取失败时禁止构建，支持重新加载分支', async () => {
  const api = {
    repositoryBranches: vi
      .fn()
      .mockRejectedValueOnce(new Error('分支读取失败'))
      .mockResolvedValue({ repository, branches: ['main'] }),
  };
  render(
    <BuildSubmissionForm
      environment={environment as never}
      api={api as never}
      onSubmitted={vi.fn()}
    />,
  );
  await screen.findByText('分支读取失败');
  expect(screen.getByRole('button', { name: '发起构建' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '刷新分支' }));
  await screen.findByRole('option', { name: 'main' });
  expect(screen.queryByText('分支读取失败')).not.toBeInTheDocument();
});

it('空仓库不允许发起构建', async () => {
  const api = { repositoryBranches: vi.fn().mockResolvedValue({ repository, branches: [] }) };
  render(
    <BuildSubmissionForm
      environment={environment as never}
      api={api as never}
      onSubmitted={vi.fn()}
    />,
  );
  await screen.findByText('仓库暂无可用分支');
  expect(screen.getByRole('button', { name: '发起构建' })).toBeDisabled();
});
