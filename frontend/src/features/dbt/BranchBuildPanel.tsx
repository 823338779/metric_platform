import { Alert, Tag } from 'antd';
import type { Publication } from '../../api/publishedCatalogApi';

const FAILED = 'FAILED';

/** 构建历史与活动指针分开显示，失败提交不会冒充旧可用版本。 */
export function BranchBuildPanel({
  releases,
  currentReleaseId,
  activeReleaseId,
  latestReleaseId,
  onSelect,
}: {
  releases: Publication[];
  currentReleaseId: string | null;
  activeReleaseId: string | null;
  /** 最新尝试独立于当前可用版本，失败时必须持续可见。 */
  latestReleaseId?: string | null;
  onSelect?(id: string): void;
}) {
  const current = releases.find((item) => item.releaseId === currentReleaseId);
  const latest = releases.find((item) => item.releaseId === latestReleaseId);
  return (
    <details open={current?.state === FAILED || latest?.state === FAILED}>
      <summary>
        构建与测试 · 可用版本 {activeReleaseId ?? '暂无'} · 最新构建 {latest?.state ?? '暂无'}
      </summary>
      {releases.length === 0 && <p>等待首次预览构建；Git 推送成功不表示预览就绪。</p>}
      <ul>
        {releases.map((release) => (
          <li key={release.releaseId}>
            <button
              type="button"
              disabled={!onSelect || release.releaseId === currentReleaseId}
              onClick={() => onSelect?.(release.releaseId)}
            >
              {release.releaseId}
            </button>{' '}
            <code>{release.sourceSha}</code>{' '}
            <Tag color={release.state === 'FAILED' ? 'red' : undefined}>{release.state}</Tag>
            {release.errorCode && <p>错误码：{release.errorCode}</p>}
            {release.validationSummary?.phase && <p>执行阶段：{release.validationSummary.phase}</p>}
            {release.validationSummary && (
              <ul>
                {release.validationSummary.checks.map((check, index) => (
                  <li key={`${check.name}:${index}`}>
                    {check.name}：{check.status}
                    {check.message && <p>{check.message}</p>}
                  </li>
                ))}
              </ul>
            )}
            {release.validationSummary?.truncated && (
              <Alert type="warning" title="验证摘要已截断" />
            )}
          </li>
        ))}
      </ul>
    </details>
  );
}
