import { getAuthSession } from '../../api/auth';
import { Alert, Button, Card, Empty, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  environmentApi,
  type EnvironmentApi,
  type EnvironmentBuildPage,
  type BuildCommand,
  type EnvironmentBuild,
  type EnvironmentInput,
  type ExecutionEnvironment,
} from '../../api/environmentApi';
import { EnvironmentForm } from './EnvironmentForm';
import { ExecutionLogs } from './ExecutionLogs';
import { BuildSubmissionForm } from './BuildSubmissionForm';
import './environment-detail.css';
import {
  ArrowLeftOutlined,
  ReloadOutlined,
  SettingOutlined,
  BuildOutlined,
  BranchesOutlined,
} from '@ant-design/icons';

const COPY = {
  cancel: '收起配置',
  historyHint: '查看固定版本的目录与查询。重新构建使用原仓库、原分支的最新提交与当前环境配置。',
  target: '执行目标',
  config: '配置版本',
  timezone: '业务时区',
  buildId: '构建 ID',
  repository: '仓库',
  commit: '完整提交',
  pendingCommit: '等待固定提交',
  showLogs: '查看日志',
  hideLogs: '收起日志',
  rebuild: '重新构建',
  retry: '重试提交',
  rebuildError: '提交结果未知，请重试本次提交',
  created: '提交时间',
  noTime: '时间未提供',
  enterBuild: '进入构建',
};
const DETAIL_STYLE = {
  page: 'environment-page environment-detail',
  header: 'environment-detail-header',
  title: 'environment-detail-title',
  row: 'build-record-main',
  summary: 'build-record-summary',
  source: 'build-record-source',
  status: 'build-record-status',
  time: 'build-record-time',
  actions: 'build-record-actions',
  details: 'build-record-details',
  rebuild: 'build-record-rebuild',
  entry: 'build-record-entry',
};
const BUILD_ROOT = '/dbt/builds/';
const CATALOG_PATH = '/catalog';
const DEPLOY_ON_SUCCESS = 'ON_SUCCESS';
const DATE_LOCALE = 'zh-CN';
const DATE_OPTIONS: Intl.DateTimeFormatOptions = {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
};
const STATUS_COLORS: Record<string, string> = {
  SUCCEEDED: 'success',
  FAILED: 'error',
  RUNNING: 'processing',
  QUEUED: 'default',
  CANCELLED: 'default',
  OUTCOME_UNKNOWN: 'warning',
};

export function EnvironmentDetailPage({ api = environmentApi }: { api?: EnvironmentApi }) {
  const { environmentId = '' } = useParams();
  // 环境切换重建状态，历史刷新保留尚未确定结果的提交键。
  return <SelectedEnvironment key={environmentId} api={api} environmentId={environmentId} />;
}

function RebuildAction({
  build,
  environment,
  api,
  onSubmitted,
  commands,
}: {
  build: EnvironmentBuild;
  environment: ExecutionEnvironment;
  api: EnvironmentApi;
  onSubmitted: () => void;
  commands: Map<string, BuildCommand>;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);
  async function submit() {
    if (submitting.current) return;
    submitting.current = true;
    // 重建跟随历史仓库与分支的最新提交；响应未知时保留完整命令和幂等键。
    const command = commands.get(build.buildId) ?? {
      repository: build.repository,
      branchName: build.branchName,
      expectedConfigVersion: environment.configVersion,
      deploymentPolicy: DEPLOY_ON_SUCCESS,
      idempotencyKey: crypto.randomUUID(),
    };
    commands.set(build.buildId, command);
    setBusy(true);
    setError('');
    try {
      await api.submitBuild(environment.environmentId, command);
      commands.delete(build.buildId);
      onSubmitted();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : COPY.rebuildError);
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }
  return (
    <div className={DETAIL_STYLE.rebuild}>
      <Button icon={<ReloadOutlined aria-hidden />} loading={busy} onClick={() => void submit()}>
        {commands.has(build.buildId) && !busy ? COPY.retry : COPY.rebuild}
      </Button>
      {error && <Alert type="error" title={error} />}
    </div>
  );
}
function SelectedEnvironment({
  api,
  environmentId,
}: {
  api: EnvironmentApi;
  environmentId: string;
}) {
  const manager = getAuthSession()?.identity.role === 'TENANT_MANAGER';
  const [params, setParams] = useSearchParams();
  const [environment, setEnvironment] = useState<ExecutionEnvironment | null>(null);
  const [page, setPage] = useState<EnvironmentBuildPage>({ items: [], nextCursor: null });
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [logBuild, setLogBuild] = useState<string | null>(null);
  const generation = useRef(0);
  const paging = useRef(false);
  // 历史筛选和刷新会卸载记录，未确认提交的幂等键因此由环境页保留。
  const rebuildCommands = useRef(new Map<string, BuildCommand>());
  const repository = params.get('repository') || undefined;
  const branchName = params.get('branchName') || undefined;
  const buildStatus = params.get('buildStatus') || undefined;
  useEffect(() => {
    const turn = ++generation.current;
    setPage({ items: [], nextCursor: null });
    setError('');
    setLogBuild(null);
    setEditing(false);
    paging.current = false;
    setBusy(false);
    Promise.all([
      api.get(environmentId),
      api.builds(environmentId, { repository, branchName, buildStatus }),
    ])
      .then(([selected, history]) => {
        if (turn === generation.current) {
          setEnvironment(selected);
          setPage(history);
        }
      })
      .catch((reason) => {
        if (turn === generation.current) setError(String(reason.message));
      });
    return () => {
      generation.current++;
    };
  }, [api, environmentId, repository, branchName, buildStatus, refresh]);
  async function save(input: EnvironmentInput) {
    if (!environment) return;
    const turn = generation.current;
    setBusy(true);
    try {
      const value = await api.update(environmentId, environment.configVersion, input);
      if (turn === generation.current) {
        setEnvironment(value);
        setEditing(false);
      }
    } catch (reason) {
      if (turn === generation.current)
        setError(reason instanceof Error ? reason.message : '配置保存失败');
    } finally {
      if (turn === generation.current) setBusy(false);
    }
  }
  async function more() {
    if (paging.current || !page.nextCursor) return;
    paging.current = true;
    setBusy(true);
    const turn = generation.current;
    try {
      const next = await api.builds(environmentId, {
        repository,
        branchName,
        buildStatus,
        cursor: page.nextCursor,
      });
      if (turn === generation.current)
        setPage((previous) => ({ ...next, items: [...previous.items, ...next.items] }));
    } catch (reason) {
      if (turn === generation.current)
        setError(reason instanceof Error ? reason.message : '历史读取失败');
    } finally {
      if (turn === generation.current) {
        paging.current = false;
        setBusy(false);
      }
    }
  }
  return (
    <section className={DETAIL_STYLE.page}>
      <Link className="back-link" to="/dbt/environments">
        <ArrowLeftOutlined aria-hidden /> 返回环境列表
      </Link>
      {error && <Alert type="error" title={error} />}
      {environment && (
        <>
          <header className={DETAIL_STYLE.header}>
            <div className={DETAIL_STYLE.title}>
              <div>
                <h2>{environment.name}</h2>
                <p>{environment.execution.connection.instanceId}</p>
              </div>
              {manager && (
                <div className="environment-management-actions">
                  <Link to={`/dbt/environments/${environmentId}/ontology`}>本体建模</Link>
                  <Button
                    icon={<SettingOutlined aria-hidden />}
                    onClick={() => setEditing(!editing)}
                  >
                    {editing ? COPY.cancel : '编辑配置'}
                  </Button>
                </div>
              )}
            </div>
            <dl className="environment-summary">
              <div>
                <dt>{COPY.target}</dt>
                <dd>
                  {environment.execution.catalog} / {environment.execution.schema}
                </dd>
              </div>
              <div>
                <dt>{COPY.config}</dt>
                <dd>v{environment.configVersion}</dd>
              </div>
              <div>
                <dt>{COPY.timezone}</dt>
                <dd>{environment.execution.businessTimezone}</dd>
              </div>
            </dl>
          </header>
          {manager && editing && (
            <Card className="environment-editor" title="环境配置">
              <EnvironmentForm
                key={environment.configVersion}
                initial={{
                  name: environment.name,
                  connection: environment.execution.connection,
                  credentialRef: environment.execution.credentialRef,
                  catalog: environment.execution.catalog,
                  schema: environment.execution.schema,
                  threads: environment.execution.threads,
                  businessTimezone: environment.execution.businessTimezone,
                }}
                busy={busy}
                onSave={(input) => void save(input)}
              />
            </Card>
          )}
          {manager && (
            <Card
              className="build-submission-panel"
              title={
                <span>
                  <BuildOutlined aria-hidden /> 发起构建
                </span>
              }
            >
              <BuildSubmissionForm
                key={environmentId}
                environment={environment}
                api={api}
                onSubmitted={() => setRefresh((value) => value + 1)}
              />
            </Card>
          )}
        </>
      )}
      <Card
        className="build-history-panel"
        title="构建历史"
        extra={
          <Button
            icon={<ReloadOutlined aria-hidden />}
            onClick={() => setRefresh((value) => value + 1)}
          >
            刷新历史
          </Button>
        }
      >
        <p className="section-description">
          {manager ? COPY.historyHint : '选择构建版本，查看资源目录并查询已授权的数据。'}
        </p>
        <form
          className="deployment-filter"
          onSubmit={(event) => {
            event.preventDefault();
            const form = new FormData(event.currentTarget);
            const next = new URLSearchParams();
            form.forEach((value, key) => {
              if (String(value).trim()) next.set(key, String(value).trim());
            });
            setParams(next);
          }}
        >
          <label>
            仓库
            <input
              aria-label="筛选仓库"
              name="repository"
              placeholder="全部仓库"
              defaultValue={repository}
            />
          </label>
          <label>
            分支
            <input
              aria-label="筛选分支"
              name="branchName"
              placeholder="全部分支"
              defaultValue={branchName}
            />
          </label>
          <label>
            状态
            <select aria-label="筛选状态" name="buildStatus" defaultValue={buildStatus ?? ''}>
              <option value="">全部状态</option>
              {['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'OUTCOME_UNKNOWN'].map(
                (status) => (
                  <option key={status}>{status}</option>
                ),
              )}
            </select>
          </label>
          <Button htmlType="submit">筛选</Button>
        </form>
        {!page.items.length && <Empty description="暂无构建记录" />}
        {page.items.map((build) => (
          <article className="environment-build" key={build.buildId}>
            <div className={DETAIL_STYLE.summary}>
              <div className={DETAIL_STYLE.row}>
                <div className={DETAIL_STYLE.source}>
                  <strong>
                    {build.catalogAvailable ? (
                      <Link
                        className={DETAIL_STYLE.entry}
                        aria-label={`${COPY.enterBuild} ${build.buildId}`}
                        to={`${BUILD_ROOT}${build.buildId}${CATALOG_PATH}`}
                      >
                        <BranchesOutlined aria-hidden /> {build.branchName}
                      </Link>
                    ) : (
                      build.branchName
                    )}
                  </strong>
                  <code title={build.commitSha ?? undefined}>
                    {build.commitSha?.slice(0, 8) ?? COPY.pendingCommit}
                  </code>
                </div>
                <div className={DETAIL_STYLE.status}>
                  <Tag color={STATUS_COLORS[build.buildStatus]}>{build.buildStatus}</Tag>
                  {build.initialDeployment && <Tag>{build.initialDeployment.deploymentStatus}</Tag>}
                </div>
                <div className={DETAIL_STYLE.time}>
                  <span>{COPY.created}</span>
                  <time dateTime={build.createdAt} title={build.createdAt}>
                    {build.createdAt
                      ? new Date(build.createdAt).toLocaleString(DATE_LOCALE, DATE_OPTIONS)
                      : COPY.noTime}
                  </time>
                </div>
                {manager && environment && (
                  <RebuildAction
                    build={build}
                    environment={environment}
                    api={api}
                    commands={rebuildCommands.current}
                    onSubmitted={() => setRefresh((value) => value + 1)}
                  />
                )}
              </div>
              {manager && (
                <div className={DETAIL_STYLE.actions}>
                  <Button
                    type="link"
                    aria-expanded={logBuild === build.buildId}
                    onClick={() =>
                      setLogBuild((current) => (current === build.buildId ? null : build.buildId))
                    }
                  >
                    {logBuild === build.buildId ? COPY.hideLogs : COPY.showLogs}
                  </Button>
                </div>
              )}
            </div>
            <div className={DETAIL_STYLE.details}>
              <dl>
                <div>
                  <dt>{COPY.buildId}</dt>
                  <dd>
                    <code>{build.buildId}</code>
                  </dd>
                </div>
                <div>
                  <dt>{COPY.repository}</dt>
                  <dd>{build.repository}</dd>
                </div>
                <div>
                  <dt>{COPY.commit}</dt>
                  <dd>
                    <code>{build.commitSha ?? COPY.pendingCommit}</code>
                  </dd>
                </div>
                <div>
                  <dt>{COPY.target}</dt>
                  <dd>
                    {build.execution?.catalog} / {build.execution?.schema}
                  </dd>
                </div>
              </dl>
            </div>
            {logBuild === build.buildId && (
              <ExecutionLogs key={logBuild} buildId={logBuild} api={api} />
            )}
          </article>
        ))}
        {page.nextCursor && (
          <Button loading={busy} onClick={() => void more()}>
            加载更多构建
          </Button>
        )}
      </Card>
    </section>
  );
}
