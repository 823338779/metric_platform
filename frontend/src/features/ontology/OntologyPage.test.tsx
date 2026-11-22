import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { getAuthSession, saveAuthSession, clearAuthSession } from '../../api/auth';
import { environmentApi } from '../../api/environmentApi';
import { ontologyApi } from '../../api/ontologyApi';
import { ApiProblem } from '../../api/httpClient';
import { PlatformLayout } from '../../layout/PlatformLayout';
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
        element: <PlatformLayout />,
        children: [
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
      },
      { path: '/login', element: <p>登录页</p> },
    ],
    { initialEntries: ['/dbt/environments/test/ontology'] },
  );
  render(<RouterProvider router={router} />);
}
async function openLibrary() {
  fireEvent.click(await screen.findByRole('button', { name: '管理定义' }));
  await waitFor(() => expect(screen.getByRole('dialog', { name: '管理定义' })).toBeVisible());
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
  await openLibrary();
  expect(await screen.findByRole('button', { name: '编辑对象 订单' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: /close/i }));
  fireEvent.click(screen.getByRole('button', { name: '新建对象' }));
  await waitFor(() => expect(screen.getByText('此环境尚无可绑定的数据集')).toBeVisible());
  expect(screen.getByRole('button', { name: '保存对象' })).toBeDisabled();
});

it('按绑定列搜索目录，清除搜索后仍能在同一张图上编辑', async () => {
  mount();
  expect(await screen.findByRole('button', { name: '管理定义' })).toBeVisible();
  expect(screen.queryByRole('region', { name: '定义目录' })).toBeNull();
  await openLibrary();
  const search = await screen.findByRole('textbox', { name: '搜索定义' });
  fireEvent.change(search, { target: { value: 'old_id' } });
  expect(screen.getByText('old_id')).toBeVisible();
  fireEvent.change(search, { target: { value: 'missing' } });
  expect(screen.queryByRole('button', { name: '编辑对象 订单' })).toBeNull();
  expect(screen.getByRole('img', { name: '对象关系拓扑图' })).toBeVisible();
  fireEvent.click(screen.getByRole('button', { name: '清除搜索' }));
  fireEvent.click(screen.getByRole('button', { name: '编辑对象 订单' }));
  expect(screen.getByRole('dialog', { name: '编辑对象' })).toBeVisible();
  fireEvent.change(screen.getByLabelText('对象名称'), { target: { value: '销售订单' } });
  fireEvent.click(screen.getByRole('button', { name: '取消编辑' }));
  fireEvent.click(screen.getByRole('button', { name: '继续编辑' }));
  expect(screen.getByLabelText('对象名称')).toHaveValue('销售订单');
  fireEvent.click(screen.getByRole('button', { name: '取消编辑' }));
  fireEvent.click(screen.getByRole('button', { name: '放弃修改' }));
  expect(screen.getByRole('button', { name: '编辑对象 订单' })).toBeVisible();
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
  await openLibrary();
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

it('仅切换到已映射的主键也核对当前构建', async () => {
  const property = { id: 'new-key', name: '新键', columnName: 'id', declaredType: 'BIGINT' };
  vi.mocked(ontologyApi.get).mockResolvedValue({
    ...graph,
    objects: [{ ...order, properties: [...order.properties, property] }],
  });
  const write = vi.spyOn(ontologyApi, 'saveObject').mockResolvedValue({ id: 'order', revision: 4 });
  mount();
  await openLibrary();
  fireEvent.click(await screen.findByRole('button', { name: '编辑对象 订单' }));
  await screen.findAllByRole('option', { name: 'id · BIGINT' });
  fireEvent.change(screen.getByLabelText('声明主键'), { target: { value: property.id } });
  fireEvent.click(screen.getByRole('button', { name: '保存对象' }));
  await waitFor(() =>
    expect(write).toHaveBeenCalledWith(
      'test',
      3,
      'build',
      expect.objectContaining({
        primaryPropertyId: property.id,
        properties: [...order.properties, property],
      }),
      false,
    ),
  );
});

it('部署切换后在当前表单重新确认对象映射', async () => {
  const changed = new ApiProblem({
    title: '冲突',
    detail: '部署已切换',
    status: 409,
    code: 'DEPLOYMENT_CHANGED',
  });
  const write = vi
    .spyOn(ontologyApi, 'saveObject')
    .mockRejectedValueOnce(changed)
    .mockResolvedValue({ id: 'order', revision: 4 });
  mount();
  await openLibrary();
  fireEvent.click(await screen.findByRole('button', { name: '编辑对象 订单' }));
  await screen.findByRole('option', { name: 'id · BIGINT' });
  fireEvent.change(screen.getByLabelText('绑定列 1'), { target: { value: 'id' } });
  fireEvent.change(screen.getByLabelText('对象名称'), { target: { value: '我的订单' } });
  fireEvent.click(screen.getByRole('button', { name: '保存对象' }));
  vi.mocked(ontologyApi.get).mockResolvedValue({
    ...graph,
    bindingReport: { ...graph.bindingReport, buildId: 'next' },
  });
  vi.mocked(ontologyApi.datasets).mockResolvedValue({
    buildId: 'next',
    datasets: [{ assetId: 'orders', name: '订单数据集' }],
  });
  vi.mocked(ontologyApi.columns).mockResolvedValue({
    buildId: 'next',
    assetId: 'orders',
    columns: [{ name: 'id', declaredType: 'BIGINT' }],
  });
  fireEvent.click(await screen.findByRole('button', { name: '刷新绑定候选' }));
  expect(screen.getByLabelText('对象名称')).toHaveValue('我的订单');
  const confirm = await screen.findByRole('button', { name: '确认绑定并保存对象' });
  await waitFor(() => expect(confirm).toBeEnabled());
  fireEvent.click(confirm);
  await waitFor(() =>
    expect(write).toHaveBeenLastCalledWith(
      'test',
      3,
      'next',
      expect.objectContaining({ name: '我的订单' }),
      false,
    ),
  );
});

it('关系草稿可以基于新部署重新确认端点', async () => {
  const write = vi
    .spyOn(ontologyApi, 'saveRelation')
    .mockRejectedValueOnce(
      new ApiProblem({
        title: '冲突',
        detail: '部署已切换',
        status: 409,
        code: 'DEPLOYMENT_CHANGED',
      }),
    )
    .mockResolvedValue({ id: 'relation', revision: 4 });
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '新建关系' }));
  fireEvent.change(screen.getByLabelText('关系名称'), { target: { value: '上级订单' } });
  for (const side of ['源', '目标']) {
    fireEvent.change(screen.getByLabelText(side + '对象'), { target: { value: 'order' } });
    fireEvent.change(screen.getByLabelText(side + '属性'), { target: { value: 'order-id' } });
  }
  fireEvent.click(screen.getByRole('button', { name: '保存关系' }));
  vi.mocked(ontologyApi.get).mockResolvedValue({
    ...graph,
    bindingReport: { ...graph.bindingReport, buildId: 'next' },
  });
  fireEvent.click(await screen.findByRole('button', { name: '刷新绑定候选' }));
  fireEvent.click(await screen.findByRole('button', { name: '确认绑定并保存关系' }));
  await waitFor(() =>
    expect(write).toHaveBeenLastCalledWith(
      'test',
      3,
      'next',
      expect.objectContaining({
        name: '上级订单',
        sourcePropertyId: 'order-id',
        targetPropertyId: 'order-id',
      }),
      true,
    ),
  );
});

it('写入进行中不允许放弃已提交的修改，成功后继续导航', async () => {
  let finish!: (result: { id: string; revision: number }) => void;
  vi.spyOn(ontologyApi, 'saveObject').mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  mount();
  await openLibrary();
  fireEvent.click(await screen.findByRole('button', { name: '编辑对象 订单' }));
  fireEvent.change(screen.getByLabelText('对象名称'), { target: { value: '已提交订单' } });
  fireEvent.click(screen.getByRole('button', { name: '保存对象' }));
  fireEvent.click(screen.getByRole('link', { name: '切换环境' }));
  expect(screen.queryByRole('button', { name: '放弃并离开' })).not.toBeInTheDocument();
  await act(async () => finish({ id: 'order', revision: 4 }));
  expect(await screen.findByText('生产环境')).toBeVisible();
});

it('显式登出后进入登录页', async () => {
  mount();
  await openLibrary();
  fireEvent.click(await screen.findByRole('button', { name: '编辑对象 订单' }));
  fireEvent.change(screen.getByLabelText('对象名称'), { target: { value: '未保存' } });
  fireEvent.click(screen.getByRole('button', { name: '登出' }));
  expect(await screen.findByText('登录页')).toBeVisible();
});
