import '@testing-library/jest-dom/vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { EnvironmentsPage } from './EnvironmentsPage';
import { EnvironmentDetailPage } from './EnvironmentDetailPage';

import { clearAuthSession, saveAuthSession } from '../../api/auth';

beforeEach(() =>
  saveAuthSession({
    accessToken: 'fixture',
    tokenType: 'Bearer',
    expiresIn: 3600,
    identity: {
      identityId: 'manager',
      subject: 'manager',
      displayName: 'Manager',
      authVersion: 1,
      tenant_id: 'tenant',
      role: 'TENANT_MANAGER',
    },
  }),
);
afterEach(() => {
  cleanup();
  clearAuthSession();
});
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
          element={
            <EnvironmentDetailPage
              api={
                {
                  repositoryBranches: vi.fn().mockResolvedValue({
                    repository: 'https://example.test/metrics.git',
                    branches: ['main', 'feature/isolated'],
                  }),
                  ...(api as object),
                } as never
              }
            />
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

function mountLogs(logs: unknown) {
  mount({
    get: vi.fn().mockResolvedValue(environment),
    builds: vi.fn().mockResolvedValue({
      items: [BUILD, 'other-build'].map((buildId) => ({
        buildId,
        repository: 'repo/a',
        branchName: 'main',
        commitSha: 'abc123',
        buildStatus: 'SUCCEEDED',
        phase: 'COMPLETE',
        createdAt: '2026-10-15T00:00:00Z',
        execution: environment.execution,
        catalogAvailable: true,
        initialDeployment: null,
      })),
      nextCursor: null,
    }),
    getBuild: vi.fn().mockResolvedValue({ buildStatus: 'SUCCEEDED' }),
    logs,
  });
}

it('历史记录直接展示四项身份信息、不展示配置版本，日志支持就地收起', async () => {
  const showLogsLabel = '查看日志';
  const hideLogsLabel = '收起日志';
  const recordSelector = 'article';
  const detailsSelector = 'details';
  const configurationLabel = '配置版本';
  const labels = ['构建 ID', '仓库', '完整提交', '执行目标'];
  const logMessage = '验证日志';
  mountLogs(
    vi.fn().mockResolvedValue({
      items: [{ logId: '1', message: logMessage }],
      nextCursor: 'end',
      hasMore: false,
    }),
  );
  const identity = await screen.findByText(BUILD);
  const record = identity.closest(recordSelector)!;
  expect(record.querySelector(detailsSelector)).toBeNull();
  expect(identity).toBeVisible();
  for (const label of labels) expect(within(record).getByText(label)).toBeVisible();
  expect(within(record).queryByText(configurationLabel)).not.toBeInTheDocument();
  fireEvent.click(screen.getAllByRole('button', { name: showLogsLabel })[0]);
  expect(await screen.findByText(logMessage)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: hideLogsLabel }));
  expect(screen.queryByRole('log')).not.toBeInTheDocument();
});

it('历史重建使用该记录的仓库分支和当前配置，失败重试沿用提交键', async () => {
  const oldRepository = 'https://example.test/previous.git';
  const oldBranch = 'release/previous';
  const failure = '响应未知';
  const rebuildLabel = '重新构建';
  const retryLabel = '重试提交';
  const refreshLabel = '刷新历史';
  const api = {
    get: vi.fn().mockResolvedValue(environment),
    builds: vi.fn().mockResolvedValue({
      items: [
        {
          buildId: BUILD,
          repository: oldRepository,
          branchName: oldBranch,
          commitSha: 'old-fixed-sha',
          buildStatus: 'SUCCEEDED',
          execution: { ...environment.execution, configVersion: 0 },
        },
      ],
      nextCursor: null,
    }),
    submitBuild: vi
      .fn()
      .mockRejectedValueOnce(new Error(failure))
      .mockResolvedValue({ buildId: BUILD }),
  };
  mount(api);
  fireEvent.click(await screen.findByRole('button', { name: rebuildLabel }));
  await screen.findByText(failure);
  expect(api.submitBuild.mock.calls[0]).toEqual([
    ID,
    {
      repository: oldRepository,
      branchName: oldBranch,
      expectedConfigVersion: environment.configVersion,
      deploymentPolicy: 'ON_SUCCESS',
      idempotencyKey: expect.any(String),
    },
  ]);
  fireEvent.click(screen.getByRole('button', { name: refreshLabel }));
  await waitFor(() => expect(api.builds).toHaveBeenCalledTimes(2));
  fireEvent.click(await screen.findByRole('button', { name: retryLabel }));
  await waitFor(() => expect(api.submitBuild).toHaveBeenCalledTimes(2));
  expect(api.submitBuild.mock.calls[1]).toEqual(api.submitBuild.mock.calls[0]);
  await waitFor(() => expect(api.builds).toHaveBeenCalledTimes(3));
});

it('点击日志立即在当前构建下显示加载反馈，空结果也有提示', async () => {
  let resolveLogs!: (value: {
    items: { logId: string; message: string }[];
    nextCursor: string;
    hasMore: boolean;
  }) => void;
  const logs = vi.fn().mockImplementation(() => new Promise((resolve) => (resolveLogs = resolve)));
  mountLogs(logs);
  const buttons = await screen.findAllByRole('button', { name: '查看日志' });
  fireEvent.click(buttons[0]);
  const build = within(buttons[0].closest('article')!);
  expect(build.getByRole('status')).toHaveTextContent('正在加载日志');
  await waitFor(() => expect(logs).toHaveBeenCalledWith(BUILD, undefined, expect.any(AbortSignal)));
  await act(async () => resolveLogs({ items: [], nextCursor: 'empty', hasMore: false }));
  expect(build.getByText('暂无日志')).toBeInTheDocument();
  expect(build.queryByText('正在加载日志')).not.toBeInTheDocument();
});

it('日志读取失败在当前构建下显示错误，再次点击可重新读取', async () => {
  const logs = vi
    .fn()
    .mockRejectedValueOnce(new Error('日志服务暂不可用'))
    .mockResolvedValueOnce({
      items: [
        { logId: '1', message: '开始构建' },
        { logId: '2', message: '构建完成' },
      ],
      nextCursor: 'done',
      hasMore: false,
    });
  mountLogs(logs);
  const buttons = await screen.findAllByRole('button', { name: '查看日志' });
  const build = within(buttons[0].closest('article')!);
  fireEvent.click(buttons[0]);
  expect(await build.findByRole('alert')).toHaveTextContent('日志服务暂不可用');
  fireEvent.click(build.getByRole('button', { name: '刷新日志' }));
  expect(await build.findByText('开始构建')).toBeInTheDocument();
  expect(build.getByText('构建完成')).toBeInTheDocument();
  expect(build.queryByRole('alert')).not.toBeInTheDocument();
});

it('切换查看的构建后忽略晚返回的旧日志', async () => {
  let resolveOld!: (value: {
    items: { logId: string; message: string }[];
    nextCursor: string;
    hasMore: boolean;
  }) => void;
  const logs = vi
    .fn()
    .mockImplementationOnce(() => new Promise((resolve) => (resolveOld = resolve)))
    .mockResolvedValueOnce({
      items: [{ logId: '2', message: '当前构建日志' }],
      nextCursor: 'new',
      hasMore: false,
    });
  mountLogs(logs);
  const buttons = await screen.findAllByRole('button', { name: '查看日志' });
  fireEvent.click(buttons[0]);
  await waitFor(() => expect(logs).toHaveBeenCalledTimes(1));
  fireEvent.click(buttons[1]);
  const current = within(buttons[1].closest('article')!);
  expect(await current.findByText('当前构建日志')).toBeInTheDocument();
  await act(async () =>
    resolveOld({
      items: [{ logId: '1', message: '旧构建日志' }],
      nextCursor: 'old',
      hasMore: false,
    }),
  );
  expect(current.getByText('当前构建日志')).toBeInTheDocument();
  expect(screen.queryByText('旧构建日志')).not.toBeInTheDocument();
});

it('仅可读取产物的构建提供目录入口', async () => {
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
          execution: environment.execution,
        },
        {
          buildId: 'failed-build',
          repository: 'repo/b',
          branchName: 'main',
          buildStatus: 'FAILED',
          catalogAvailable: false,
          execution: environment.execution,
        },
      ],
      nextCursor: null,
    }),
  };
  mount(api);
  expect(await screen.findByText('FAILED')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: `进入构建 ${BUILD}` })).toHaveAttribute(
    'href',
    `/dbt/builds/${BUILD}/catalog`,
  );
  expect(screen.queryByRole('link', { name: '进入构建 failed-build' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: '查看目录' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: '查询此构建' })).not.toBeInTheDocument();
  expect(api.builds.mock.calls[0][0]).toBe(ID);
});

it('每次构建选择绑定仓库的分支，超时重试保留提交键', async () => {
  const api = {
    get: vi.fn().mockResolvedValue(environment),
    builds: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    submitBuild: vi.fn().mockRejectedValue(new Error('响应未知')),
  };
  mount(api);
  await screen.findByText('测试环境');
  await screen.findByRole('option', { name: 'feature/isolated' });
  expect(screen.getByLabelText('Git 仓库地址')).toHaveValue('https://example.test/metrics.git');
  expect(screen.getByLabelText('Git 仓库地址')).toHaveAttribute('readonly');
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
    repository: 'https://example.test/metrics.git',
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
    repositoryBranches: vi
      .fn()
      .mockResolvedValue({ repository: 'https://example.test/metrics.git', branches: ['main'] }),
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
