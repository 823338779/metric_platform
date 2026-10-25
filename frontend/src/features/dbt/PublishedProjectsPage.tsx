import { Alert, Button, Empty, Input, Tag, Skeleton } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  buildCatalogApi,
  type BuildCatalogApi,
  type DeploymentAttempt,
  type DeploymentKey,
} from '../../api/buildCatalogApi';

const EMPTY = '';
const MAIN = 'main';
const DEPLOYED = 'DEPLOYED';
const REPOSITORY = 'repository';
const BRANCH = 'branchName';
const ENVIRONMENT = 'environmentId';
const COPY = {
  title: '部署版本',
  repository: 'Git 仓库地址',
  branch: '分支',
  environment: '环境 UUID',
  submit: '查看部署版本',
  refresh: '刷新状态',
  more: '加载更多部署版本',
  required: '填写 Git 仓库地址和环境 UUID，查看 dbt-service 中的部署版本。',
  empty: '该仓库、分支和环境暂无部署记录',
  failure: '部署历史读取失败，请重试。',
  currentFailure: '当前部署状态暂不可用，仍可浏览历史版本。',
  source: '来源：dbt-service 部署历史',
  active: '当前生效',
  catalog: '查看指标目录',
  query: '查询此版本',
  version: '部署序号',
  created: '提交时间',
  deployed: '部署时间',
  description: '追踪每次部署，回到任一固定版本理解与分析数据。',
  history: '部署历史',
  build: '构建版本',
  status: '状态',
  action: '操作',
  filter: '定位部署范围',
  loading: '正在读取部署历史',
};
type DeploymentPage = { items: DeploymentAttempt[]; nextCursor: string | null };

/** 只读取 dbt-service 已有部署契约；仓库由用户指定，不依赖外部项目登记。 */
export function PublishedProjectsPage({ api = buildCatalogApi }: { api?: BuildCatalogApi }) {
  const [params, setParams] = useSearchParams();
  const repository = params.get(REPOSITORY) ?? EMPTY;
  const branchName = params.get(BRANCH) ?? MAIN;
  const environmentId = params.get(ENVIRONMENT) ?? EMPTY;
  const [draftRepository, setDraftRepository] = useState(repository);
  const [draftBranch, setDraftBranch] = useState(branchName);
  const [draftEnvironment, setDraftEnvironment] = useState(environmentId);
  const [history, setHistory] = useState<DeploymentPage | null>(null);
  const [activeBuild, setActiveBuild] = useState<string | null>(null);
  const [error, setError] = useState(EMPTY);
  const [currentError, setCurrentError] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [refresh, setRefresh] = useState(0);
  // 仓库、分支、环境和刷新任一改变，旧分页结果都不能进入当前列表。
  const generation = useRef(0);
  const paging = useRef(false);
  useEffect(() => {
    setDraftRepository(repository);
    setDraftBranch(branchName);
    setDraftEnvironment(environmentId);
    const turn = ++generation.current;
    setHistory(null);
    setActiveBuild(null);
    setError(EMPTY);
    setCurrentError(false);
    setLoadingMore(false);
    paging.current = false;
    setLoading(Boolean(repository && branchName && environmentId));
    if (repository && branchName && environmentId) {
      const key: DeploymentKey = { repository, branchName, environmentId };
      void api
        .deployments(key)
        .then((page) => {
          if (generation.current === turn) setHistory(page);
        })
        .catch(() => {
          if (generation.current === turn) setError(COPY.failure);
        })
        .finally(() => {
          if (generation.current === turn) setLoading(false);
        });
      // 当前指针失败不影响独立的历史读取，也不会更改任何已选 buildId。
      void api
        .current(key)
        .then((target) => {
          if (generation.current === turn) setActiveBuild(target.activeBuildId);
        })
        .catch(() => {
          if (generation.current === turn) setCurrentError(true);
        });
    }
    return () => {
      generation.current++;
    };
  }, [api, repository, branchName, environmentId, refresh]);

  async function loadMore() {
    if (!history?.nextCursor || paging.current) return;
    const cursor = history.nextCursor;
    const turn = generation.current;
    paging.current = true;
    setLoadingMore(true);
    setError(EMPTY);
    try {
      const page = await api.deployments({ repository, branchName, environmentId }, cursor);
      if (generation.current === turn)
        setHistory((previous) =>
          previous?.nextCursor === cursor
            ? { ...page, items: [...previous.items, ...page.items] }
            : previous,
        );
    } catch {
      if (generation.current === turn) setError(COPY.failure);
    } finally {
      if (generation.current === turn) {
        paging.current = false;
        setLoadingMore(false);
      }
    }
  }
  return (
    <section className="projects-page">
      <header className="page-heading">
        <div>
          <h2>{COPY.title}</h2>
          <p>{COPY.description}</p>
        </div>
        <Button
          icon={<ReloadOutlined aria-hidden />}
          onClick={() => setRefresh((value) => value + 1)}
        >
          {COPY.refresh}
        </Button>
      </header>
      <section className="management-panel deployment-scope">
        <header className="panel-heading">
          <h3>{COPY.filter}</h3>
        </header>
        <form
          className="deployment-filter"
          onSubmit={(event) => {
            event.preventDefault();
            setParams({
              [REPOSITORY]: draftRepository.trim(),
              [BRANCH]: draftBranch.trim(),
              [ENVIRONMENT]: draftEnvironment,
            });
            setRefresh((value) => value + 1);
          }}
        >
          <label>
            {COPY.repository}
            <Input
              aria-label={COPY.repository}
              required
              value={draftRepository}
              onChange={(event) => setDraftRepository(event.target.value)}
            />
          </label>
          <label>
            {COPY.branch}
            <Input
              aria-label={COPY.branch}
              required
              value={draftBranch}
              onChange={(event) => setDraftBranch(event.target.value)}
            />
          </label>
          <label>
            {COPY.environment}
            <Input
              aria-label={COPY.environment}
              required
              value={draftEnvironment}
              onChange={(event) => setDraftEnvironment(event.target.value)}
            />
          </label>
          <Button
            type="primary"
            icon={<SearchOutlined aria-hidden />}
            htmlType="submit"
            loading={loading}
            disabled={!draftRepository.trim() || !draftBranch.trim() || !draftEnvironment.trim()}
          >
            {COPY.submit}
          </Button>
        </form>
      </section>
      {error && <Alert type="error" title={error} />}
      {currentError && <Alert type="warning" title={COPY.currentFailure} />}
      {(!repository || !environmentId) && <Empty description={COPY.required} />}
      {loading && (
        <div className="management-panel panel-loading" aria-label={COPY.loading}>
          <Skeleton active />
        </div>
      )}
      {history && (
        <section className="management-panel" aria-label={COPY.title}>
          <header className="panel-heading">
            <h3>{COPY.history}</h3>
            <span>{COPY.source}</span>
          </header>
          {!history.items.length && <Empty description={COPY.empty} />}
          {!!history.items.length && (
            <div className="management-table-scroll">
              <table className="management-table deployment-table">
                <thead>
                  <tr>
                    <th>{COPY.version}</th>
                    <th>{COPY.build}</th>
                    <th>{COPY.status}</th>
                    <th>{COPY.created}</th>
                    <th>{COPY.deployed}</th>
                    <th>{COPY.action}</th>
                  </tr>
                </thead>
                <tbody>
                  {history.items.map((deployment) => (
                    <tr key={deployment.generation}>
                      <td>
                        <strong>#{deployment.generation}</strong>
                      </td>
                      <td>
                        <code>{deployment.buildId}</code>
                        {deployment.reason && <small>{deployment.reason}</small>}
                      </td>
                      <td>
                        <Tag
                          color={deployment.deploymentStatus === DEPLOYED ? 'success' : 'default'}
                        >
                          {deployment.deploymentStatus}
                        </Tag>
                        {deployment.buildId === activeBuild && (
                          <Tag color="green">{COPY.active}</Tag>
                        )}
                      </td>
                      <td>
                        <time>{deployment.createdAt}</time>
                      </td>
                      <td>
                        <time>{deployment.deployedAt ?? EMPTY}</time>
                      </td>
                      <td>
                        <div className="table-actions">
                          <Link
                            to={`/dbt/builds/${encodeURIComponent(deployment.buildId)}/catalog`}
                          >
                            {COPY.catalog}
                          </Link>{' '}
                          {deployment.deploymentStatus === DEPLOYED && (
                            <Link
                              to={`/dbt/builds/${encodeURIComponent(deployment.buildId)}/query`}
                            >
                              {COPY.query}
                            </Link>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {history.nextCursor && (
            <Button loading={loadingMore} onClick={() => void loadMore()}>
              {COPY.more}
            </Button>
          )}
        </section>
      )}
    </section>
  );
}
