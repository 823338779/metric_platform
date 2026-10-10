import '@testing-library/jest-dom/vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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
const O = '44444444-4444-4444-8444-444444444444';
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
    options: vi
      .fn()
      .mockResolvedValue({ optionsTaskId: O, buildId: B1, state: 'QUEUED', options: [] }),
    getOptions: vi
      .fn()
      .mockResolvedValue({ optionsTaskId: O, buildId: B1, state: 'SUCCEEDED', options: [] }),
    submit: vi
      .fn()
      .mockResolvedValue({ queryId: Q, buildId: B1, state: 'SUCCEEDED', resultAvailable: true }),
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
it('异步选项完成前禁查询，新部署不替换选项和查询 buildId', async () => {
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
  expect(api.getOptions).toHaveBeenCalledWith(O);
  expect(api.submit).toHaveBeenCalledWith(
    B1,
    expect.objectContaining({ metricResourceIds: ['metric'], mode: 'QUERY' }),
  );
  expect(api.submit.mock.calls[0][1]).not.toHaveProperty('buildId');
});
it('结果分页固定query/build，继续保留高精度原值', async () => {
  const api = fixture();
  api.options.mockResolvedValue({ optionsTaskId: O, buildId: B1, state: 'SUCCEEDED', options: [] });
  api.results
    .mockResolvedValueOnce({
      queryId: Q,
      buildId: B1,
      state: 'SUCCEEDED',
      columns: [{ name: 'value', type: 'DECIMAL' }],
      rows: [['9007199254740993.01']],
      nextOffset: 1,
      resultTruncated: false,
    })
    .mockResolvedValueOnce({
      queryId: Q,
      buildId: B1,
      state: 'SUCCEEDED',
      columns: [{ name: 'value', type: 'DECIMAL' }],
      rows: [['9007199254740994.02']],
      nextOffset: null,
      resultTruncated: false,
    });
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  fireEvent.click(await screen.findByRole('button', { name: '加载更多结果' }));
  expect(await screen.findByText(/9007199254740994.02/)).toHaveTextContent('9007199254740993.01');
  expect(api.results).toHaveBeenLastCalledWith(Q, 1);
  expect(api.results).toHaveBeenCalledTimes(2);
  expect(api.submit).toHaveBeenCalledWith(B1, expect.objectContaining({ limit: 1000 }));
  expect(screen.queryByRole('button', { name: '加载更多结果' })).not.toBeInTheDocument();
});
it('响应丢失重试沿用原请求键和时间解释，不创建新查询意图', async () => {
  const api = fixture();
  api.options.mockResolvedValue({ optionsTaskId: O, buildId: B1, state: 'SUCCEEDED', options: [] });
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
    api.options.mockResolvedValue({
      optionsTaskId: O,
      buildId: B1,
      state: 'SUCCEEDED',
      options: [
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
  api.options.mockResolvedValue({ optionsTaskId: O, buildId: B1, state: 'SUCCEEDED', options: [] });
  api.results.mockResolvedValue({
    queryId: Q,
    buildId: B2,
    state: 'SUCCEEDED',
    rows: [['wrong-build']],
  });
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(await screen.findByText(/查询结果身份不匹配/)).toBeInTheDocument();
  expect(screen.queryByText(/wrong-build/)).not.toBeInTheDocument();
});

it('普通查询OUTCOME_UNKNOWN显示诊断并停止轮询', async () => {
  const api = {
    ...fixture(),
    query: vi.fn().mockResolvedValue({
      queryId: Q,
      buildId: B1,
      state: 'OUTCOME_UNKNOWN',
      error: { code: 'QUERY_OUTCOME_UNKNOWN', message: 'worker lost', retryable: false },
    }),
  };
  api.options.mockResolvedValue({ optionsTaskId: O, buildId: B1, state: 'SUCCEEDED', options: [] });
  api.submit.mockResolvedValue({ queryId: Q, buildId: B1, state: 'RUNNING' });
  mount(api);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(screen.getByRole('button', { name: '查询' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await screen.findByText(/OUTCOME_UNKNOWN.*worker lost/, {}, { timeout: 2500 });
  await new Promise((resolve) => setTimeout(resolve, 1200));
  expect(api.query).toHaveBeenCalledTimes(1);
  expect(api.results).not.toHaveBeenCalled();
});
