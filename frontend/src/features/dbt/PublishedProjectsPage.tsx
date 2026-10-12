import { Alert, Button, Card, Empty, Input, Tag } from 'antd';
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
const PRODUCTION = 'PRODUCTION';
const PREVIEW = 'PREVIEW';
const DEPLOYED = 'DEPLOYED';
const REPOSITORY = 'repository';
const BRANCH = 'branchName';
const ENVIRONMENT = 'environment';
const COPY = {
  title: '部署版本',
  repository: 'Git 仓库地址',
  branch: '分支',
  environment: '环境',
  submit: '查看部署版本',
  refresh: '刷新状态',
  more: '加载更多部署版本',
  required: '填写 Git 仓库地址，查看 dbt-service 中的部署版本。',
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
  production: '正式',
  preview: '预览',
};
type DeploymentPage = { items: DeploymentAttempt[]; nextCursor: string | null };

/** 只读取 dbt-service 已有部署契约；仓库由用户指定，不依赖外部项目登记。 */
export function PublishedProjectsPage({ api = buildCatalogApi }: { api?: BuildCatalogApi }) {
  const [params, setParams] = useSearchParams();
  const repository = params.get(REPOSITORY) ?? EMPTY;
  const branchName = params.get(BRANCH) ?? MAIN;
  const environment = params.get(ENVIRONMENT) === PREVIEW ? PREVIEW : PRODUCTION;
  const [draftRepository, setDraftRepository] = useState(repository);
  const [draftBranch, setDraftBranch] = useState(branchName);
  const [draftEnvironment, setDraftEnvironment] = useState(environment);
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
    setDraftEnvironment(environment);
    const turn = ++generation.current;
    setHistory(null);
    setActiveBuild(null);
    setError(EMPTY);
    setCurrentError(false);
    setLoadingMore(false);
    paging.current = false;
    setLoading(Boolean(repository && branchName));
    if (repository && branchName) {
      const key: DeploymentKey = { repository, branchName, environment };
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
  }, [api, repository, branchName, environment, refresh]);

  async function loadMore() {
    if (!history?.nextCursor || paging.current) return;
    const cursor = history.nextCursor;
    const turn = generation.current;
    paging.current = true;
    setLoadingMore(true);
    setError(EMPTY);
    try {
      const page = await api.deployments({ repository, branchName, environment }, cursor);
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
      <header className="projects-heading">
        <h2>{COPY.title}</h2>
        <Button onClick={() => setRefresh((value) => value + 1)}>{COPY.refresh}</Button>
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
          <select
            aria-label={COPY.environment}
            value={draftEnvironment}
            onChange={(event) =>
              setDraftEnvironment(event.target.value === PREVIEW ? PREVIEW : PRODUCTION)
            }
          >
            <option value={PRODUCTION}>{COPY.production}</option>
            <option value={PREVIEW}>{COPY.preview}</option>
          </select>
        </label>
        <Button
          htmlType="submit"
          loading={loading}
          disabled={!draftRepository.trim() || !draftBranch.trim()}
        >
          {COPY.submit}
        </Button>
      </form>
      {error && <Alert type="error" title={error} />}
      {currentError && <Alert type="warning" title={COPY.currentFailure} />}
      {!repository && <Empty description={COPY.required} />}
      {history && (
        <section aria-label={COPY.title}>
          <p>{COPY.source}</p>
          {!history.items.length && <Empty description={COPY.empty} />}
          {history.items.map((deployment) => (
            <Card key={deployment.generation} title={`${COPY.version} ${deployment.generation}`}>
              <Tag>{deployment.deploymentStatus}</Tag>
              {deployment.buildId === activeBuild && <Tag color="green">{COPY.active}</Tag>}
              <p>
                <code>{deployment.buildId}</code>
              </p>
              <p>
                {COPY.created}：{deployment.createdAt} · {COPY.deployed}：
                {deployment.deployedAt ?? EMPTY}
              </p>
              {deployment.reason && <p>{deployment.reason}</p>}
              <Link to={`/dbt/builds/${encodeURIComponent(deployment.buildId)}/catalog`}>
                {COPY.catalog}
              </Link>{' '}
              {deployment.deploymentStatus === DEPLOYED && (
                <Link to={`/dbt/builds/${encodeURIComponent(deployment.buildId)}/query`}>
                  {COPY.query}
                </Link>
              )}
            </Card>
          ))}
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
