import { cleanup, render, screen } from '@testing-library/react';
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
