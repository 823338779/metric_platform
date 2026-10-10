import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { PublishedWorkbench } from './PublishedWorkbench';
afterEach(cleanup);
const A = '11111111-1111-4111-8111-111111111111',
  B = '22222222-2222-4222-8222-222222222222';
function Navigation() {
  const navigate = useNavigate();
  return <button onClick={() => navigate(`/dbt/builds/${B}/query`)}>切换查询分支</button>;
}
it('运行中的A查询不绘制到B，同名指标重新读取固定构建选项', async () => {
  let finish!: (value: unknown) => void;
  const api = {
    build: vi.fn(async (id: string) => ({
      buildId: id,
      repository: 'repo',
      branchName: 'feature/a',
      environment: 'PREVIEW',
      queryAvailable: true,
    })),
    current: vi.fn().mockResolvedValue({ activeBuildId: A }),
    catalog: vi.fn(async (id: string) => ({
      buildId: id,
      items: [{ resourceId: 'same', displayName: id === A ? 'A指标' : 'B指标' }],
      nextCursor: null,
    })),
    options: vi.fn(async (id: string) => ({ buildId: id, state: 'SUCCEEDED', options: [] })),
    submit: vi.fn(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    ),
  };
  render(
    <MemoryRouter initialEntries={[`/dbt/builds/${A}/query`]}>
      <Navigation />
      <Routes>
        <Route
          path="/dbt/builds/:buildId/query"
          element={<PublishedWorkbench api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByRole('checkbox', { name: 'A指标' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  fireEvent.click(screen.getByRole('button', { name: '切换查询分支' }));
  fireEvent.click(await screen.findByRole('checkbox', { name: 'B指标' }));
  await waitFor(() =>
    expect(api.options).toHaveBeenCalledWith(
      B,
      expect.objectContaining({ metricResourceIds: ['same'] }),
    ),
  );
  await act(async () =>
    finish({ queryId: 'old-query', buildId: A, state: 'SUCCEEDED', columns: [], rows: [] }),
  );
  expect(screen.queryByText(/old-query/)).not.toBeInTheDocument();
});
