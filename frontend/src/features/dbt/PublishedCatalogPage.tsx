import { Alert, Button, Skeleton, Typography } from 'antd';
import {
  BranchesOutlined,
  CloseOutlined,
  DatabaseOutlined,
  FolderOpenOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  buildCatalogApi,
  type BuildCatalogApi,
  type BuildView,
  type DeploymentTarget,
  type BuildPage,
  type DeploymentAttempt,
} from '../../api/buildCatalogApi';
import { publishedResourceAdapter } from './publishedResourceAdapter';
import { DbtCatalogPage } from './DbtCatalogPage';
import { BranchBuildPanel } from './BranchBuildPanel';
import './catalog-page.css';

const POLL_MS = 5000;
const COPY = {
  refresh: '刷新构建状态',
  switch: '切换到当前部署',
  updated: '有效部署已更新；当前页面保持固定构建，切换后重新选择资源。',
  undeployed: '当前分支尚未部署',
  choose: '请选择项目分支',
  unavailable: '目录暂不可用',
  failed: '读取失败，请重试；已有固定版本保持不变。',
  moreBuilds: '加载更多构建历史',
  moreDeployments: '加载更多部署历史',
  context: '版本上下文',
  closeContext: '收起版本上下文',
  details: '版本详情',
  repository: '仓库',
  branch: '分支',
  environment: '环境',
  pinned: '固定版本',
  config: '配置版本',
  commit: '提交 SHA',
  refreshShort: '刷新',
  fixedHint: '目录、定义、血缘与源码均来自此版本。',
  environments: '选择环境与构建',
  loading: '读取版本信息',
};
const ENVIRONMENTS = '/dbt/environments';
const CONTEXT_ID = 'catalog-version-context';
const DESKTOP_CONTEXT = '(min-width: 1200px)';
const MEDIA_CHANGE = 'change';
const PATH_SEPARATOR = '/';
const GIT_SUFFIX = /\.git$/;
/** 构建页面只在初次无版本时解析部署，新部署只提示，不覆盖固定 buildId。 */
export function PublishedCatalogPage({ api = buildCatalogApi }: { api?: BuildCatalogApi }) {
  const { buildId: routeBuildId } = useParams();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const buildId = routeBuildId ?? params.get('buildId');
  const [build, setBuild] = useState<BuildView | null>(null);
  const [current, setCurrent] = useState<DeploymentTarget | null>(null);
  const [history, setHistory] = useState<BuildPage | null>(null);
  const [deployments, setDeployments] = useState<DeploymentAttempt[]>([]);
  const [deploymentCursor, setDeploymentCursor] = useState<string | null>(null);
  const [loadingBuilds, setLoadingBuilds] = useState(false);
  const [loadingDeployments, setLoadingDeployments] = useState(false);
  // 分支/仓库/环境或固定构建改变后，旧分页响应不能写入新作用域。
  const historyGeneration = useRef(0);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [busy, setBusy] = useState(false);
  /** 版本侧栏在窄屏默认收起，用户可以通过工具栏随时展开。 */
  const [contextOpen, setContextOpen] = useState(
    () => window.matchMedia?.(DESKTOP_CONTEXT).matches ?? true,
  );
  const contextToggle = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const media = window.matchMedia?.(DESKTOP_CONTEXT);
    if (!media) return;
    const collapseOnNarrow = () => {
      if (!media.matches) setContextOpen(false);
    };
    collapseOnNarrow();
    media.addEventListener(MEDIA_CHANGE, collapseOnNarrow);
    return () => media.removeEventListener(MEDIA_CHANGE, collapseOnNarrow);
  }, []);
  const adapter = useMemo(() => publishedResourceAdapter(api), [api, buildId]);
  const repository = params.get('repository') ?? '';
  const branchName = params.get('branchName') ?? '';
  const environmentId = params.get('environmentId') ?? '';
  useEffect(() => {
    const turn = ++historyGeneration.current;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setBuild(null);
    setCurrent(null);
    setHistory(null);
    setDeployments([]);
    setDeploymentCursor(null);
    setLoadingBuilds(false);
    setLoadingDeployments(false);
    setError('');
    setBusy(true);
    async function read(includeHistory = false) {
      try {
        const fixed = buildId ? await api.build(buildId) : null;
        if (fixed && fixed.buildId !== buildId) throw new Error('构建身份不一致');
        if (!active) return;
        setBuild(fixed);
        const key = fixed
          ? {
              repository: fixed.repository,
              branchName: fixed.branchName,
              environmentId: fixed.execution.environmentId,
            }
          : { repository, branchName, environmentId };
        if (!key.repository || !key.branchName || !key.environmentId) {
          setBusy(false);
          return;
        }
        const [target, builds, attempts] = await Promise.allSettled([
          api.current(key),
          includeHistory ? api.builds({ ...key }) : Promise.resolve(null),
          includeHistory ? api.deployments(key) : Promise.resolve(null),
        ]);
        if (!active) return;
        if (builds.status === 'fulfilled' && builds.value) setHistory(builds.value);
        if (attempts.status === 'fulfilled' && attempts.value) {
          setDeployments(attempts.value.items);
          setDeploymentCursor(attempts.value.nextCursor);
        }
        if (target.status === 'fulfilled') {
          setCurrent(target.value);
          if (!buildId && target.value.activeBuildId) {
            setParams(
              (previous) => {
                const next = new URLSearchParams(previous);
                next.set('buildId', target.value.activeBuildId!);
                return next;
              },
              { replace: true },
            );
            return;
          }
        } else setError(COPY.failed);
        timer = setTimeout(() => void read(), POLL_MS);
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : COPY.failed);
      } finally {
        if (active) setBusy(false);
      }
    }
    void read(true);
    return () => {
      active = false;
      if (historyGeneration.current === turn) historyGeneration.current++;
      if (timer) clearTimeout(timer);
    };
  }, [api, buildId, repository, branchName, environmentId, refresh]);
  // 两类历史游标独立前进；状态轮询不覆盖已追加的历史页面。
  async function loadHistory(deployment: boolean) {
    const cursor = deployment ? deploymentCursor : history?.nextCursor;
    if (!cursor) return;
    const turn = historyGeneration.current;
    const key = {
      repository: build?.repository ?? repository,
      branchName: build?.branchName ?? branchName,
      environmentId: build?.execution.environmentId ?? environmentId,
    };
    const setLoading = deployment ? setLoadingDeployments : setLoadingBuilds;
    setLoading(true);
    try {
      if (deployment) {
        const page = await api.deployments(key, cursor);
        if (turn !== historyGeneration.current) return;
        setDeployments((current) => [...current, ...page.items]);
        setDeploymentCursor(page.nextCursor);
      } else {
        const page = await api.builds({
          environmentId: key.environmentId,
          repository: key.repository,
          branchName: key.branchName,
          cursor,
        });
        if (turn !== historyGeneration.current) return;
        setHistory((current) =>
          current ? { ...page, items: [...current.items, ...page.items] } : current,
        );
      }
    } catch {
      if (turn === historyGeneration.current) setError(COPY.failed);
    } finally {
      if (turn === historyGeneration.current) setLoading(false);
    }
  }
  function selectBuild(id: string) {
    navigate(`/dbt/builds/${encodeURIComponent(id)}/catalog`);
  }
  const repositoryName =
    build?.repository.split(PATH_SEPARATOR).filter(Boolean).at(-1)?.replace(GIT_SUFFIX, '') ??
    COPY.environments;
  return (
    <div className="published-catalog">
      <header className="catalog-context-bar">
        <div className="catalog-context-summary">
          <Link className="catalog-project-link" to={ENVIRONMENTS} title={build?.repository}>
            <FolderOpenOutlined aria-hidden /> <strong>{repositoryName}</strong>
          </Link>
          {build && (
            <>
              <span className="catalog-branch" title={build.branchName}>
                <BranchesOutlined aria-hidden /> {build.branchName}
              </span>
              <Link
                className="catalog-environment-link"
                to={`/dbt/environments/${build.execution.environmentId}`}
                title={`${build.execution.catalog} / ${build.execution.schema}`}
              >
                <DatabaseOutlined aria-hidden /> {build.execution.catalog} /{' '}
                {build.execution.schema}
              </Link>
            </>
          )}
        </div>
        <div className="catalog-context-actions">
          <Button
            type="text"
            loading={busy}
            aria-label={COPY.refresh}
            icon={<ReloadOutlined aria-hidden />}
            onClick={() => setRefresh((value) => value + 1)}
          >
            {COPY.refreshShort}
          </Button>
          <Button
            ref={contextToggle}
            type="text"
            icon={<InfoCircleOutlined aria-hidden />}
            aria-expanded={contextOpen}
            aria-controls={CONTEXT_ID}
            onClick={() => setContextOpen((value) => !value)}
          >
            {COPY.context}
          </Button>
        </div>
      </header>
      {error && <Alert type="warning" title={error} />}
      {current?.activeBuildId && buildId && current.activeBuildId !== buildId && (
        <Alert
          type="info"
          title={COPY.updated}
          action={
            <Button onClick={() => selectBuild(current.activeBuildId!)}>{COPY.switch}</Button>
          }
        />
      )}
      {!buildId && current && !current.activeBuildId && (
        <Alert type="info" title={COPY.undeployed} />
      )}
      {!buildId && !branchName && <p>{COPY.choose}</p>}
      <div className={`published-catalog-body${contextOpen ? ' has-context' : ''}`}>
        {build?.catalogAvailable ? (
          <DbtCatalogPage key={buildId} api={adapter} />
        ) : build && !busy ? (
          <Alert type="info" title={COPY.unavailable} />
        ) : busy ? (
          <div className="catalog-page-loading" aria-label={COPY.loading}>
            <Skeleton active />
          </div>
        ) : (
          <div className="catalog-page-loading">
            <Link to={ENVIRONMENTS}>{COPY.environments}</Link>
          </div>
        )}
        {contextOpen && (
          <aside className="catalog-context" id={CONTEXT_ID} aria-label={COPY.context}>
            <div className="catalog-context-title">
              <h2>{COPY.context}</h2>
              <Button
                type="text"
                size="small"
                aria-label={COPY.closeContext}
                icon={<CloseOutlined aria-hidden />}
                onClick={() => {
                  setContextOpen(false);
                  contextToggle.current?.focus();
                }}
              />
            </div>
            {build && (
              <>
                <dl className="catalog-context-fields">
                  <div>
                    <dt>{COPY.repository}</dt>
                    <dd title={build.repository}>{repositoryName}</dd>
                  </div>
                  <div>
                    <dt>{COPY.branch}</dt>
                    <dd>{build.branchName}</dd>
                  </div>
                  <div>
                    <dt>{COPY.environment}</dt>
                    <dd>
                      <Link to={`/dbt/environments/${build.execution.environmentId}`}>
                        {build.execution.catalog} / {build.execution.schema}
                      </Link>
                    </dd>
                  </div>
                  <div>
                    <dt>{COPY.pinned}</dt>
                    <dd>
                      <code title={build.buildId}>{build.buildId.slice(0, 8)}</code>
                    </dd>
                  </div>
                </dl>
                <p className="catalog-context-hint">{COPY.fixedHint}</p>
                <details className="catalog-version-details">
                  <summary>{COPY.details}</summary>
                  <dl>
                    <dt>{COPY.repository}</dt>
                    <dd>
                      <Typography.Text copyable>{build.repository}</Typography.Text>
                    </dd>
                    <dt>{COPY.pinned}</dt>
                    <dd>
                      <Typography.Text copyable>{build.buildId}</Typography.Text>
                    </dd>
                    <dt>{COPY.commit}</dt>
                    <dd>
                      <Typography.Text copyable>{build.commitSha}</Typography.Text>
                    </dd>
                    <dt>{COPY.config}</dt>
                    <dd>{build.execution.configVersion}</dd>
                  </dl>
                </details>
              </>
            )}
            {history && (
              <BranchBuildPanel
                history={history}
                deployments={deployments}
                currentBuildId={buildId}
                activeBuildId={current?.activeBuildId ?? null}
                onSelect={selectBuild}
              />
            )}
            {history?.nextCursor && (
              <Button
                loading={loadingBuilds}
                disabled={loadingBuilds}
                onClick={() => void loadHistory(false)}
              >
                {COPY.moreBuilds}
              </Button>
            )}
            {deploymentCursor && (
              <Button
                loading={loadingDeployments}
                disabled={loadingDeployments}
                onClick={() => void loadHistory(true)}
              >
                {COPY.moreDeployments}
              </Button>
            )}
          </aside>
        )}
      </div>
    </div>
  );
}
