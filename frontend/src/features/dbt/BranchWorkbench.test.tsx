import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { PublishedWorkbench } from './PublishedWorkbench';
afterEach(cleanup);
it('分享链接持续显示固定分支环境与源码身份，元数据失败不改目标', async () => {
  const scoped = {
    catalog: vi.fn().mockResolvedValue({ releaseId: 'ra', total: 0, resources: [] }),
    release: vi.fn().mockResolvedValue({ releaseId: 'ra', sourceSha: 'fixed-sha' }),
  };
  const api = {
    inBranch: vi.fn(() => scoped),
    branch: vi.fn().mockResolvedValue({
      branchId: 'a',
      projectId: 'p',
      mode: 'PREVIEW',
      gitRef: 'refs/heads/dev/a',
    }),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/p/query?branchId=a&releaseId=ra']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/query"
          element={<PublishedWorkbench api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  const identity = await screen.findByRole('region', { name: '查询上下文' });
  await waitFor(() => expect(identity).toHaveTextContent('refs/heads/dev/a'));
  expect(identity).toHaveTextContent('开发预览');
  expect(identity).toHaveTextContent('fixed-sha');
  expect(identity).toHaveTextContent('ra');
});

function Navigation() {
  const navigate = useNavigate();
  return (
    <button onClick={() => navigate('/dbt/projects/p/query?branchId=b&releaseId=rb')}>
      切换查询分支
    </button>
  );
}
it('运行中的 A 查询不会绘制到 B，同名指标重新读取 B 的选项', async () => {
  let finish!: (value: unknown) => void;
  const a = {
    catalog: vi.fn().mockResolvedValue({
      releaseId: 'ra',
      total: 1,
      resources: [{ resourceId: 'same', kind: 'METRIC', displayName: 'A 指标' }],
    }),
    options: vi.fn().mockResolvedValue({ releaseId: 'ra', options: [] }),
    submit: vi.fn().mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    ),
  };
  const b = {
    catalog: vi.fn().mockResolvedValue({
      releaseId: 'rb',
      total: 1,
      resources: [{ resourceId: 'same', kind: 'METRIC', displayName: 'B 指标' }],
    }),
    options: vi.fn().mockResolvedValue({ releaseId: 'rb', options: [] }),
    submit: vi.fn(),
  };
  const api = {
    inBranch: vi.fn((context) => (context.branchId === 'a' ? a : b)),
    catalog: vi.fn().mockRejectedValue(new Error('禁止生产回退')),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/p/query?branchId=a&releaseId=ra']}>
      <Navigation />
      <Routes>
        <Route
          path="/dbt/projects/:projectId/query"
          element={<PublishedWorkbench api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('checkbox', { name: 'A 指标' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  fireEvent.click(screen.getByRole('button', { name: '切换查询分支' }));
  fireEvent.click(await screen.findByRole('checkbox', { name: 'B 指标' }));
  await waitFor(() => expect(b.options).toHaveBeenCalledWith('p', 'rb', ['same']));
  await act(async () =>
    finish({ queryId: 'old-query', releaseId: 'ra', state: 'READY', columns: [], rows: [] }),
  );
  expect(screen.queryByText(/old-query/)).not.toBeInTheDocument();
  expect(api.catalog).not.toHaveBeenCalled();
});
