import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import type {
  QueryConnectivityResponse,
  QueryResponse,
  SemanticSearchResult,
  ValidationResponse,
} from '../../api/types';
import { QueryWorkbenchPage } from './QueryWorkbenchPage';

afterEach(cleanup);
beforeAll(() => {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
});

const metric = semantic('metric-1', 'METRIC', '销售额');
const dimension = semantic('dimension-1', 'DIMENSION', '客户');
const connectivity: QueryConnectivityResponse = {
  catalogVersion: 'catalog-1',
  datasets: [{ datasetId: 'dataset-1', dataSourceId: 'source-1' }],
  paths: [],
};

it('loads connectivity once and does not validate while selecting objects', async () => {
  const api = fakeApi();
  render(
    <MemoryRouter>
      <QueryWorkbenchPage api={api} />
    </MemoryRouter>,
  );

  fireEvent.click(await screen.findByRole('button', { name: `添加${metric.displayName}` }));
  fireEvent.click(screen.getByRole('button', { name: `添加${dimension.displayName}` }));

  expect(api.getQueryConnectivity).toHaveBeenCalledTimes(1);
  expect(api.validateQuery).not.toHaveBeenCalled();
  expect(api.executeQuery).not.toHaveBeenCalled();

  const resultCard = screen.getByText('查询结果').closest('.ant-card');
  expect(resultCard).toBeTruthy();
  expect(within(resultCard as HTMLElement).getByRole('button', { name: '执行查询' })).toBeTruthy();
  expect(within(resultCard as HTMLElement).getByRole('button', { name: '刷新' })).toBeTruthy();
  expect(within(resultCard as HTMLElement).getByRole('button', { name: '导出' })).toBeTruthy();
  expect(screen.getAllByRole('button', { name: '执行查询' })).toHaveLength(1);

  fireEvent.click(screen.getByRole('button', { name: '执行查询' }));
  await waitFor(() => expect(api.validateQuery).toHaveBeenCalledTimes(1));
  expect(api.executeQuery).toHaveBeenCalledTimes(1);
});

function fakeApi() {
  return {
    querySemanticObjects: vi.fn().mockResolvedValue({
      items: [metric, dimension],
      pageInfo: { hasMore: false, limit: 50 },
      facets: { types: { METRIC: 1, DIMENSION: 1 }, domains: {}, statuses: {} },
      catalogVersion: 'catalog-1',
    }),
    getQueryConnectivity: vi.fn().mockResolvedValue(connectivity),
    resolveSemantic: vi.fn(),
    validateQuery: vi.fn().mockResolvedValue(validation),
    executeQuery: vi.fn().mockResolvedValue(result),
  };
}

const validation: ValidationResponse = {
  valid: true,
  metrics: [],
  dimensions: [],
  aggregates: [],
  resolvedRelationshipIds: [],
  parameterCount: 0,
  issues: [],
  warnings: [],
};

const result: QueryResponse = {
  columns: [{ name: '销售额', type: 'DECIMAL' }],
  rows: [[100]],
  metrics: [],
  dimensions: [],
  durationMs: 1,
  queryId: 'query-1',
  rowCount: 1,
  truncated: false,
  appliedFilters: [],
  freshnessWarnings: [],
  catalogVersion: 'catalog-1',
  evidence: {
    queryId: 'query-1',
    catalogVersion: 'catalog-1',
    definitionVersions: {},
    relationshipIds: [],
  },
};

function semantic(
  id: string,
  type: 'METRIC' | 'DIMENSION',
  displayName: string,
): SemanticSearchResult {
  return {
    id,
    type,
    datasetId: 'dataset-1',
    displayName,
    name: id,
    aliases: [],
    score: 1,
    matchReasons: [],
    version: 1,
    summary: {},
  };
}
