import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { DbtQueryPage } from './DbtQueryPage';
import { DbtDimensionValues } from './DbtDimensionValues';

beforeAll(() => {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  window.matchMedia = vi.fn().mockImplementation(() => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
});
afterEach(cleanup);

const options = {
  releaseId: 'A',
  metrics: [{ name: 'revenue' }],
  dimensions: [{ token: 'region', name: 'Region' }],
  timeDimensions: [{ token: 'metric_time__month', granularity: 'month' }],
  allowedFilters: ['=', 'IN'],
};
const api = () => ({
  listDbtProjects: vi.fn().mockResolvedValue([{ projectId: 'demo', activeReleaseId: 'A' }]),
  getDbtCatalog: vi.fn().mockResolvedValue({
    releaseId: 'A',
    resources: [
      { resourceId: 'r', kind: 'METRIC', name: 'revenue', queryable: true },
      { resourceId: 'o', kind: 'METRIC', name: 'orders', queryable: true },
    ],
  }),
  getDbtQueryOptions: vi.fn().mockResolvedValue(options),
  submitDbtQuery: vi.fn().mockResolvedValue({ queryId: 'q', releaseId: 'A', status: 'PENDING' }),
  explainDbtQuery: vi.fn(),
  getDbtQuery: vi.fn().mockResolvedValue({
    queryId: 'q',
    releaseId: 'A',
    status: 'COMPLETED',
    columns: [],
    rows: [],
    errorCode: null,
  }),
  submitDbtDimensionValues: vi.fn(),
});
function SwitchRelease() {
  const navigate = useNavigate();
  return (
    <button onClick={() => navigate('/dbt/projects/demo/query?releaseId=B&metric=orders')}>
      切换版本
    </button>
  );
}
const page = (client: ReturnType<typeof api>) =>
  render(
    <MemoryRouter initialEntries={['/dbt/projects/demo/query?releaseId=A&metric=revenue']}>
      <SwitchRelease />
      <Routes>
        <Route
          path="/dbt/projects/:projectId/query"
          element={<DbtQueryPage api={client as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );

it('loads fixed-release options and submits the returned metric_time token', async () => {
  const client = api();
  page(client);
  await waitFor(() =>
    expect(client.getDbtQueryOptions).toHaveBeenCalledWith('demo', 'A', ['revenue']),
  );
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(false),
  );
  fireEvent.click(
    within(screen.getByRole('group', { name: '日期粒度' })).getByRole('button', {
      name: '月',
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '运行查询' }));
  await waitFor(() =>
    expect(client.submitDbtQuery).toHaveBeenCalledWith(
      'demo',
      expect.objectContaining({ releaseId: 'A', groupBy: ['metric_time__month'] }),
    ),
  );
});

it('clears query state when the URL release changes', async () => {
  const client = api();
  page(client);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: '运行查询' }));
  expect(await screen.findByText('queryId：q')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '切换版本' }));
  await waitFor(() => expect(screen.queryByText('queryId：q')).toBeNull());
  expect(screen.getByText('releaseId：B')).toBeTruthy();
});

it('shows a way back to the active release when querying a historical release', async () => {
  const client = {
    ...api(),
    listDbtProjects: vi.fn().mockResolvedValue([{ projectId: 'demo', activeReleaseId: 'B' }]),
  };
  page(client);
  expect(await screen.findByText('当前查看的是历史发布版本')).toBeTruthy();
  expect(client.getDbtCatalog).not.toHaveBeenCalled();
  expect(client.getDbtQueryOptions).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(true);
  fireEvent.click(screen.getByRole('link', { name: '切换到当前发布版本' }));
  await waitFor(() => expect(screen.getByText('releaseId：B')).toBeTruthy());
  expect(screen.queryByText('当前查看的是历史发布版本')).toBeNull();
});

it('loads metrics beyond the first catalog page', async () => {
  const client = api();
  client.getDbtCatalog.mockImplementation((_: string, release: string, __: string, page: number) =>
    Promise.resolve({
      releaseId: release,
      total: 101,
      resources:
        page === 0
          ? Array.from({ length: 100 }, (_, index) => ({
              resourceId: String(index),
              kind: 'METRIC',
              name: `metric_${index}`,
              queryable: true,
            }))
          : [{ resourceId: 'last', kind: 'METRIC', name: 'metric_100', queryable: true }],
    }),
  );
  page(client);
  await waitFor(() =>
    expect(client.getDbtCatalog).toHaveBeenCalledWith('demo', 'A', '', 1, 100, 'METRIC'),
  );
  fireEvent.change(screen.getByRole('textbox', { name: '搜索指标' }), {
    target: { value: 'metric_100' },
  });
  expect(await screen.findByRole('checkbox', { name: 'metric_100' })).toBeTruthy();
});

it('does not show a stale submit failure after switching releases', async () => {
  const client = api();
  let rejectOld: ((cause: Error) => void) | undefined;
  client.submitDbtQuery.mockImplementation(
    () =>
      new Promise((_, reject) => {
        rejectOld = reject;
      }),
  );
  page(client);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: '运行查询' }));
  fireEvent.click(screen.getByRole('button', { name: '切换版本' }));
  rejectOld?.(new Error('old release failed'));
  await waitFor(() => expect(screen.getByText('releaseId：B')).toBeTruthy());
  expect(screen.queryByText('查询提交失败')).toBeNull();
});

it('disables query when the runtime has no compatible options', async () => {
  const client = api();
  client.getDbtQueryOptions.mockRejectedValue(
    new ApiProblem({ status: 422, title: 'Unprocessable', detail: 'invalid metrics' }),
  );
  page(client);
  expect(await screen.findByText('当前指标组合无法查询')).toBeTruthy();
  expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(true);
});

it('distinguishes a runtime outage from an incompatible metric combination', async () => {
  const client = api();
  client.getDbtQueryOptions.mockRejectedValue(
    new ApiProblem({ status: 503, title: 'Unavailable', detail: 'runtime unavailable' }),
  );
  page(client);
  expect(await screen.findByText('dbt 运行服务暂不可用')).toBeTruthy();
  expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(true);
});

it('shows truncated dimension values for a typed filter', async () => {
  const client = api();
  client.submitDbtDimensionValues.mockResolvedValue({
    queryId: 'values',
    releaseId: 'A',
    status: 'PENDING',
  });
  client.getDbtQuery.mockResolvedValue({
    queryId: 'values',
    releaseId: 'A',
    status: 'COMPLETED',
    rows: [['North']],
    truncated: true,
    columns: [{ name: 'region', type: 'TEXT' }],
  });
  const selected = vi.fn();
  render(
    <DbtDimensionValues
      projectId="demo"
      releaseId="A"
      metrics={['revenue']}
      dimension="region"
      onSelect={selected}
      api={client as never}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '查询维度值' }));
  expect(await screen.findByText('维度值已截断，请增加筛选条件')).toBeTruthy();
  expect(client.submitDbtDimensionValues).toHaveBeenCalledWith(
    'demo',
    expect.objectContaining({ releaseId: 'A', dimension: 'region', limit: 100 }),
  );
});

it('passes the current typed filter into dimension value enumeration', async () => {
  const client = api();
  client.submitDbtDimensionValues.mockResolvedValue({
    queryId: 'values',
    releaseId: 'A',
    status: 'PENDING',
  });
  client.getDbtQuery.mockResolvedValue({
    queryId: 'values',
    releaseId: 'A',
    status: 'COMPLETED',
    rows: [['North']],
    columns: [{ name: 'region', type: 'TEXT' }],
  });
  page(client);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(false),
  );
  fireEvent.click(screen.getByText('筛选与排序'));
  fireEvent.click(
    within(screen.getByRole('group', { name: '筛选维度' })).getByRole('button', { name: 'Region' }),
  );
  fireEvent.change(screen.getByRole('textbox', { name: '筛选值' }), { target: { value: 'North' } });
  fireEvent.click(screen.getByRole('button', { name: '查询维度值' }));
  await waitFor(() =>
    expect(client.submitDbtDimensionValues).toHaveBeenCalledWith(
      'demo',
      expect.objectContaining({
        releaseId: 'A',
        filters: [{ field: 'region', operator: '=', value: 'North' }],
      }),
    ),
  );
});

it('rejects an inverted date range before submitting a query', async () => {
  const client = api();
  page(client);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(false),
  );
  fireEvent.change(screen.getByLabelText('开始时间'), { target: { value: '2026-10-01T00:00' } });
  fireEvent.change(screen.getByLabelText('结束时间'), { target: { value: '2026-09-01T00:00' } });
  fireEvent.click(screen.getByRole('button', { name: '运行查询' }));
  expect(await screen.findByText('开始时间不能晚于结束时间')).toBeTruthy();
  expect(client.submitDbtQuery).not.toHaveBeenCalled();
});

it('generates SQL through the explain action and displays its result', async () => {
  const client = api();
  let finish: ((value: unknown) => void) | undefined;
  client.explainDbtQuery.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  client.getDbtQuery.mockResolvedValue({
    queryId: 'explain',
    releaseId: 'A',
    status: 'COMPLETED',
    mode: 'EXPLAIN',
    columns: [],
    rows: [],
    sql: 'select 42 as revenue',
    errorCode: null,
  });
  page(client);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '解释查询' }).hasAttribute('disabled')).toBe(false),
  );
  fireEvent.click(screen.getByRole('button', { name: '解释查询' }));
  expect(await screen.findByText('正在生成 SQL，请稍候…')).toBeTruthy();
  expect(client.submitDbtQuery).not.toHaveBeenCalled();
  finish?.({ queryId: 'explain', releaseId: 'A', status: 'PENDING' });
  expect((await screen.findByLabelText('生成的 SQL')).textContent).toBe('select 42 as revenue');
});

it('distinguishes same-name dimensions and submits the selected source token', async () => {
  const client = api();
  client.getDbtQueryOptions.mockResolvedValue({
    ...options,
    dimensions: [
      { token: 'shop__region', name: 'region' },
      { token: 'user__region', name: 'region' },
    ],
  });
  page(client);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(false),
  );
  expect(
    within(screen.getByRole('group', { name: '维度' })).getByRole('button', {
      name: 'region（shop__region）',
    }),
  ).toBeTruthy();
  fireEvent.click(
    within(screen.getByRole('group', { name: '维度' })).getByRole('button', {
      name: 'region（user__region）',
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '运行查询' }));
  await waitFor(() =>
    expect(client.submitDbtQuery).toHaveBeenCalledWith(
      'demo',
      expect.objectContaining({ groupBy: ['user__region'] }),
    ),
  );
});

it('submits the selected dimension and date grain for the current metric', async () => {
  const client = api();
  page(client);
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '运行查询' }).hasAttribute('disabled')).toBe(false),
  );
  fireEvent.click(
    within(screen.getByRole('group', { name: '维度' })).getByRole('button', { name: 'Region' }),
  );
  fireEvent.click(
    within(screen.getByRole('group', { name: '日期粒度' })).getByRole('button', {
      name: '月',
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '运行查询' }));
  await waitFor(() =>
    expect(client.submitDbtQuery).toHaveBeenCalledWith(
      'demo',
      expect.objectContaining({
        releaseId: 'A',
        metrics: ['revenue'],
        groupBy: ['region', 'metric_time__month'],
        filters: [],
        orderBy: ['metric_time__month'],
      }),
    ),
  );
});
