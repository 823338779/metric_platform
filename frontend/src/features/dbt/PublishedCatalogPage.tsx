import { Alert, Button } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  buildCatalogApi,
  type BuildCatalogApi,
  type BuildView,
  type DeploymentTarget,
  type BranchList,
  type BuildPage,
  type DeploymentAttempt,
  type Environment,
} from '../../api/buildCatalogApi';
import { publishedResourceAdapter } from './publishedResourceAdapter';
import { DbtCatalogPage } from './DbtCatalogPage';
import { BranchContextBar } from './BranchContextBar';
import { BranchBuildPanel } from './BranchBuildPanel';

const PREVIEW = 'PREVIEW';
const PRODUCTION = 'PRODUCTION';
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
};
/** 构建页面只在初次无版本时解析部署，新部署只提示，不覆盖固定 buildId。 */
export function PublishedCatalogPage({ api = buildCatalogApi }: { api?: BuildCatalogApi }) {
  const { projectId: routeProjectId, buildId: routeBuildId } = useParams();
  const [params, setParams] = useSearchParams();
  const projectId = routeProjectId ?? params.get('agentProjectId') ?? '';
  const navigate = useNavigate();
  const buildId = routeBuildId ?? params.get('buildId');
  const [build, setBuild] = useState<BuildView | null>(null);
  const [current, setCurrent] = useState<DeploymentTarget | null>(null);
  const [branches, setBranches] = useState<BranchList | null>(null);
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
  const adapter = useMemo(() => publishedResourceAdapter(api), [api, buildId]);
  const repository = params.get('repository') ?? '';
  const branchName = params.get('branchName') ?? '';
  const environment = (
    params.get('environment') === PRODUCTION ? PRODUCTION : PREVIEW
  ) as Environment;
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
              environment: fixed.environment,
            }
          : { repository, branchName, environment };
        if (!key.repository || !key.branchName) {
          setBusy(false);
          return;
        }
        const [target, builds, attempts] = await Promise.allSettled([
          api.current(key),
          includeHistory
            ? api.builds({ repository: key.repository, branchName: key.branchName })
            : Promise.resolve(null),
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
  }, [api, buildId, repository, branchName, environment, refresh]);
  useEffect(() => {
    if (!projectId) return;
    let active = true;
    void api
      .branches(projectId)
      .then((value) => {
        if (active) setBranches(value);
      })
      .catch(() => {
        if (active) setError('Agent 分支信息不可用，历史构建仍可浏览。');
      });
    return () => {
      active = false;
    };
  }, [api, projectId, refresh]);
  // 两类历史游标独立前进；状态轮询不覆盖已追加的历史页面。
  async function loadHistory(deployment: boolean) {
    const cursor = deployment ? deploymentCursor : history?.nextCursor;
    if (!cursor) return;
    const turn = historyGeneration.current;
    const key = {
      repository: build?.repository ?? repository,
      branchName: build?.branchName ?? branchName,
      environment: build?.environment ?? environment,
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
    navigate(
      `/dbt/builds/${encodeURIComponent(id)}/catalog${projectId ? `?agentProjectId=${encodeURIComponent(projectId)}` : ''}`,
    );
  }
  function selectBranch(name: string) {
    const selection = {
      repository: build?.repository ?? repository,
      branchName: name,
      environment: name === 'main' ? PRODUCTION : PREVIEW,
    };
    if (routeBuildId)
      navigate(
        `/dbt/projects/${encodeURIComponent(projectId)}/catalog?${new URLSearchParams(selection)}`,
      );
    else setParams(selection);
  }
  return (
    <div className="published-catalog">
      {error && <Alert type="warning" title={error} />}
      {branches && (
        <BranchContextBar
          branches={branches}
          repository={build?.repository ?? repository}
          branchName={build?.branchName ?? branchName}
          environment={build?.environment ?? environment}
          onSelect={selectBranch}
          projectId={projectId}
        />
      )}
      {build && (
        <p>
          仓库：{build.repository} · 分支：{build.branchName} · 环境：{build.environment} ·
          buildId：{build.buildId} · SHA：{build.commitSha}
        </p>
      )}
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
      {build && !build.queryAvailable && (
        <Alert
          type="warning"
          title={`查询不可用：${build.queryUnavailableReason ?? '环境暂不可用'}；目录与历史继续可读。`}
        />
      )}
      <Button loading={busy} onClick={() => setRefresh((value) => value + 1)}>
        {COPY.refresh}
      </Button>
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
      {build?.catalogAvailable ? (
        <DbtCatalogPage key={buildId} api={adapter} queryAvailable={build.queryAvailable} />
      ) : build && !busy ? (
        <Alert type="info" title={COPY.unavailable} />
      ) : null}
    </div>
  );
}
