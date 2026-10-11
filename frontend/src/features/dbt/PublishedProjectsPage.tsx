import { Alert, Button, Card, Empty } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  buildCatalogApi,
  type BuildCatalogApi,
  type DevelopmentProject,
  type BuildPage,
} from '../../api/buildCatalogApi';

const COPY = {
  projectUnavailable: 'Agent 项目列表不可用，请重试或打开已知构建链接。',
  historyUnavailable: '构建历史读取失败，请重试。',
  repository: '构建历史仓库',
};

/** 项目选择来自 Agent，历史构建按选定仓库实时读取。 */
export function PublishedProjectsPage({ api = buildCatalogApi }: { api?: BuildCatalogApi }) {
  // 仓库来自所选项目；切换后旧仓库分页响应不能写入新列表。
  const [repository, setRepository] = useState('');
  const historyGeneration = useRef(0);
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
      try {
        const result = await list();
        if (!active) return;
        setProjects(result);
        setRepository((current) =>
          result.some((project) => project.repository === current)
            ? current
            : (result.find((project) => project.repository)?.repository ?? ''),
        );
      } catch {
        if (active) setError(COPY.projectUnavailable);
      }
    }
    void read();
    return () => {
      active = false;
    };
  }, [api, refresh]);
  useEffect(() => {
    const turn = ++historyGeneration.current;
    setHistory(null);
    setLoadingMore(false);
    if (repository)
      void api
        .builds({ repository })
        .then((page) => {
          if (historyGeneration.current === turn) setHistory(page);
        })
        .catch(() => {
          if (historyGeneration.current === turn) setError(COPY.historyUnavailable);
        });
    return () => {
      historyGeneration.current++;
    };
  }, [api, repository, refresh]);
  // 游标由服务生成，追加历史时保留每条记录的固定构建身份。
  async function loadMore() {
    if (!history?.nextCursor) return;
    const cursor = history.nextCursor;
    const turn = historyGeneration.current;
    setLoadingMore(true);
    try {
      const page = await api.builds({ repository, cursor });
      if (turn !== historyGeneration.current) return;
      setHistory((current) =>
        current?.nextCursor === cursor
          ? { ...page, items: [...current.items, ...page.items] }
          : current,
      );
    } catch {
      if (turn === historyGeneration.current) setError(COPY.historyUnavailable);
    } finally {
      if (turn === historyGeneration.current) setLoadingMore(false);
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
      <label>
        {COPY.repository}{' '}
        <select
          aria-label={COPY.repository}
          value={repository}
          onChange={(event) => setRepository(event.target.value)}
        >
          {projects
            .filter((project) => project.repository)
            .map((project) => (
              <option key={project.projectId} value={project.repository!}>
                {project.name}
              </option>
            ))}
        </select>
      </label>
      {history && (
        <section aria-label="构建历史">
          <p>来源：dbt-service 实时构建历史</p>
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
