import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { EnvironmentsPage } from './EnvironmentsPage';
import { EnvironmentDetailPage } from './EnvironmentDetailPage';

afterEach(cleanup);
const ID = '11111111-1111-4111-8111-111111111111';
const BUILD = '22222222-2222-4222-8222-222222222222';
const environment = {
  environmentId: ID,
  name: '测试环境',
  configVersion: 1,
  execution: {
    environmentId: ID,
    configVersion: 1,
    connection: { type: 'starrocks', instanceId: 'warehouse', host: 'localhost', port: 9030 },
    credentialRef: 'test',
    catalog: 'default_catalog',
    schema: 'test_env',
    threads: 2,
    businessTimezone: 'Asia/Shanghai',
  },
};

it('环境配置没有仓库或分支字段', async () => {
  const api = { list: vi.fn().mockResolvedValue({ items: [] }) };
  render(
    <MemoryRouter>
      <EnvironmentsPage api={api as never} />
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('button', { name: '新建环境' }));
  expect(screen.getByLabelText('Catalog')).toBeInTheDocument();
  expect(screen.getByLabelText('Schema')).toBeInTheDocument();
  expect(screen.queryByLabelText(/仓库|分支/)).not.toBeInTheDocument();
});

function mount(api: unknown) {
  render(
    <MemoryRouter initialEntries={[`/dbt/environments/${ID}`]}>
      <Routes>
        <Route
          path="/dbt/environments/:environmentId"
          element={<EnvironmentDetailPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

it('完整构建历史展示失败和未部署版本，查询使用固定 build', async () => {
  const api = {
    get: vi.fn().mockResolvedValue(environment),
    builds: vi.fn().mockResolvedValue({
      items: [
        {
          buildId: BUILD,
          repository: 'repo/a',
          branchName: 'feature/test',
          buildStatus: 'SUCCEEDED',
          catalogAvailable: true,
          queryAvailable: true,
          execution: environment.execution,
        },
        {
          buildId: 'failed-build',
          repository: 'repo/b',
          branchName: 'main',
          buildStatus: 'FAILED',
          catalogAvailable: false,
          queryAvailable: false,
          execution: environment.execution,
        },
      ],
      nextCursor: null,
    }),
  };
  mount(api);
  expect(await screen.findByText('FAILED')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查询此构建' })).toHaveAttribute(
    'href',
    `/dbt/builds/${BUILD}/query`,
  );
  expect(api.builds.mock.calls[0][0]).toBe(ID);
});

it('每次构建独立指定仓库分支，超时重试保留提交键', async () => {
  const api = {
    get: vi.fn().mockResolvedValue(environment),
    builds: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    submitBuild: vi.fn().mockRejectedValue(new Error('响应未知')),
  };
  mount(api);
  await screen.findByText('测试环境');
  fireEvent.change(screen.getByLabelText('Git 仓库地址'), {
    target: { value: 'https://example.test/metrics.git' },
  });
  fireEvent.change(screen.getByLabelText('构建分支'), { target: { value: 'feature/isolated' } });
  fireEvent.click(screen.getByRole('button', { name: '发起构建' }));
  await screen.findByText('响应未知');
  fireEvent.click(screen.getByRole('button', { name: '刷新历史' }));
  await waitFor(() => expect(api.builds).toHaveBeenCalledTimes(2));
  await screen.findByText('测试环境');
  fireEvent.click(screen.getByRole('button', { name: '重试本次提交' }));
  await waitFor(() => expect(api.submitBuild).toHaveBeenCalledTimes(2));
  expect(api.submitBuild.mock.calls[0]).toEqual(api.submitBuild.mock.calls[1]);
  expect(api.submitBuild.mock.calls[0][1]).toMatchObject({
    branchName: 'feature/isolated',
    expectedConfigVersion: 1,
  });
});

it('切换环境忽略晚返回的旧历史', async () => {
  let resolveOld: (value: unknown) => void = () => {};
  const old = new Promise((resolve) => {
    resolveOld = resolve;
  });
  const other = { ...environment, environmentId: BUILD, name: '另一环境' };
  const api = {
    get: vi
      .fn()
      .mockImplementation((id: string) => Promise.resolve(id === ID ? environment : other)),
    builds: vi
      .fn()
      .mockImplementation((id: string) =>
        id === ID ? old : Promise.resolve({ items: [], nextCursor: null }),
      ),
  };
  render(
    <MemoryRouter initialEntries={[`/dbt/environments/${ID}`]}>
      <Link to={`/dbt/environments/${BUILD}`}>切换环境</Link>
      <Routes>
        <Route
          path="/dbt/environments/:environmentId"
          element={<EnvironmentDetailPage api={api as never} />}
        />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(screen.getByRole('link', { name: '切换环境' }));
  await screen.findByText('另一环境');
  await act(async () =>
    resolveOld({ items: [{ buildId: 'obsolete-build', buildStatus: 'FAILED' }], nextCursor: null }),
  );
  expect(screen.queryByText('obsolete-build')).not.toBeInTheDocument();
  expect(screen.queryByText('测试环境')).not.toBeInTheDocument();
});

it('配置版本冲突保留编辑内容并显示服务错误', async () => {
  const api = {
    get: vi.fn().mockResolvedValue(environment),
    builds: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    update: vi.fn().mockRejectedValue(new Error('环境配置版本冲突')),
  };
  mount(api);
  fireEvent.click(await screen.findByRole('button', { name: '编辑配置' }));
  fireEvent.change(screen.getByLabelText('Schema'), { target: { value: 'changed_schema' } });
  fireEvent.click(screen.getByRole('button', { name: '保存环境' }));
  await screen.findByText('环境配置版本冲突');
  expect(screen.getByLabelText('Schema')).toHaveValue('changed_schema');
  expect(api.update).toHaveBeenCalledWith(
    ID,
    1,
    expect.objectContaining({ schema: 'changed_schema' }),
  );
});
