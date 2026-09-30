import { Alert, Button, Card, Empty, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtProject, DbtRelease } from '../../api/types';
import { ApiProblem } from '../../api/httpClient';
import { DbtPublicationProgress } from './DbtPublicationProgress';

const REFRESH_LABEL = '手动刷新';
const REFRESH_ACCEPTED = '刷新请求已受理，等待后台同步和发布；当前版本仍可使用。';
const REFRESH_FAILED = '刷新请求失败，请稍后重试';
const EMPTY = '';
const STATUS_POLL_MS = 5000;
const STATUS_FAILED = '项目状态读取失败，显示的可能是上次结果，页面会自动重试。';
const RELEASE_FAILED = '发布状态读取失败';

/** 用观测时间确认后台已处理新信号，不把旧结果当作本次刷新完成。 */
function observationKey(project: DbtProject) {
  if (project.lastObservedAt) return project.lastObservedAt;
  return [
    project.lastObservedAt,
    project.observedSha,
    project.lastObservationStatus,
    project.activeReleaseId,
  ].join('|');
}

export function DbtProjectsPage({
  api = metricPlatformApi,
}: {
  api?: Pick<MetricPlatformApi, 'listDbtProjects' | 'refreshDbtProjects' | 'listDbtReleases'>;
}) {
  const [projects, setProjects] = useState<DbtProject[]>([]);
  const [releases, setReleases] = useState<Record<string, DbtRelease[]>>({});
  const [releaseErrors, setReleaseErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState(EMPTY);
  const [statusError, setStatusError] = useState(EMPTY);
  const [refreshing, setRefreshing] = useState(false);
  // 保存点击前的观测快照；重新进入页面也持续读取服务端已有任务。
  const [baseline, setBaseline] = useState<Record<string, string> | null>(null);
  async function refresh() {
    setRefreshing(true);
    setError(EMPTY);
    const before = Object.fromEntries(
      projects.map((project) => [project.projectId, observationKey(project)]),
    );
    try {
      await api.refreshDbtProjects();
      setBaseline(before);
    } catch (failure) {
      setError(failure instanceof ApiProblem ? failure.message : REFRESH_FAILED);
    } finally {
      setRefreshing(false);
    }
  }
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function load() {
      try {
        const latest = await api.listDbtProjects();
        const results = await Promise.allSettled(
          latest.map((project) => api.listDbtReleases(project.projectId)),
        );
        if (!active) return;
        setProjects(latest);
        setStatusError(EMPTY);
        const loaded: Record<string, DbtRelease[]> = {};
        const failures: Record<string, string> = {};
        results.forEach((result, index) => {
          const id = latest[index].projectId;
          if (result.status === 'fulfilled') loaded[id] = result.value;
          else failures[id] = RELEASE_FAILED;
        });
        setReleases((previous) => ({ ...previous, ...loaded }));
        setReleaseErrors(failures);
      } catch {
        if (active) setStatusError(STATUS_FAILED);
      } finally {
        // 串行轮询覆盖后台清理；卸载或新请求后丢弃过期响应。
        if (active) timer = setTimeout(() => void load(), STATUS_POLL_MS);
      }
    }
    void load();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [api, baseline]);
  const waiting = (project: DbtProject) =>
    baseline !== null && baseline[project.projectId] === observationKey(project);
  const accepted = baseline !== null && (projects.length === 0 || projects.some(waiting));
  return (
    <Card
      title="语义项目 · 发布进度"
      extra={
        <Button
          type="primary"
          loading={refreshing}
          disabled={refreshing}
          onClick={() => void refresh()}
        >
          {REFRESH_LABEL}
        </Button>
      }
    >
      <Space orientation="vertical" size="large" style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          从远端 Git 同步定义，构建并校验后切换发布。页面每 5 秒更新；请求受理不代表发布成功。
        </Typography.Text>
        {error && <Alert type="error" showIcon title={error} />}
        {statusError && <Alert type="warning" showIcon title={statusError} />}
        {accepted && <Alert type="info" showIcon title={REFRESH_ACCEPTED} />}
        {!projects.length && <Empty description="尚无语义项目，手动刷新后会读取配置的远端仓库。" />}
        {projects.map((project) => (
          <Card
            key={project.projectId}
            size="small"
            title={project.projectId}
            extra={
              project.activeReleaseId ? (
                <Link
                  to={`/dbt/projects/${encodeURIComponent(project.projectId)}/catalog?releaseId=${project.activeReleaseId}`}
                >
                  查看当前目录
                </Link>
              ) : (
                <Typography.Text type="secondary">尚未发布</Typography.Text>
              )
            }
          >
            <DbtPublicationProgress
              project={project}
              releases={releases[project.projectId] ?? []}
              waiting={waiting(project)}
              loadError={releaseErrors[project.projectId]}
            />
          </Card>
        ))}
      </Space>
    </Card>
  );
}
