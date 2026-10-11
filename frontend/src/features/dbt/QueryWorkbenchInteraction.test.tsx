import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { PublishedWorkbench } from './PublishedWorkbench';

afterEach(cleanup);
beforeAll(() => {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

function page() {
  const api = {
    build: vi.fn().mockResolvedValue({
      buildId: 'A',
      repository: 'repo',
      branchName: 'feature/a',
      environment: 'PREVIEW',
      queryAvailable: true,
    }),
    current: vi.fn().mockResolvedValue({ activeBuildId: 'A' }),
    catalog: vi.fn().mockResolvedValue({
      buildId: 'A',
      nextCursor: null,
      items: [{ resourceId: 'metric-id', name: 'revenue', displayName: '销售额' }],
    }),
    availableDimensions: vi.fn().mockResolvedValue({
      state: 'SUCCEEDED',
      buildId: 'A',
      dimensions: [
        {
          optionId: 'time-option',
          resourceId: 'time-resource',
          displayName: '下单时间',
          granularities: ['day', 'month'],
          operators: [],
        },
        {
          optionId: 'region-option',
          resourceId: 'region-resource',
          displayName: '地区',
          granularities: [],
          operators: ['='],
        },
      ],
    }),
    resource: vi.fn().mockImplementation((release, id) =>
      Promise.resolve({
        buildId: release,
        resourceId: id,
        kind: 'DIMENSION',
        name: id,
        capabilities: [],
        attributes: { dataType: id === 'time-resource' ? 'DATE' : 'VARCHAR(64)' },
        nativeDetails: {},
      }),
    ),
    submit: vi
      .fn()
      .mockResolvedValue({ queryId: 'Q', buildId: 'A', state: 'SUCCEEDED', columns: [], rows: [] }),
  };
  render(
    <MemoryRouter initialEntries={['/dbt/projects/p/query?buildId=A']}>
      <Routes>
        <Route
          path="/dbt/projects/:projectId/query"
          element={<PublishedWorkbench api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  return api;
}

async function pickOrder(name: string) {
  fireEvent.click(screen.getByRole('button', { name: '添加排序字段' }));
  const menu = await screen.findByRole('region', { name: '选择排序字段' });
  fireEvent.click(within(menu).getByRole('button', { name }));
}

it('submits tag grain and ordered multi-field sorting using fixed resource identities', async () => {
  const api = page();
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  expect(await screen.findByText('DATE')).toBeTruthy();
  expect(screen.getByText('VARCHAR(64)')).toBeTruthy();
  expect(api.resource).toHaveBeenCalledWith('A', 'time-resource');
  fireEvent.click(
    within(screen.getByRole('group', { name: '分组维度' })).getByRole('button', {
      name: '下单时间',
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '下单时间时间粒度' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '月' }));
  await pickOrder('下单时间');
  await pickOrder('销售额');
  fireEvent.click(screen.getByRole('button', { name: '销售额排序方向：升序' }));
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenCalledWith(
      'A',
      expect.objectContaining({
        metricResourceIds: ['metric-id'],
        groupBy: [{ optionId: 'time-option', grain: 'month' }],
        orderBy: [
          { fieldId: 'time-option', direction: 'ASC' },
          { fieldId: 'metric-id', direction: 'DESC' },
        ],
      }),
    ),
  );
  fireEvent.click(screen.getByRole('button', { name: '移除分组维度 下单时间' }));
  await waitFor(() =>
    expect(screen.queryByRole('button', { name: '下单时间排序方向：升序' })).toBeNull(),
  );
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenLastCalledWith(
      'A',
      expect.objectContaining({
        groupBy: [],
        orderBy: [{ fieldId: 'metric-id', direction: 'DESC' }],
      }),
    ),
  );
});

it('does not display types from mismatched resource metadata and still permits queries', async () => {
  const api = page();
  api.resource.mockResolvedValue({
    buildId: 'OLD',
    resourceId: 'wrong',
    attributes: { dataType: 'VARCHAR' },
  });
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await waitFor(() => expect(api.resource).toHaveBeenCalled());
  expect(screen.queryByText('VARCHAR')).toBeNull();
  expect(screen.getByText('类型待确认')).toBeTruthy();
  expect(screen.getByRole('button', { name: '查询' }).hasAttribute('disabled')).toBe(false);
});
