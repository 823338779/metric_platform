import { Alert, Button, Card, Empty, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  environmentApi,
  type EnvironmentApi,
  type EnvironmentBuildPage,
  type EnvironmentInput,
  type ExecutionEnvironment,
} from '../../api/environmentApi';
import { EnvironmentForm } from './EnvironmentForm';
import { BuildSubmissionForm } from './BuildSubmissionForm';

export function EnvironmentDetailPage({ api = environmentApi }: { api?: EnvironmentApi }) {
  const { environmentId = '' } = useParams();
  // 环境切换重建状态，历史刷新保留尚未确定结果的提交键。
  return <SelectedEnvironment key={environmentId} api={api} environmentId={environmentId} />;
}
function SelectedEnvironment({
  api,
  environmentId,
}: {
  api: EnvironmentApi;
  environmentId: string;
}) {
  const [params, setParams] = useSearchParams();
  const [environment, setEnvironment] = useState<ExecutionEnvironment | null>(null);
  const [page, setPage] = useState<EnvironmentBuildPage>({ items: [], nextCursor: null });
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [logs, setLogs] = useState<{
    buildId: string;
    items: string[];
    loading: boolean;
    error: string;
  } | null>(null);
  const logRequest = useRef(0);
  const generation = useRef(0);
  const paging = useRef(false);
  const repository = params.get('repository') || undefined;
  const branchName = params.get('branchName') || undefined;
  const buildStatus = params.get('buildStatus') || undefined;
  useEffect(() => {
    const turn = ++generation.current;
    setPage({ items: [], nextCursor: null });
    setError('');
    setLogs(null);
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
  async function showLogs(buildId: string) {
    const turn = generation.current;
    const request = ++logRequest.current;
    setLogs({ buildId, items: [], loading: true, error: '' });
    try {
      const result = await api.logs(buildId);
      if (turn === generation.current && request === logRequest.current)
        setLogs({ buildId, items: result.items, loading: false, error: '' });
    } catch (reason) {
      if (turn === generation.current && request === logRequest.current)
        setLogs({
          buildId,
          items: [],
          loading: false,
          error: reason instanceof Error ? reason.message : '日志读取失败',
        });
    }
  }
  return (
    <section className="environment-page">
      <Link to="/dbt/environments">返回环境列表</Link>
      {error && <Alert type="error" title={error} />}
      {environment && (
        <>
          <header>
            <div>
              <h2>{environment.name}</h2>
              <p>
                {environment.execution.connection.instanceId} / {environment.execution.catalog} /{' '}
                {environment.execution.schema} · 配置版本 {environment.configVersion}
              </p>
            </div>
            <Button onClick={() => setEditing(!editing)}>编辑配置</Button>
          </header>
          {editing && (
            <Card title="环境配置">
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
          <Card title="发起构建">
            <BuildSubmissionForm
              key={environmentId}
              environment={environment}
              api={api}
              onSubmitted={() => setRefresh((value) => value + 1)}
            />
          </Card>
        </>
      )}
      <Card
        title="构建历史"
        extra={<Button onClick={() => setRefresh((value) => value + 1)}>刷新历史</Button>}
      >
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
          <input
            aria-label="筛选仓库"
            name="repository"
            placeholder="全部仓库"
            defaultValue={repository}
          />
          <input
            aria-label="筛选分支"
            name="branchName"
            placeholder="全部分支"
            defaultValue={branchName}
          />
          <select aria-label="筛选状态" name="buildStatus" defaultValue={buildStatus ?? ''}>
            <option value="">全部状态</option>
            {['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'OUTCOME_UNKNOWN'].map(
              (status) => (
                <option key={status}>{status}</option>
              ),
            )}
          </select>
          <Button htmlType="submit">筛选</Button>
        </form>
        {!page.items.length && <Empty description="暂无构建记录" />}
        {page.items.map((build) => (
          <article className="environment-build" key={build.buildId}>
            <p>
              <Tag>{build.buildStatus}</Tag>
              {build.initialDeployment && (
                <Tag>{build.initialDeployment.deploymentStatus}</Tag>
              )}{' '}
              <code>{build.buildId}</code>
            </p>
            <p>
              {build.repository} · {build.branchName} · {build.commitSha ?? '等待固定提交'}
            </p>
            <p>
              {build.execution?.catalog} / {build.execution?.schema} · 配置版本{' '}
              {build.execution?.configVersion ?? build.configVersion} · {build.createdAt}
            </p>
            <div className="environment-actions">
              {build.catalogAvailable && (
                <Link to={`/dbt/builds/${build.buildId}/catalog`}>查看目录</Link>
              )}
              {build.queryAvailable && (
                <Link to={`/dbt/builds/${build.buildId}/query`}>查询此构建</Link>
              )}
              <Button type="link" onClick={() => void showLogs(build.buildId)}>
                查看日志
              </Button>
            </div>
            {logs?.buildId === build.buildId && (
              <section aria-label="构建日志" aria-busy={logs.loading}>
                {logs.loading ? (
                  <p role="status">正在加载日志</p>
                ) : logs.error ? (
                  <Alert type="error" title={logs.error} />
                ) : logs.items.length ? (
                  <pre>{logs.items.join('\n')}</pre>
                ) : (
                  <p role="status">暂无日志</p>
                )}
              </section>
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
