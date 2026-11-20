import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { getAuthSession, saveAuthSession, clearAuthSession } from '../../api/auth';
import { environmentApi } from '../../api/environmentApi';
import { ontologyApi } from '../../api/ontologyApi';
import { ApiProblem } from '../../api/httpClient';
import { OntologyPage } from './OntologyPage';

const order = {
  id: 'order',
  name: '订单',
  description: '',
  datasetAssetId: 'orders',
  primaryPropertyId: 'order-id',
  properties: [{ id: 'order-id', name: '订单编号', columnName: 'old_id', declaredType: 'INT' }],
};
const graph = {
  revision: 3,
  objects: [order],
  relations: [],
  bindingReport: { buildId: 'build', revision: 3, findings: [] },
};
const path = '/dbt/environments/:environmentId/ontology';
function mount() {
  const router = createMemoryRouter(
    [
      {
        path,
        element: (
          <>
            <Link to="/dbt/environments/prod/ontology">切换环境</Link>
            <OntologyPage />
          </>
        ),
      },
    ],
    { initialEntries: ['/dbt/environments/test/ontology'] },
  );
  render(<RouterProvider router={router} />);
}
beforeEach(async () => {
  // 测试路由使用 Node 的 Request，它要求同一运行时的 AbortSignal。
  const nodeUtil = await vi.importActual<{ transferableAbortController: typeof AbortController }>(
    'node:util',
  );
  vi.stubGlobal('AbortController', nodeUtil.transferableAbortController);
  saveAuthSession({
    accessToken: 'fixture',
    tokenType: 'Bearer',
    expiresIn: 3600,
    identity: {
      identityId: 'user',
      subject: 'user',
      displayName: '管理者',
      authVersion: 1,
      tenant_id: 'tenant',
      role: 'TENANT_MANAGER',
    },
  });
  vi.spyOn(environmentApi, 'get').mockImplementation(
    async (id) => ({ name: id === 'test' ? '测试环境' : '生产环境', environmentId: id }) as never,
  );
  vi.spyOn(ontologyApi, 'get').mockResolvedValue(graph);
  vi.spyOn(ontologyApi, 'datasets').mockResolvedValue({
    buildId: 'build',
    datasets: [{ assetId: 'orders', name: '订单数据集' }],
  });
  vi.spyOn(ontologyApi, 'columns').mockResolvedValue({
    buildId: 'build',
    assetId: 'orders',
    columns: [{ name: 'id', declaredType: 'BIGINT' }],
  });
});
afterEach(() => {
  cleanup();
  clearAuthSession();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('只读账号直接访问建模地址不会读取管理数据', async () => {
  const session = getAuthSession()!;
  saveAuthSession({ ...session, identity: { ...session.identity, role: 'READ_ONLY' } });
  mount();
  expect(await screen.findByRole('alert')).toHaveTextContent('仅租户管理者');
  expect(ontologyApi.get).not.toHaveBeenCalled();
});

it('环境无部署时保留已有对象并说明无法新建绑定', async () => {
  vi.mocked(ontologyApi.datasets).mockResolvedValue({ buildId: null, datasets: [] });
  mount();
  expect(await screen.findByRole('button', { name: '编辑对象 订单' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '新建对象' }));
  expect(await screen.findByText('此环境尚无可绑定的数据集')).toBeVisible();
  expect(screen.getByRole('button', { name: '保存对象' })).toBeDisabled();
});

it('修复字段沿用属性及主键 ID，发生并发冲突保留当前输入', async () => {
  vi.mocked(ontologyApi.get).mockResolvedValue({
    ...graph,
    bindingReport: {
      ...graph.bindingReport,
      findings: [
        { kind: 'PROPERTY', id: 'order-id', status: 'NEEDS_REPAIR', reason: 'TYPE_CONFLICT' },
      ],
    },
  });
  const write = vi
    .spyOn(ontologyApi, 'saveObject')
    .mockRejectedValue(new ApiProblem({ title: 'Conflict', detail: '编辑序号冲突', status: 409 }));
  mount();
  expect(await screen.findByText(/需修复/)).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '编辑对象 订单' }));
  await screen.findByRole('option', { name: /^id/ });
  fireEvent.change(screen.getByLabelText('绑定列 1'), { target: { value: 'id' } });
  fireEvent.change(screen.getByLabelText('对象名称'), { target: { value: '销售订单' } });
  fireEvent.click(screen.getByRole('button', { name: '保存对象' }));
  await waitFor(() =>
    expect(write).toHaveBeenCalledWith(
      'test',
      3,
      'build',
      expect.objectContaining({
        id: 'order',
        name: '销售订单',
        primaryPropertyId: 'order-id',
        properties: [
          { id: 'order-id', name: '订单编号', columnName: 'id', declaredType: 'BIGINT' },
        ],
      }),
      false,
    ),
  );
  expect(await screen.findByText(/编辑序号冲突/)).toBeVisible();
  expect(screen.getByLabelText('对象名称')).toHaveValue('销售订单');
  fireEvent.click(screen.getByRole('link', { name: '切换环境' }));
  expect(await screen.findByRole('button', { name: '留在此页' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '留在此页' }));
  expect(screen.getByLabelText('对象名称')).toHaveValue('销售订单');
  fireEvent.click(screen.getByRole('link', { name: '切换环境' }));
  fireEvent.click(await screen.findByRole('button', { name: '放弃并离开' }));
  expect(await screen.findByText('生产环境')).toBeVisible();
  expect(screen.queryByLabelText('对象名称')).not.toBeInTheDocument();
});

it('生产环境图加载后迟到的测试环境响应被忽略', async () => {
  let resolve!: (value: typeof graph) => void;
  vi.mocked(ontologyApi.get).mockImplementation((id) =>
    id === 'test'
      ? new Promise((done) => {
          resolve = done;
        })
      : Promise.resolve({ ...graph, objects: [] }),
  );
  mount();
  await waitFor(() => expect(ontologyApi.get).toHaveBeenCalled());
  fireEvent.click(screen.getByRole('link', { name: '切换环境' }));
  await screen.findByText('生产环境');
  await act(async () => resolve({ ...graph, objects: [{ ...order, name: '仅测试环境订单' }] }));
  expect(screen.queryByText('仅测试环境订单')).not.toBeInTheDocument();
});
