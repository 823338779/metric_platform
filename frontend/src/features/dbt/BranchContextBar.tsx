import { Alert, Space, Tag } from 'antd';
import type { BranchView } from '../../api/publishedCatalogApi';

/** 只改变 URL 选择；活动版本推进不会静默替换页面固定版本。 */
export function BranchContextBar({
  branches,
  branch,
  availableSha,
  onSelect,
}: {
  branches: BranchView[];
  branch: BranchView;
  availableSha?: string;
  onSelect(id: string): void;
}) {
  // 链接来自服务受控地址，只允许普通 HTTP(S) 导航。
  let mergeUrl: string | null = null;
  try {
    if (
      branch.mergeRequestUrl &&
      ['https:', 'http:'].includes(new URL(branch.mergeRequestUrl).protocol)
    )
      mergeUrl = branch.mergeRequestUrl;
  } catch {
    /* 无效地址不展示 */
  }
  return (
    <section aria-label="分支上下文">
      <Space wrap>
        <Tag color={branch.mode === 'PRODUCTION' ? 'green' : 'blue'}>
          {branch.mode === 'PRODUCTION' ? '线上' : '开发预览'}
        </Tag>
        <label>
          项目分支{' '}
          <select
            aria-label="项目分支"
            value={branch.branchId}
            onChange={(event) => onSelect(event.target.value)}
          >
            {!branches.some((item) => item.branchId === branch.branchId) && (
              <option value={branch.branchId}>
                {branch.gitRef} · {branch.status}
              </option>
            )}
            {branches.map((item) => (
              <option key={item.branchId} value={item.branchId} disabled={item.status !== 'ACTIVE'}>
                {item.gitRef} · {item.mode === 'PRODUCTION' ? '线上' : '开发'}
              </option>
            ))}
          </select>
        </label>
        {mergeUrl && (
          <a href={mergeUrl} target="_blank" rel="noopener noreferrer">
            查看合并请求
          </a>
        )}
      </Space>
      <p>
        可用提交：<code>{availableSha ?? '尚无可用预览'}</code> · 最新提交：
        <code>{branch.observedHeadSha ?? '尚未观测'}</code>
      </p>
      {branch.status !== 'ACTIVE' && (
        <Alert
          type="warning"
          title={`原分支不可用：${branch.status}，同名新分支不会替代此链接。`}
        />
      )}
    </section>
  );
}
