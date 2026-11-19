import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { AssetGrantPanel } from './AssetAccess';

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
    if (url.endsWith('/accounts'))
      return Response.json([
        { id: 'first', username: 'other' },
        { id: 'u', username: 'reader' },
      ]);
    if (url.endsWith('/first/assets')) return Response.json([]);
    if (url.endsWith('/assets')) return Response.json([{ ...asset, granted }]);
    granted = init.method === 'PUT';
    return new Response(null, { status: 204 });
  });
  vi.stubGlobal('fetch', fetcher);
  render(<AssetGrantPanel />);
  fireEvent.click(await screen.findByRole('button', { name: 'reader' }));
  fireEvent.click(await screen.findByRole('button', { name: '授予' }));
  fireEvent.click(await screen.findByRole('button', { name: '撤销' }));
  expect(await screen.findByRole('button', { name: '授予' })).toBeTruthy();
  expect(granted).toBe(false);
  expect(fetcher).toHaveBeenCalledWith(
    '/api/v1/tenant/accounts/u/assets/m',
    expect.objectContaining({ method: 'DELETE' }),
  );
});

it('groups grants by environment identity while keeping same-named assets separate', async () => {
  const prod = { ...asset, environmentName: '生产' };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) =>
      Response.json(
        url.endsWith('/accounts')
          ? [{ id: 'u', username: 'reader' }]
          : [
              prod,
              { ...prod, assetId: 'ds', kind: 'DATASET', name: 'orders' },
              {
                ...prod,
                assetId: 'test',
                environmentName: '测试',
                target: { ...asset.target, environmentId: 'test' },
              },
            ],
      ),
    ),
  );
  render(<AssetGrantPanel />);
  const production = await screen.findByRole('region', { name: '生产' });
  const test = screen.getByRole('region', { name: '测试' });
  expect(within(production).getAllByRole('listitem')).toHaveLength(2);
  expect(within(test).getAllByRole('listitem')).toHaveLength(1);
  expect(within(production).getByText('sales')).toBeTruthy();
  expect(within(test).getByText('sales')).toBeTruthy();
});

it('filters asset types within environments and keeps the selected type after granting', async () => {
  let granted = false;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      if (url.endsWith('/accounts')) return Response.json([{ id: 'u', username: 'reader' }]);
      if (url.endsWith('/assets'))
        return Response.json([
          asset,
          {
            ...asset,
            assetId: 'd',
            kind: 'DIMENSION',
            dimensionName: 'region',
            granted,
            target: { ...asset.target, environmentId: 'other' },
          },
        ]);
      granted = url.endsWith('/u/assets/d') && init.method === 'PUT';
      return new Response(null, { status: 204 });
    }),
  );
  render(<AssetGrantPanel />);
  await screen.findByText('region');
  const filters = within(screen.getByRole('group', { name: '资产类型' }));
  fireEvent.click(filters.getByRole('radio', { name: /维度/ }));
  expect(screen.queryByRole('region', { name: 'env' })).toBeNull();
  const region = within(screen.getByRole('region', { name: 'other' }));
  expect(region.getByText('未授权')).toBeTruthy();
  fireEvent.click(region.getByRole('button', { name: '授予' }));
  expect(await region.findByText('已授权')).toBeTruthy();
  expect((filters.getByRole('radio', { name: /维度/ }) as HTMLInputElement).checked).toBe(true);
  fireEvent.click(filters.getByRole('radio', { name: /数据集/ }));
  expect(screen.getByText('暂无数据集资产')).toBeTruthy();
  fireEvent.click(filters.getByRole('radio', { name: /指标/ }));
  expect(screen.getByText('sales')).toBeTruthy();
  expect(screen.queryByText('region')).toBeNull();
  fireEvent.click(filters.getByRole('radio', { name: /全部/ }));
  expect(screen.getByText('region')).toBeTruthy();
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
  await screen.findByRole('option', { name: 'reader' });
  fireEvent.change(screen.getByLabelText('授权账号'), { target: { value: 'u' } });
  expect(await screen.findByText('当前未部署')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '撤销' }));
  expect(await screen.findByText('暂无可授权资产')).toBeTruthy();
  expect(revoked).toBe(true);
});
