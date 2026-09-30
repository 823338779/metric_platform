import { Alert, Button, Card, Empty, Space, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  publishedCatalogApi,
  type PublishedCatalogApi,
  type PublishedProject,
  type Publication,
} from '../../api/publishedCatalogApi';

const EMPTY = '';
const POLL_MS = 5000;
const READ_ERROR = '发布信息读取失败，保留上次结果；刷新后重试。';
const STATES: Record<string, string> = {
  PREPARING: '准备中',
  BUILDING: '构建中',
  VALIDATING: '验证中',
  PUBLISHED: '已发布',
  FAILED: '失败',
  SUPERSEDED: '已被新候选替代',
};

/** 页面刷新只读取服务结果，不触发构建或批准发布。 */
export function PublishedProjectsPage({
  api = publishedCatalogApi,
}: {
  api?: PublishedCatalogApi;
}) {
  const [projects, setProjects] = useState<PublishedProject[]>([]);
  const [releases, setReleases] = useState<Record<string, Publication[]>>({});
  const [error, setError] = useState(EMPTY);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function load() {
      try {
        const latest = await api.projects();
        const histories = await Promise.all(
          latest.map(
            async (project) => [project.projectId, await api.releases(project.projectId)] as const,
          ),
        );
        if (!active) return;
        setProjects(latest);
        setReleases(Object.fromEntries(histories));
        setError(EMPTY);
      } catch {
        if (active) setError(READ_ERROR);
      } finally {
        if (active) timer = setTimeout(() => void load(), POLL_MS);
      }
    }
    void load();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [api, refresh]);
  return (
    <Card
      title="语义项目 · 已发布目录"
      extra={<Button onClick={() => setRefresh((value) => value + 1)}>刷新发布结果</Button>}
    >
      <Space orientation="vertical" style={{ width: '100%' }}>
        <Typography.Text>
          展示 dbt-service 已发布的完整目录；每 5 秒重新读取发布状态。
        </Typography.Text>
        {error && <Alert type="warning" title={error} />}
        {!error && !projects.length && <Empty description="暂无已登记项目" />}
        {projects.map((project) => (
          <Card key={project.projectId} title={project.projectId}>
            {project.activePublication ? (
              <Link
                to={`/dbt/projects/${encodeURIComponent(project.projectId)}/catalog?releaseId=${project.activePublication.releaseId}`}
              >
                查看当前目录
              </Link>
            ) : (
              <span>尚未发布</span>
            )}
            {releases[project.projectId]?.map((release) => (
              <p key={release.releaseId}>
                <Tag>{STATES[release.state] ?? release.state}</Tag>
                {release.releaseId} · {release.buildMode}
                {release.errorCode && ` · ${release.errorCode}`}
              </p>
            ))}
          </Card>
        ))}
      </Space>
    </Card>
  );
}
