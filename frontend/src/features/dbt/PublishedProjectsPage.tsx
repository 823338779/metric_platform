import { Alert, Button, Card, Empty } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  buildCatalogApi,
  type BuildCatalogApi,
  type DevelopmentProject,
  type BuildPage,
} from '../../api/buildCatalogApi';

/** 项目选择来自Agent，历史构建来自平台投影；Agent失联不阻断已有历史。 */
export function PublishedProjectsPage({ api = buildCatalogApi }: { api?: BuildCatalogApi }) {
  const [projects, setProjects] = useState<DevelopmentProject[]>([]);
  const [history, setHistory] = useState<BuildPage | null>(null);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  useEffect(() => {
    let active = true;
    async function read() {
      setError('');
      const list = async () => {
        const result: DevelopmentProject[] = [];
        let cursor: string | undefined;
        do {
          const page = await api.projects(cursor);
          result.push(...page.items);
          cursor = page.nextCursor ?? undefined;
        } while (cursor && active);
        return result;
      };
      const [projectResult, buildResult] = await Promise.allSettled([list(), api.builds()]);
      if (!active) return;
      if (projectResult.status === 'fulfilled') setProjects(projectResult.value);
      else setError('Agent 项目列表不可用，以下构建历史仍可查看。');
      if (buildResult.status === 'fulfilled') setHistory(buildResult.value);
    }
    void read();
    return () => {
      active = false;
    };
  }, [api, refresh]);
  // 游标由服务生成，追加历史时保留每条记录的固定构建身份。
  async function loadMore() {
    if (!history?.nextCursor) return;
    const cursor = history.nextCursor;
    setLoadingMore(true);
    try {
      const page = await api.builds({ cursor });
      setHistory((current) =>
        current?.nextCursor === cursor
          ? { ...page, items: [...current.items, ...page.items] }
          : current,
      );
    } catch {
      setError('构建历史读取失败，请重试。');
    } finally {
      setLoadingMore(false);
    }
  }
  return (
    <section className="projects-page">
      <header className="projects-heading">
        <h2>语义项目与构建历史</h2>
        <Button onClick={() => setRefresh((value) => value + 1)}>刷新状态</Button>
      </header>
      {error && <Alert type="warning" title={error} />}{' '}
      {!projects.length && !history?.items.length && <Empty description="暂无项目或构建" />}
      {projects.map((project) => (
        <Card key={project.projectId} title={project.name}>
          <p>{project.repository}</p>
          {project.developmentConfigured && (
            <Link
              to={`/dbt/projects/${encodeURIComponent(project.projectId)}/catalog?${new URLSearchParams({ repository: project.repository ?? '' })}`}
            >
              选择项目分支
            </Link>
          )}
        </Card>
      ))}
      {history && (
        <section aria-label="构建历史">
          <p>
            来源：{history.source} · 同步时间：{history.syncedAt}
          </p>
          {history.items.map((build) => (
            <p key={build.buildId}>
              {build.repository} · {build.branchName} · {build.buildStatus}{' '}
              <Link to={`/dbt/builds/${encodeURIComponent(build.buildId)}/catalog`}>
                {build.buildId}
              </Link>
            </p>
          ))}
          {history.nextCursor && (
            <Button loading={loadingMore} onClick={() => void loadMore()}>
              加载更多构建
            </Button>
          )}
        </section>
      )}
    </section>
  );
}
