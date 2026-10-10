import { Alert, Tag } from 'antd';
import type { BranchList, Environment } from '../../api/buildCatalogApi';

const LABELS = {
  branch: '项目分支',
  choose: '请选择',
  cached: '分支列表来自历史缓存，无法确认实时分支状态。',
  agent: '在 Agent 中开发与交付',
};
/** 项目/分支来自 Agent；缓存状态和来源时间明确展示。 */
export function BranchContextBar({
  branches,
  repository,
  branchName,
  environment,
  onSelect,
  projectId,
}: {
  branches: BranchList;
  repository: string;
  branchName: string;
  environment: Environment;
  onSelect: (name: string) => void;
  projectId: string;
}) {
  let link: string | null = null;
  try {
    const base = import.meta.env.VITE_AGENT_URL;
    if (base) {
      const url = new URL(base);
      if (url.protocol === 'https:' || url.protocol === 'http:') {
        url.search = new URLSearchParams({ projectId, branchName }).toString();
        link = url.toString();
      }
    }
  } catch {
    /* 非HTTP地址不导航。 */
  }
  return (
    <section aria-label="分支上下文">
      <Tag>{environment}</Tag>
      <span>{repository}</span>
      <label>
        {LABELS.branch}
        <select
          aria-label={LABELS.branch}
          value={branchName}
          onChange={(event) => onSelect(event.target.value)}
        >
          <option value="">{LABELS.choose}</option>
          {branches.items.map((item) => (
            <option key={item.branchName} value={item.branchName}>
              {item.branchName}
            </option>
          ))}
        </select>
      </label>
      <p>
        {branches.source} · {branches.observedAt}
      </p>
      {branches.cached && <Alert type="warning" title={LABELS.cached} />}{' '}
      {link && (
        <a href={link} target="_blank" rel="noopener noreferrer">
          {LABELS.agent}
        </a>
      )}
    </section>
  );
}
