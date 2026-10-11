import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ApiProblem } from '../../api/httpClient';
import { PublishedWorkbench } from './PublishedWorkbench';
vi.mock('./DbtQueryResults', () => ({
  DbtQueryResults: ({
    result,
  }: {
    result: { rows: unknown; status: string; errorMessage?: string };
  }) => (
    <pre>
      {result.status} {result.errorMessage} {JSON.stringify(result.rows)}
    </pre>
  ),
}));
afterEach(cleanup);
const B1 = '11111111-1111-4111-8111-111111111111';
const B2 = '22222222-2222-4222-8222-222222222222';
const Q = '33333333-3333-4333-8333-333333333333';
function fixture() {
  return {
    build: vi.fn().mockResolvedValue({
      buildId: B1,
      repository: 'repo/a',
      branchName: 'feature/shared',
      environment: 'PREVIEW',
      queryAvailable: true,
    }),
    current: vi.fn().mockResolvedValue({ activeBuildId: B2 }),
    catalog: vi.fn().mockResolvedValue({
      buildId: B1,
      items: [{ resourceId: 'metric', displayName: '销售额' }],
      nextCursor: null,
    }),
    availableDimensions: vi
      .fn()
      .mockResolvedValue({ buildId: B1, metricResourceIds: ['metric'], dimensions: [] }),
    getOptions: vi.fn().mockResolvedValue({ buildId: B1, state: 'SUCCEEDED', dimensions: [] }),
    submit: vi.fn().mockResolvedValue({
      queryId: Q,
      buildId: B1,
      state: 'SUCCEEDED',
      resultAvailable: true,
      columns: [{ name: 'value', type: 'DECIMAL' }],
      rows: [['9007199254740993.01']],
      nextOffset: null,
    }),
    results: vi.fn().mockResolvedValue({
      queryId: Q,
      buildId: B1,
      state: 'SUCCEEDED',
      columns: [{ name: 'value', type: 'DECIMAL' }],
      rows: [['9007199254740993.01']],
      nextOffset: null,
      resultTruncated: false,
    }),
  };
}
function mount(api: unknown) {
  render(
    <MemoryRouter initialEntries={[`/dbt/builds/${B1}/query`]}>
      <Routes>
        <Route
          path="/dbt/builds/:buildId/query"
          element={<PublishedWorkbench api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
}
it('同步选项响应前禁查询，新部署不替换选项和查询 buildId', async () => {
  const api = fixture();
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  expect(screen.getByRole('button', { name: '查询' })).toBeDisabled();
  expect(await screen.findByText(/有效部署已更新/)).toBeInTheDocument();
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled(), {
    timeout: 2500,
  });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await screen.findByText(/9007199254740993.01/);
  expect(api.getOptions).not.toHaveBeenCalled();
  expect(api.submit).toHaveBeenCalledWith(
    B1,
    expect.objectContaining({ metricResourceIds: ['metric'], mode: 'QUERY' }),
  );
  expect(api.submit.mock.calls[0][1]).not.toHaveProperty('buildId');
});
it('完整同步结果固定build，保留超过旧单页上限的高精度原值', async () => {
  const api = fixture();
  api.submit.mockResolvedValueOnce({
    buildId: B1,
    columns: [{ name: 'value', type: 'DECIMAL' }],
    rows: Array.from({ length: 201 }, (_, index) => [
      index === 200 ? '9007199254740994.02' : '9007199254740993.01',
    ]),
    resultTruncated: false,
  });
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(await screen.findByText(/9007199254740994.02/)).toHaveTextContent('9007199254740993.01');
  expect(api.results).not.toHaveBeenCalled();
  expect(api.submit).toHaveBeenCalledWith(B1, expect.objectContaining({ limit: 1000 }));
  expect(api.submit.mock.calls[0][1]).not.toHaveProperty('idempotencyKey');
  expect(screen.queryByRole('button', { name: '加载更多结果' })).not.toBeInTheDocument();
});
it('响应丢失后手动重新执行保留固定构建与查询条件', async () => {
  const api = fixture();
  api.availableDimensions.mockResolvedValue({ buildId: B1, state: 'SUCCEEDED', dimensions: [] });
  api.submit.mockRejectedValueOnce(new Error('response lost'));
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await screen.findByText(/读取或查询失败/);
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() => expect(api.submit).toHaveBeenCalledTimes(2));
  expect(api.submit.mock.calls[1]).toEqual(api.submit.mock.calls[0]);
});

it.each(['EXPLAIN', 'DIMENSION_VALUES'] as const)(
  '提交 %s 时保留 optionId 与北京时间解释，拒绝颠倒范围',
  async (mode) => {
    const api = fixture();
    api.availableDimensions.mockResolvedValue({
      buildId: B1,
      state: 'SUCCEEDED',
      dimensions: [
        {
          optionId: 'opaque-region',
          resourceId: null,
          displayName: '地区',
          granularities: [],
          operators: [],
        },
      ],
    });
    mount(api);
    fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
    fireEvent.click(await screen.findByRole('button', { name: '地区' }));
    fireEvent.change(screen.getByLabelText('开始时间'), { target: { value: '2026-10-10T09:00' } });
    fireEvent.change(screen.getByLabelText('结束时间'), { target: { value: '2026-10-09T09:00' } });
    const label = mode === 'EXPLAIN' ? '查看 SQL' : '维度值';
    fireEvent.click(screen.getByRole('button', { name: new RegExp(label) }));
    expect(await screen.findByText('开始时间不能晚于结束时间')).toBeInTheDocument();
    expect(api.submit).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('结束时间'), { target: { value: '2026-10-11T09:00' } });
    fireEvent.click(screen.getByRole('button', { name: new RegExp(label) }));
    await waitFor(() =>
      expect(api.submit).toHaveBeenCalledWith(
        B1,
        expect.objectContaining({
          mode,
          startTime: '2026-10-10T01:00:00.000Z',
          endTime: '2026-10-11T01:00:00.000Z',
          groupBy: [{ optionId: 'opaque-region' }],
          ...(mode === 'DIMENSION_VALUES' ? { dimensionOptionId: 'opaque-region' } : {}),
        }),
      ),
    );
  },
);

it('错误构建的结果页不覆盖已有结果', async () => {
  const api = fixture();
  api.availableDimensions.mockResolvedValue({ buildId: B1, state: 'SUCCEEDED', dimensions: [] });
  api.submit.mockResolvedValue({
    queryId: Q,
    buildId: B2,
    state: 'SUCCEEDED',
    rows: [['wrong-build']],
  });
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(await screen.findByText(/查询版本不匹配/)).toBeInTheDocument();
  expect(screen.queryByText(/wrong-build/)).not.toBeInTheDocument();
});

it('同步查询失败展示诊断且不读取结果页', async () => {
  const api = fixture();
  api.submit.mockRejectedValue(new Error('QUERY_OUTCOME_UNKNOWN: worker lost'));
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await screen.findByText(/QUERY_OUTCOME_UNKNOWN.*worker lost/);
  expect(api.results).not.toHaveBeenCalled();
});

it('同步返回选项及首屏结果，不读取任务状态或重复拉取首屏', async () => {
  const api = fixture();
  api.availableDimensions.mockResolvedValue({
    buildId: B1,
    metricResourceIds: ['metric'],
    dimensions: [],
  });
  api.submit.mockResolvedValue({
    queryId: Q,
    buildId: B1,
    state: 'SUCCEEDED',
    resultAvailable: true,
    columns: [{ name: 'value', type: 'DECIMAL' }],
    rows: [['sync-first-page']],
    nextOffset: null,
  });
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(await screen.findByText(/sync-first-page/)).toBeInTheDocument();
  expect(api.getOptions).not.toHaveBeenCalled();
  expect(api.results).not.toHaveBeenCalled();
  expect(api.availableDimensions).toHaveBeenCalledWith(B1, { metricResourceIds: ['metric'] });
});

it('明确失败后再次同步查询不发送幂等键，查询条件保持不变', async () => {
  const api = fixture();
  api.submit.mockRejectedValueOnce(
    new ApiProblem({
      status: 503,
      title: 'Failure',
      detail: 'command failed',
      code: 'COMMAND_FAILED',
      retryable: false,
      buildId: B1,
    }),
  );
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await screen.findByText(/COMMAND_FAILED/);
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() => expect(api.submit).toHaveBeenCalledTimes(2));
  const first = api.submit.mock.calls[0][1];
  const second = api.submit.mock.calls[1][1];
  expect(second).not.toHaveProperty('idempotencyKey');
  expect(second).toEqual(first);
});
