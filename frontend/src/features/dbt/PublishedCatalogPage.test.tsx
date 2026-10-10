import '@testing-library/jest-dom/vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { PublishedCatalogPage } from './PublishedCatalogPage';
vi.mock('./DbtCatalogPage', () => ({ DbtCatalogPage: () => <p>目录已加载</p> }));
afterEach(cleanup);
it('固定构建链接读取历史，与Agent分支登记无关', async () => {
  const B = '11111111-1111-4111-8111-111111111111';
  const api = {
    build: vi.fn().mockResolvedValue({
      buildId: B,
      repository: 'repo',
      branchName: 'feature/a',
      environment: 'PREVIEW',
      catalogAvailable: true,
      queryAvailable: false,
    }),
    current: vi.fn().mockRejectedValue(new Error('offline')),
    builds: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    deployments: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    branches: vi.fn(),
  };
  render(
    <MemoryRouter initialEntries={[`/dbt/builds/${B}/catalog`]}>
      <Routes>
        <Route
          path="/dbt/builds/:buildId/catalog"
          element={<PublishedCatalogPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByText('目录已加载')).toBeInTheDocument();
  await waitFor(() => expect(api.build).toHaveBeenCalledWith(B));
  expect(api.branches).not.toHaveBeenCalled();
});
