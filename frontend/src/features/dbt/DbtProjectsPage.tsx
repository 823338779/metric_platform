import { Alert, Card, Table, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { metricPlatformApi, type MetricPlatformApi } from '../../api/metricPlatformApi';
import type { DbtProject } from '../../api/types';

/** Git 观测提交与当前已发布版本分别显示，避免把 push 误当发布。 */
export function DbtProjectsPage({
  api = metricPlatformApi,
}: {
  api?: Pick<MetricPlatformApi, 'listDbtProjects'>;
}) {
  const [projects, setProjects] = useState<DbtProject[]>([]);
  const [error, setError] = useState('');
  useEffect(() => {
    void api
      .listDbtProjects()
      .then(setProjects)
      .catch(() => setError('项目状态读取失败'));
  }, [api]);
  return (
    <Card title="dbt 项目">
      {error && <Alert type="error" title={error} />}
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
