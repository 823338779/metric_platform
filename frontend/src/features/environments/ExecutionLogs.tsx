import { Alert, Button, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { EnvironmentApi, ExecutionLog } from '../../api/environmentApi';

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
const WARNING = new Set(['warn', 'warning']);
const POLL_MS = 1000;
const RETRY_MS = 3000;
const COPY = {
  title: '构建日志',
  refresh: '刷新日志',
  loading: '正在加载日志',
  empty: '暂无日志',
  failed: '日志读取失败',
};
const PRE_WRAP = 'pre-wrap';
const WARNING_COLOR = 'warning';
const DEFAULT_COLOR = 'default';

/** 固定构建内串行续读；终态后取完当前日志，未知结果继续观察。 */
export function ExecutionLogs({ buildId, api }: { buildId: string; api: EnvironmentApi }) {
  const [items, setItems] = useState<ExecutionLog[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const cursor = useRef<string | undefined>(undefined);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function read() {
      try {
        const build = await api.getBuild(buildId, controller.signal);
        let more = true;
        while (more && !controller.signal.aborted) {
          const page = await api.logs(buildId, cursor.current, controller.signal);
          if (controller.signal.aborted) return;
          setItems((previous) => {
            const seen = new Set(previous.map((row) => row.logId));
            return [
              ...previous,
              ...page.items.filter((row) => {
                if (seen.has(row.logId)) return false;
                seen.add(row.logId);
                return true;
              }),
            ];
          });
          cursor.current = page.nextCursor;
          more = page.hasMore;
        }
        if (controller.signal.aborted) return;
        setError('');
        setLoading(false);
        if (!TERMINAL.has(build.buildStatus)) timer = setTimeout(() => void read(), POLL_MS);
      } catch (failure) {
        if (controller.signal.aborted) return;
        setError(failure instanceof Error ? failure.message : COPY.failed);
        setLoading(false);
        timer = setTimeout(() => void read(), RETRY_MS);
      }
    }
    void read();
    return () => {
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [api, buildId, refresh]);
  return (
    <section aria-label={COPY.title} aria-busy={loading}>
      <Button onClick={() => setRefresh((value) => value + 1)}>{COPY.refresh}</Button>
      {error && <Alert type="error" title={error} />}
      {loading && <p role="status">{COPY.loading}</p>}
      {!loading && !items.length && <p role="status">{COPY.empty}</p>}
      {items.map((row) => (
        <div key={row.logId}>
          <time>{row.eventTime ? new Date(row.eventTime).toLocaleString() : row.createdAt}</time>{' '}
          <Tag color={WARNING.has(row.level) ? WARNING_COLOR : DEFAULT_COLOR}>{row.level}</Tag>{' '}
          <span>
            {row.phase} {row.nodeId}
          </span>
          <pre style={{ whiteSpace: PRE_WRAP }}>{row.message}</pre>
        </div>
      ))}
    </section>
  );
}
