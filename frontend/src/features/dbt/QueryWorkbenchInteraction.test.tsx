import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import * as auth from '../../api/auth';
import { PublishedWorkbench } from './PublishedWorkbench';

afterEach(cleanup);
beforeAll(() => {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

function page(timeLabel = '下单时间', granularities = ['day', 'month'], includeDenied = false) {
  const api = {
    build: vi.fn().mockResolvedValue({
      catalogAvailable: true,
      buildId: 'A',
      repository: 'repo',
      branchName: 'feature/a',
      execution: {
        environmentId: '12345678-1234-4234-8234-000000000001',
        configVersion: 1,
        connection: {
          type: 'starrocks',
          instanceId: 'warehouse',
          host: 'localhost',
          port: 9030,
          path: null,
        },
        credentialRef: 'warehouse-test',
        catalog: 'default_catalog',
        schema: 'analytics',
        threads: 2,
        businessTimezone: 'Asia/Shanghai',
      },
    }),
    current: vi.fn().mockResolvedValue({ activeBuildId: 'A' }),
    catalog: vi.fn().mockResolvedValue([
      {
        unique_id: 'metric-id',
        resource_type: 'metric',
        name: 'revenue',
        label: '销售额',
      },
      ...(includeDenied
        ? [
            {
              unique_id: 'denied-metric',
              resource_type: 'metric',
              name: 'restricted',
              granted: false,
            },
          ]
        : []),
    ]),
    availableDimensions: vi.fn().mockResolvedValue({
      state: 'SUCCEEDED',
      buildId: 'A',
      dimensions: [
        {
          optionId: 'time-option',
          dimensionType: 'time',
          displayName: timeLabel,
          granularities,
          operators: [],
        },
        {
          optionId: 'region-option',
          dimensionType: 'categorical',
          displayName: '地区',
          granularities: [],
          operators: ['='],
        },
        ...(includeDenied
          ? [
              {
                optionId: 'denied-dimension',
                dimensionType: 'categorical',
                displayName: '受限维度',
                granted: false,
                granularities: [],
                operators: ['='],
              },
            ]
          : []),
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

it('默认按指标日期的日粒度分组，并使用服务选项身份升序排序', async () => {
  const api = page('metric_time');
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  expect(await screen.findByRole('button', { name: 'metric_time排序方向：升序' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'metric_time时间粒度' }).textContent).toContain('日');
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenCalledWith(
      'A',
      expect.objectContaining({
        groupBy: [{ optionId: 'time-option', grain: 'day' }],
        orderBy: [{ fieldId: 'time-option', direction: 'ASC' }],
      }),
    ),
  );
});

it('用户修改默认日期粒度和排序后，保留修改并允许移除', async () => {
  const api = page('metric_time');
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  fireEvent.click(await screen.findByRole('button', { name: 'metric_time排序方向：升序' }));
  fireEvent.click(screen.getByRole('button', { name: 'metric_time时间粒度' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: '月' }));
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenLastCalledWith(
      'A',
      expect.objectContaining({
        groupBy: [{ optionId: 'time-option', grain: 'month' }],
        orderBy: [{ fieldId: 'time-option', direction: 'DESC' }],
      }),
    ),
  );
  fireEvent.click(screen.getByRole('button', { name: '移除排序字段 metric_time' }));
  fireEvent.change(screen.getByLabelText('行数'), { target: { value: '100' } });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenLastCalledWith('A', expect.objectContaining({ orderBy: [] })),
  );
  fireEvent.click(screen.getByRole('button', { name: '移除分组维度 metric_time' }));
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenLastCalledWith(
      'A',
      expect.objectContaining({ groupBy: [], orderBy: [] }),
    ),
  );
});

it('不存在指标日期时不擅自添加其他时间维度或排序', async () => {
  const api = page();
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await screen.findByRole('button', { name: '下单时间' });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenCalledWith(
      'A',
      expect.objectContaining({ groupBy: [], orderBy: [] }),
    ),
  );
});

it('服务未声明日粒度时保留日期分组和升序，不提交不支持的粒度', async () => {
  const api = page('metric_time', ['month']);
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  await screen.findByRole('button', { name: 'metric_time排序方向：升序' });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(api.submit).toHaveBeenCalledWith(
      'A',
      expect.objectContaining({
        groupBy: [{ optionId: 'time-option' }],
        orderBy: [{ fieldId: 'time-option', direction: 'ASC' }],
      }),
    ),
  );
});

it('submits tag grain and ordered multi-field sorting using fixed resource identities', async () => {
  const api = page();
  fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
  expect(await screen.findByText('time')).toBeTruthy();
  expect(screen.getByText('categorical')).toBeTruthy();
  expect(api.resource).not.toHaveBeenCalled();
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
        metrics: ['revenue'],
        groupBy: [{ optionId: 'time-option', grain: 'month' }],
        orderBy: [
          { fieldId: 'time-option', direction: 'ASC' },
          { fieldId: 'revenue', direction: 'DESC' },
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
        orderBy: [{ fieldId: 'revenue', direction: 'DESC' }],
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
  await screen.findByText('time');
  expect(api.resource).not.toHaveBeenCalled();
  expect(screen.queryByText('VARCHAR')).toBeNull();
  expect(screen.getByText('categorical')).toBeTruthy();
  expect(screen.getByRole('button', { name: '查询' }).hasAttribute('disabled')).toBe(false);
});

it('未授权的指标和维度显示原因且无法加入查询', async () => {
  const api = page('metric_time', ['day'], true);
  const metric = await screen.findByRole('checkbox', { name: 'restricted' });
  expect(metric.hasAttribute('disabled')).toBe(true);
  expect(within(metric).getByText('无权限')).toBeTruthy();
  fireEvent.click(metric);
  expect(api.availableDimensions).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('checkbox', { name: '销售额' }));
  await screen.findByRole('button', { name: 'metric_time时间粒度' });
  const add = screen.getByRole('button', { name: '添加分组维度' });
  if (add.getAttribute('aria-expanded') !== 'true') fireEvent.click(add);
  const group = await screen.findByRole('region', { name: '选择分组维度' });
  const dimension = within(group).getByRole('button', { name: '受限维度' });
  expect(dimension.hasAttribute('disabled')).toBe(true);
  expect(within(dimension).getByText('无权限')).toBeTruthy();
  fireEvent.click(dimension);
  expect(dimension.getAttribute('aria-pressed')).toBe('false');
  expect(within(group).getByRole('button', { name: '地区' }).hasAttribute('disabled')).toBe(false);
});

it('旧版接口缺少 granted 时按只读账号的模型和维度授权禁用下拉项', async () => {
  const session = vi
    .spyOn(auth, 'getAuthSession')
    .mockReturnValue({ identity: { role: 'READ_ONLY' } } as auth.AuthSession);
  try {
    const api = page();
    api.catalog.mockResolvedValue([
      {
        unique_id: 'metric-id',
        resource_type: 'metric',
        name: 'revenue',
        label: '销售额',
        granted: true,
      },
      ...['orders', 'customers'].map((model) => ({
        unique_id: model,
        resource_type: 'dimension',
        name: 'region',
        semanticModelName: model,
        granted: model === 'customers',
      })),
    ]);
    api.availableDimensions.mockResolvedValue({
      buildId: 'A',
      dimensions: [
        {
          optionId: 't',
          name: 'metric_time',
          displayName: 'metric_time',
          granularities: ['day'],
          operators: [],
        },
        ...['orders', 'customers', 'unknown'].map((model) => ({
          optionId: model,
          name: 'region',
          displayName: model,
          semanticModelName: model,
          granularities: [],
          operators: ['='],
        })),
      ],
    });
    fireEvent.click(await screen.findByRole('checkbox', { name: '销售额' }));
    await screen.findByRole('button', { name: 'metric_time时间粒度' });
    const add = screen.getByRole('button', { name: '添加分组维度' });
    if (add.getAttribute('aria-expanded') !== 'true') fireEvent.click(add);
    const menu = within(await screen.findByRole('region', { name: '选择分组维度' }));
    for (const model of ['orders', 'unknown']) {
      const option = menu.getByRole('button', { name: model });
      expect(option.hasAttribute('disabled')).toBe(true);
      expect(within(option).getByText('无权限')).toBeTruthy();
      fireEvent.click(option);
      expect(option.getAttribute('aria-pressed')).toBe('false');
    }
    expect(menu.getByRole('button', { name: 'customers' }).hasAttribute('disabled')).toBe(false);
    expect(menu.getByRole('button', { name: 'metric_time' }).hasAttribute('disabled')).toBe(false);
  } finally {
    session.mockRestore();
  }
});
