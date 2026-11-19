import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { AssetGrantPanel, MyAssets } from './AssetAccess';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
const asset = {
  assetId: 'm',
  nodeId: 'metric.pkg.sales',
  kind: 'METRIC',
  name: 'sales',
  dimensionName: '',
  target: { repository: 'repo', branchName: 'main', environmentId: 'env' },
  buildId: 'b',
  granted: false,
};
it('grants an individual asset to the selected reader and can revoke it', async () => {
  let granted = false;
  const fetcher = vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/accounts')) return Response.json([{ id: 'u', username: 'reader' }]);
    if (url.endsWith('/assets')) return Response.json([{ ...asset, granted }]);
    granted = init.method === 'PUT';
    return new Response(null, { status: 204 });
  });
  vi.stubGlobal('fetch', fetcher);
  render(<AssetGrantPanel />);
  fireEvent.click(screen.getByRole('button', { name: '管理资产授权' }));
  await screen.findByRole('option', { name: 'reader' });
  fireEvent.change(screen.getByLabelText('授权账号'), { target: { value: 'u' } });
  fireEvent.click(await screen.findByRole('button', { name: '授予' }));
  fireEvent.click(await screen.findByRole('button', { name: '撤销' }));
  expect(await screen.findByRole('button', { name: '授予' })).toBeTruthy();
  expect(granted).toBe(false);
});
it('links granted metrics to the fixed build query', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => Response.json([{ ...asset, granted: true }])),
  );
  render(
    <MemoryRouter>
      <MyAssets />
    </MemoryRouter>,
  );
  expect((await screen.findByRole('link', { name: '查询汇总' })).getAttribute('href')).toBe(
    '/dbt/builds/b/query?metricResourceId=metric.pkg.sales',
  );
});

it('lets the manager revoke a grant while its asset is absent', async () => {
  let revoked = false;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/accounts')) return Response.json([{ id: 'u', username: 'reader' }]);
      if (url.endsWith('/assets'))
        return Response.json(revoked ? [] : [{ ...asset, buildId: null, granted: true }]);
      revoked = url.endsWith('/u/assets/m') && init.method === 'DELETE';
      return new Response(null, { status: 204 });
    }),
  );
  render(<AssetGrantPanel />);
  fireEvent.click(screen.getByRole('button', { name: '管理资产授权' }));
  await screen.findByRole('option', { name: 'reader' });
  fireEvent.change(screen.getByLabelText('授权账号'), { target: { value: 'u' } });
  expect(await screen.findByText('当前未部署')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '撤销' }));
  expect(await screen.findByText('暂无可授权资产')).toBeTruthy();
  expect(revoked).toBe(true);
});
