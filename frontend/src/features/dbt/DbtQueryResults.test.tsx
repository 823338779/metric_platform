import * as echarts from 'echarts/core';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import type { DbtQueryStatus } from '../../api/types';
import { DbtQueryResults } from './DbtQueryResults';

vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({
    setOption: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn(),
  })),
}));
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
const result = (columns: { name: string; type: string }[], rows: unknown[][]): DbtQueryStatus => ({
  queryId: 'q',
  releaseId: 'r',
  targetCommitSha: 'sha',
  mode: 'QUERY',
  status: 'COMPLETED',
  columns,
  rows,
  errorCode: null,
  truncated: false,
  durationMs: 10,
});

it('shows generated SQL for explain results instead of an empty data table', () => {
  const sql = 'select count(distinct order_id) as order_count\nfrom orders';
  const explained = { ...result([], []), mode: 'EXPLAIN' as const, sql };
  render(<DbtQueryResults result={explained} />);
  expect(screen.getByLabelText('生成的 SQL').textContent).toBe(sql);
  expect(screen.queryByRole('table')).toBeNull();
  expect(screen.queryByText(/当前条件下没有数据/)).toBeNull();
});

it('shows progress and actionable errors for explain results', () => {
  const explained = { ...result([], []), mode: 'EXPLAIN' as const };
  const view = render(<DbtQueryResults result={{ ...explained, status: 'RUNNING' }} />);
  expect(screen.getByText('正在生成 SQL，请稍候…')).toBeTruthy();
  view.rerender(
    <DbtQueryResults
      result={{ ...explained, status: 'FAILED', errorCode: 'INVALID_DIMENSION_COMBINATION' }}
    />,
  );
  expect(screen.getByText(/SQL 生成失败/)).toBeTruthy();
  expect(screen.getByText(/INVALID_DIMENSION_COMBINATION/)).toBeTruthy();
  view.rerender(<DbtQueryResults result={explained} />);
  expect(screen.getByText('运行服务未返回 SQL，请重新解释查询')).toBeTruthy();
});

it('keeps decimal and bigint strings exact in the table', async () => {
  render(
    <DbtQueryResults
      result={result(
        [
          { name: 'amount', type: 'DECIMAL(38,2)' },
          { name: 'count', type: 'BIGINT' },
        ],
        [['12345678901234567890.12', '9007199254740993']],
      )}
    />,
  );
  expect(await screen.findByText('12345678901234567890.12')).toBeTruthy();
  expect(screen.getByText('9007199254740993')).toBeTruthy();
  expect(screen.queryByRole('img', { name: '指标图' })).toBeNull();
});

it('offers a line chart for a safe time series', async () => {
  render(
    <DbtQueryResults
      result={result(
        [
          { name: 'metric_time', type: 'DATE' },
          { name: 'revenue', type: 'DECIMAL(12,2)' },
        ],
        [['2026-09-01', '123.45']],
      )}
    />,
  );
  expect(await screen.findByRole('img', { name: '时间序列图' })).toBeTruthy();
  expect(screen.getByRole('radio', { name: '折线图' })).toBeTruthy();
  expect(screen.getByText('123.45')).toBeTruthy();
});

it('keeps multi-dimension results in the table without merging chart categories', async () => {
  render(
    <DbtQueryResults
      result={result(
        [
          { name: 'metric_time', type: 'DATE' },
          { name: 'region', type: 'TEXT' },
          { name: 'revenue', type: 'DECIMAL(12,2)' },
        ],
        [['2026-09-01', 'North', '12.50']],
      )}
    />,
  );
  expect(await screen.findByText('North')).toBeTruthy();
  expect(screen.queryByRole('img', { name: '时间序列图' })).toBeNull();
});

it('shows task failure code even when no rows exist', () => {
  render(
    <DbtQueryResults
      result={{ ...result([], []), status: 'FAILED', errorCode: 'INVALID_DIMENSION_COMBINATION' }}
    />,
  );
  expect(screen.getByText('INVALID_DIMENSION_COMBINATION')).toBeTruthy();
});

it('plots time and category as separate series without summing rows', async () => {
  render(
    <DbtQueryResults
      metricNames={['revenue']}
      result={result(
        [
          { name: 'month', type: 'datetime' },
          { name: 'region', type: 'str' },
          { name: 'revenue', type: 'Decimal' },
        ],
        [
          ['2026-02-01', 'North', '20.5'],
          ['2026-01-01', 'North', '10.5'],
          ['2026-01-01', 'South', '7.5'],
        ],
      )}
    />,
  );
  expect(await screen.findByRole('img', { name: '时间序列图' })).toBeTruthy();
  const chart = vi.mocked(echarts.init).mock.results.at(-1)!.value;
  const option = chart.setOption.mock.calls.at(-1)[0];
  expect(option.xAxis.data).toEqual(['2026-01-01', '2026-02-01']);
  expect(option.series.map((item: { data: unknown[] }) => item.data)).toEqual([
    [10.5, 20.5],
    [7.5, null],
  ]);
  expect(option.series.every((item: { type: string }) => item.type === 'line')).toBe(true);
});

it('treats a numeric identifier as a dimension when the metric is explicit', async () => {
  render(
    <DbtQueryResults
      metricNames={['revenue']}
      result={result(
        [
          { name: 'shop_id', type: 'int' },
          { name: 'revenue', type: 'float' },
        ],
        [
          [1, 12],
          [2, 20],
        ],
      )}
    />,
  );
  expect(await screen.findByRole('img', { name: '指标图' })).toBeTruthy();
});

it('shows an ungrouped metric as a value card and can switch to details', async () => {
  render(
    <DbtQueryResults
      metricNames={['revenue']}
      result={result([{ name: 'revenue', type: 'Decimal' }], [['123.45']])}
    />,
  );
  expect(await screen.findByRole('region', { name: '指标数值' })).toBeTruthy();
  fireEvent.click(screen.getByRole('radio', { name: '仅明细' }));
  expect(screen.queryByRole('region', { name: '指标数值' })).toBeNull();
  expect(screen.getByText('123.45')).toBeTruthy();
});

it('refuses ambiguous chart cells instead of aggregating ratios', async () => {
  render(
    <DbtQueryResults
      metricNames={['ratio']}
      result={result(
        [
          { name: 'region', type: 'str' },
          { name: 'ratio', type: 'float' },
        ],
        [
          ['North', 0.2],
          ['North', 0.8],
        ],
      )}
    />,
  );
  expect(await screen.findByText(/横轴与系列无法唯一区分/)).toBeTruthy();
  expect(screen.queryByRole('img', { name: '指标图' })).toBeNull();
  expect(screen.getByText('0.2')).toBeTruthy();
  expect(screen.getByText('0.8')).toBeTruthy();
});

it('keeps high precision decimals out of charts', async () => {
  render(
    <DbtQueryResults
      metricNames={['revenue']}
      result={result(
        [
          { name: 'region', type: 'str' },
          { name: 'revenue', type: 'Decimal' },
        ],
        [['North', '0.1234567890123456789']],
      )}
    />,
  );
  expect(await screen.findByText('0.1234567890123456789')).toBeTruthy();
  expect(screen.queryByRole('img', { name: '指标图' })).toBeNull();
});
