import { Alert, Button, Card, Table, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtProject } from '../../api/types';
import { ApiProblem } from '../../api/httpClient';

const REFRESH_LABEL = '手动刷新';
const REFRESH_ACCEPTED = '刷新请求已受理，等待后台同步和发布；当前版本仍可使用。';
const REFRESH_FAILED = '刷新请求失败，请稍后重试';
const EMPTY = '';
const INFO = 'info';
const STATUS_POLL_MS = 5000;
const STATUS_FAILED = '项目状态读取失败';

/** Git 观测提交与当前已发布版本分别显示，避免把 push 误当发布。 */
export function DbtProjectsPage({
  api = metricPlatformApi,
}: {
  api?: Pick<MetricPlatformApi, 'listDbtProjects' | 'refreshDbtProjects'>;
}) {
  const [projects, setProjects] = useState<DbtProject[]>([]);
  const [error, setError] = useState('');
  // 提交状态与发布状态分开，避免将受理提示误当成发布成功。
  const [refreshing, setRefreshing] = useState(false);
  const [refreshAccepted, setRefreshAccepted] = useState(false);
  async function refresh() {
    setRefreshing(true);
    setRefreshAccepted(false);
    setError(EMPTY);
    try {
      await api.refreshDbtProjects();
      setRefreshAccepted(true);
    } catch (failure) {
      setError(failure instanceof ApiProblem ? failure.message : REFRESH_FAILED);
    } finally {
      setRefreshing(false);
    }
  }
  useEffect(() => {
    // 受理后持续读取发布状态；串行请求，并在离开页面时停止。
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    async function load() {
      try {
        const latest = await api.listDbtProjects();
        if (active) setProjects(latest);
      } catch {
        if (active) setError(STATUS_FAILED);
      } finally {
        if (active && refreshAccepted) timer = setTimeout(() => void load(), STATUS_POLL_MS);
      }
    }
    void load();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [api, refreshAccepted]);
  return (
    <Card
      title="语义项目"
      extra={
        <Button loading={refreshing} disabled={refreshing} onClick={() => void refresh()}>
          {REFRESH_LABEL}
        </Button>
      }
    >
      {error && <Alert type="error" title={error} />}
      {refreshAccepted && <Alert type={INFO} title={REFRESH_ACCEPTED} />}
      <Table
        rowKey="projectId"
        dataSource={projects}
        pagination={false}
        columns={[
          { title: '项目', dataIndex: 'projectId' },
          {
            title: '已观测 main SHA',
            dataIndex: 'observedSha',
            render: (value: string | null) => value ?? '尚未观测',
          },
          {
            title: '当前发布 releaseId',
            dataIndex: 'activeReleaseId',
            render: (value: string | null) => value ?? '尚未发布',
          },
          { title: '观测状态', dataIndex: 'lastObservationStatus' },
          {
            title: '目录',
            render: (_, row) =>
              row.activeReleaseId ? (
                <Link
                  to={`/dbt/projects/${encodeURIComponent(row.projectId)}/catalog?releaseId=${row.activeReleaseId}`}
                >
                  查看目录
                </Link>
              ) : (
                <Typography.Text type="secondary">等待发布</Typography.Text>
              ),
          },
        ]}
      />
    </Card>
  );
}
