import { Button, Tag } from 'antd';
import type { BuildPage, DeploymentAttempt } from '../../api/buildCatalogApi';

/** 构建状态、当前有效版本和部署尝试独立展示，成功构建不等于部署成功。 */
export function BranchBuildPanel({
  history,
  deployments,
  currentBuildId,
  activeBuildId,
  onSelect,
}: {
  history: BuildPage;
  deployments: DeploymentAttempt[];
  currentBuildId: string | null;
  activeBuildId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <details>
      <summary>构建与部署历史</summary>
      <p>
        来源：{history.source} · 同步时间：{history.syncedAt}
      </p>
      <ul>
        {history.items.map((build) => (
          <li key={build.buildId}>
            <Tag>{build.buildStatus}</Tag>
            <code>{build.buildId}</code> · {build.commitSha}{' '}
            {build.buildId === activeBuildId && <strong>当前有效部署</strong>}{' '}
            {build.error && (
              <span>
                {build.error.code}：{build.error.message}
              </span>
            )}
            <Button
              disabled={!build.catalogAvailable || build.buildId === currentBuildId}
              onClick={() => onSelect(build.buildId)}
            >
              查看固定构建
            </Button>
          </li>
        ))}
      </ul>
      <ul>
        {deployments.map((attempt) => (
          <li key={attempt.generation}>
            {attempt.buildId} · {attempt.deploymentStatus} · {attempt.reason}
          </li>
        ))}
      </ul>
    </details>
  );
}
