import { Alert, Button, Skeleton } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import {
  publishedCatalogApi,
  type PublishedCatalogApi,
  type BranchView,
  type Publication,
} from '../../api/publishedCatalogApi';
import { DbtCatalogPage } from './DbtCatalogPage';
import { publishedResourceAdapter } from './publishedResourceAdapter';
import { BranchContextBar } from './BranchContextBar';
import { BranchBuildPanel } from './BranchBuildPanel';
import { BranchChangesPanel } from './BranchChangesPanel';

const ACTIVE = 'ACTIVE';
const PRODUCTION = 'PRODUCTION';
const PUBLISHED = 'PUBLISHED';
const FAILURE = '分支或发布版本读取失败，请重试';

/** URL 是页面的固定上下文；无分支旧链接只能解析生产登记。 */
export function PublishedCatalogPage({ api = publishedCatalogApi }: { api?: PublishedCatalogApi }) {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const branchId = params.get('branchId');
  const releaseId = params.get('releaseId');
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [state, setState] = useState<{
    key: string;
    branches: BranchView[];
    branch: BranchView;
    releases: Publication[];
  } | null>(null);
  const generation = useRef(0);
  const key = JSON.stringify([projectId, branchId, releaseId, attempt]);
  const current = state?.key === key ? state : null;
  const context = useMemo(
    () => (branchId ? { projectId, branchId, releaseId } : null),
    [projectId, branchId, releaseId],
  );
  const scopedApi = useMemo(
    () => (context ? { ...api, ...api.inBranch(context) } : api),
    [api, context],
  );
  const adapter = useMemo(() => publishedResourceAdapter(scopedApi), [scopedApi]);

  useEffect(() => {
    const turn = ++generation.current;
    setError('');
    setBusy(true);
    async function read() {
      try {
        const branches = await api.branches(projectId);
        const selected = branchId ?? branches.find((item) => item.mode === PRODUCTION)?.branchId;
        if (!selected) throw new Error('生产分支尚未登记');
        const branch = await api.branch(projectId, selected);
        if (turn !== generation.current) return;
        if (branch.branchId !== selected || branch.projectId !== projectId)
          throw new Error('分支归属不一致');
        const releases =
          branch.status === ACTIVE
            ? await api.inBranch({ projectId, branchId: selected, releaseId }).releases(projectId)
            : [];
        if (turn !== generation.current) return;
        // 列表只有状态；当前版本与最新构建的安全测试摘要需读取固定详情。
        const detailIds = [...new Set([releaseId, branch.latestReleaseId])].filter(
          (id): id is string => Boolean(id && releases.some((item) => item.releaseId === id)),
        );
        const details = await Promise.allSettled(
          detailIds.map((id) =>
            api.inBranch({ projectId, branchId: selected, releaseId: id }).release(projectId, id),
          ),
        );
        if (turn !== generation.current) return;
        details.forEach((detail) => {
          if (detail.status === 'fulfilled') {
            const index = releases.findIndex((item) => item.releaseId === detail.value.releaseId);
            if (index >= 0) releases[index] = detail.value;
          } else setError('构建详情暂不可用，列表状态保留；请刷新后重试。');
        });
        // 仅初次无版本访问采用活动版本；后续 head 推进保留当前固定版本。
        if (!branchId || (!releaseId && branch.activeReleaseId && branch.status === ACTIVE)) {
          setParams(
            (previous) => {
              const next = new URLSearchParams(previous);
              next.set('branchId', selected);
              if (!releaseId && branch.activeReleaseId)
                next.set('releaseId', branch.activeReleaseId);
              return next;
            },
            { replace: true },
          );
        } else setState({ key, branches, branch, releases });
      } catch {
        if (turn === generation.current) setError(FAILURE);
      } finally {
        if (turn === generation.current) setBusy(false);
      }
    }
    void read();
    return () => {
      generation.current++;
    };
  }, [api, projectId, branchId, releaseId, attempt]);

  function selectBranch(id: string) {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.set('branchId', id);
      for (const field of ['releaseId', 'resourceId', 'metricResourceId']) next.delete(field);
      return next;
    });
  }
  function selectRelease(id: string) {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.set('releaseId', id);
      next.delete('resourceId');
      next.delete('metricResourceId');
      return next;
    });
  }
  async function refresh() {
    if (!branchId) {
      setAttempt((value) => value + 1);
      return;
    }
    const turn = generation.current;
    setBusy(true);
    setError('');
    try {
      const value = await api.branch(projectId, branchId);
      if (turn !== generation.current) return;
      if (value.status !== ACTIVE) {
        setAttempt((count) => count + 1);
        return;
      }
      if (value.activeReleaseId && value.activeReleaseId !== releaseId)
        selectRelease(value.activeReleaseId);
      else setAttempt((count) => count + 1);
    } catch {
      if (turn === generation.current) setError(FAILURE);
    } finally {
      if (turn === generation.current) setBusy(false);
    }
  }
  const action = (
    <Button size="small" icon={<ReloadOutlined />} loading={busy} onClick={() => void refresh()}>
      刷新已发布版本
    </Button>
  );
  const selected = current?.releases.find((item) => item.releaseId === releaseId);
  const available = current?.releases.find(
    (item) => item.releaseId === current.branch.activeReleaseId,
  );
  return (
    <div className="published-catalog">
      {error && <Alert title={error} type="warning" />}
      {current && (
        <>
          <BranchContextBar
            branches={current.branches}
            branch={current.branch}
            availableSha={available?.sourceSha}
            onSelect={selectBranch}
          />
          {releaseId &&
            current.branch.activeReleaseId &&
            current.branch.activeReleaseId !== releaseId && (
              <Alert
                type="info"
                title="当前显示固定版本；可用版本已变化，点击刷新后重新选择资源。"
              />
            )}
          <BranchBuildPanel
            releases={current.releases}
            currentReleaseId={releaseId}
            activeReleaseId={current.branch.activeReleaseId}
            latestReleaseId={current.branch.latestReleaseId}
            onSelect={selectRelease}
          />
          {context && current.branch.mode !== PRODUCTION && selected?.state === PUBLISHED && (
            <BranchChangesPanel api={api} context={context} />
          )}
        </>
      )}
      {current?.branch.status === ACTIVE && selected?.state === PUBLISHED ? (
        <DbtCatalogPage key={key} api={adapter} actions={action} />
      ) : (
        <>
          {action}
          {busy && <Skeleton active />}
          {current?.branch.status === ACTIVE && !busy && (
            <Alert
              type="info"
              title={
                releaseId
                  ? '所选版本尚未可用或已不在此分支，构建状态见上方。'
                  : '尚无可用预览，等待构建完成后刷新。'
              }
            />
          )}
        </>
      )}
    </div>
  );
}
