import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { PublishedWorkbench } from './PublishedWorkbench';

afterEach(cleanup);

it.each([false, true])(
  'recovers pending submission after same-version refresh (failure=%s)',
  async (refreshFails) => {
    let complete!: (value: unknown) => void;
    const pending = new Promise((resolve) => {
      complete = resolve;
    });
    const api = {
      build: vi.fn().mockResolvedValue({
        buildId: 'A',
        repository: 'repo',
        branchName: 'feature/a',
        environment: 'PREVIEW',
        queryAvailable: true,
      }),
      current: refreshFails
        ? vi.fn().mockRejectedValue(new Error('offline'))
        : vi.fn().mockResolvedValue({ activeBuildId: 'A' }),
      catalog: vi.fn().mockResolvedValue({
        buildId: 'A',
        nextCursor: null,
        items: [{ resourceId: 'M', kind: 'METRIC', displayName: '指标 M' }],
      }),
      availableDimensions: vi
        .fn()
        .mockResolvedValue({ buildId: 'A', state: 'SUCCEEDED', dimensions: [] }),
      submit: vi.fn().mockReturnValue(pending),
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
    fireEvent.click(await screen.findByRole('checkbox', { name: '指标 M' }));
    await waitFor(() =>
      expect((screen.getByRole('button', { name: '查询' }) as HTMLButtonElement).disabled).toBe(
        false,
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    fireEvent.click(screen.getByRole('button', { name: '刷新已发布版本' }));
    await waitFor(() => expect(api.current).toHaveBeenCalled());
    await act(async () => {
      complete({ queryId: 'Q', buildId: 'A', state: 'SUCCEEDED', columns: [], rows: [] });
    });
    await screen.findByText('版本 A');
    await waitFor(() =>
      expect((screen.getByRole('button', { name: '查询' }) as HTMLButtonElement).disabled).toBe(
        false,
      ),
    );
  },
);

it('queries with platform identities and refreshes without requesting publication', async () => {
  const api = {
    build: vi.fn().mockResolvedValue({
      buildId: 'A',
      repository: 'repo',
      branchName: 'feature/a',
      environment: 'PREVIEW',
      queryAvailable: true,
    }),
    current: vi.fn().mockResolvedValue({ activeBuildId: 'A' }),
    publication: vi.fn().mockResolvedValue({ projectId: 'p', activePublication: { buildId: 'A' } }),
    catalog: vi.fn().mockResolvedValue({
      buildId: 'A',
      nextCursor: null,
      items: [
        {
          resourceId: 'metric.p.revenue',
          kind: 'METRIC',
          name: 'revenue',
          displayName: '销售额',
          capabilities: ['QUERY'],
          attributes: {},
          nativeDetails: {},
        },
      ],
    }),
    availableDimensions: vi.fn().mockResolvedValue({
      state: 'SUCCEEDED',
      buildId: 'A',
      dimensions: [
        {
          optionId: 'opaque-region',
          displayName: '地区',
          granularities: [],
          operators: ['='],
          resourceId: null,
        },
      ],
    }),
    submit: vi
      .fn()
      .mockResolvedValue({ queryId: 'Q', buildId: 'A', state: 'SUCCEEDED', columns: [], rows: [] }),
    query: vi
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
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  fireEvent.click(
    await within(screen.getByRole('group', { name: '分组维度' })).findByRole('button', {
      name: '地区',
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenCalledWith(
      'A',
      expect.objectContaining({
        metricResourceIds: ['metric.p.revenue'],
        groupBy: [{ optionId: 'opaque-region' }],
      }),
    ),
  );
});
