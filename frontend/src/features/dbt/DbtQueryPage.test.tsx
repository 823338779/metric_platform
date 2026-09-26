import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '日期粒度' }));
  fireEvent.click(await screen.findByText('metric_time · month'));
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
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '指标' }));
  fireEvent.change(screen.getByRole('combobox', { name: '指标' }), {
    target: { value: 'metric_100' },
  });
  expect(await screen.findByRole('option', { name: 'metric_100' })).toBeTruthy();
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
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '筛选维度' }));
  fireEvent.click(await screen.findByText('Region'));
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
