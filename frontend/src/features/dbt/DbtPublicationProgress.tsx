import { Alert, Collapse, Descriptions, Space, Steps, Table, Tag, Typography } from 'antd';
import type { DbtProject, DbtRelease } from '../../api/types';

const STATE = {
  preparing: 'PREPARING',
  building: 'BUILDING',
  validating: 'VALIDATING',
  ready: 'READY',
  failed: 'FAILED',
  published: 'PUBLISHED',
  superseded: 'SUPERSEDED',
};
const SKIPPED = 'SKIPPED_UNCHANGED';
const STEPS = ['同步远端 Git', '准备构建', '构建与校验', '切换发布'];
const ERROR_HELP: Record<string, { title: string; detail: string; step: number }> = {
  RUN_FAILED: {
    title: '构建或校验失败',
    detail:
      '运行服务未提供具体子阶段和日志，请检查 dbt-service 中该版本的构建记录。修复定义并推送新提交后再刷新。',
    step: 2,
  },
  SERVICE_REJECTED: {
    title: '运行服务拒绝任务',
    detail: '请检查项目绑定、运行参数和服务日志；服务端未提供更具体的失败环节。',
    step: 2,
  },
  TEMPORARY_FAILURE: {
    title: '运行服务通信失败',
    detail: '自动重试后仍无法完成通信，请检查 dbt-service 是否启动及网络连接。',
    step: 2,
  },
  INVALID_RUN: {
    title: '发布校验未通过',
    detail:
      '运行产物、目标版本或发布条件不符合要求，尚未切换当前版本。请检查运行服务产物和平台日志。',
    step: 3,
  },
  UNKNOWN_RUN_STATE: {
    title: '运行服务返回未知状态',
    detail: '请检查运行服务与指标平台的接口版本是否一致。',
    step: 2,
  },
};
const UNKNOWN_ERROR = {
  title: '发布流程失败',
  detail: '服务端未提供可定位的失败环节，请结合错误码检查平台与运行服务日志。',
  step: 2,
};
const LABELS: Record<string, string> = {
  PREPARING: '准备构建',
  BUILDING: '构建与校验中',
  VALIDATING: '校验中',
  READY: '等待发布',
  PUBLISHED: '已发布',
  FAILED: '失败',
  SUPERSEDED: '已被新版本替代',
  PENDING: '保留（未清理）',
  CLEANING: '清理中',
  CLEANED: '已清理',
};
const DASH = '—';
const DATE_LOCALE = 'zh-CN';

/** 只映射已知状态，不按耗时猜测进度或虚构失败子阶段。 */
export function DbtPublicationProgress({
  project,
  releases,
  waiting,
  loadError,
}: {
  project: DbtProject;
  releases: DbtRelease[];
  waiting: boolean;
  loadError?: string;
}) {
  const latest = releases[0];
  let title = '尚未创建发布任务';
  let description = '点击手动刷新后，平台会读取远端 Git 的主分支并判断是否需要构建。';
  let current = 0;
  let status: 'process' | 'error' | 'finish' = 'process';
  let tone: 'info' | 'error' | 'success' | 'warning' = 'info';
  if (waiting) {
    title = '等待后台读取远端 Git';
    description =
      '请求已经受理，尚未观测到新的处理结果。下方历史记录不代表本次刷新结果；若持续无变化，请检查平台 Git 同步日志。';
  } else if (latest) {
    switch (latest.state) {
      case STATE.preparing:
        current = 1;
        title = '正在准备构建';
        description = '已读取远端版本，正在向运行服务提交任务或确认受理结果。';
        break;
      case STATE.building:
      case STATE.validating:
        current = 2;
        title = '正在构建与校验';
        description = '正在执行 dbt 构建及发布前校验。当前接口未分别提供这两个子阶段的实时进度。';
        break;
      case STATE.ready:
        current = 3;
        title = '正在切换发布';
        description = '运行服务已就绪，平台正在核对远端版本并导入目录。';
        break;
      case STATE.failed: {
        const failure = ERROR_HELP[latest.errorCode ?? DASH] ?? UNKNOWN_ERROR;
        current = failure.step;
        title = failure.title;
        description = failure.detail;
        status = 'error';
        tone = 'error';
        break;
      }
      case STATE.published:
        current = 3;
        status = 'finish';
        tone = 'success';
        title = project.lastObservationStatus === SKIPPED ? '本次无需重新构建' : '发布成功';
        description =
          project.lastObservationStatus === SKIPPED
            ? '远端 SHA 与构建配置未变化，继续使用已发布版本。'
            : '新版本已经生效，可以查看当前目录；空版本的目录为空。';
        break;
      case STATE.superseded:
        current = 3;
        tone = 'warning';
        title = '该候选已被新版本替代';
        description = '此候选不会覆盖较新的版本，请查看最新发布记录。';
        break;
      default:
        title = '暂无法识别发布阶段';
        description = '服务端返回了未知状态，请查看历史记录中的原始状态。';
    }
  }
  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      {loadError && (
        <Alert
          type="warning"
          title="发布状态读取失败"
          description="以下为上次读取的记录，不能据此判断最新进度。页面会自动重试。"
        />
      )}
      <Steps
        size="small"
        current={current}
        status={status}
        items={STEPS.map((step) => ({ title: step }))}
      />
      <Alert
        type={tone}
        showIcon
        title={title}
        description={
          <>
            {description}
            {latest?.state === STATE.failed && !waiting && (
              <div>
                {project.activeReleaseId
                  ? '当前仍使用上一个成功发布版本。'
                  : '当前尚无成功发布版本。'}{' '}
                {latest.errorCode && <Typography.Text code>{latest.errorCode}</Typography.Text>}
              </div>
            )}
          </>
        }
      />
      <Descriptions
        size="small"
        column={{ xs: 1, sm: 2 }}
        items={[
          {
            key: 'sha',
            label: '远端已观测 SHA',
            children: <Typography.Text code>{project.observedSha ?? '尚未观测'}</Typography.Text>,
          },
          {
            key: 'release',
            label: '当前生效版本',
            children: (
              <Typography.Text code>{project.activeReleaseId ?? '尚未发布'}</Typography.Text>
            ),
          },
          {
            key: 'observed',
            label: '最近观测时间',
            children: project.lastObservedAt
              ? new Date(project.lastObservedAt).toLocaleString(DATE_LOCALE)
              : DASH,
          },
        ]}
      />
      <Alert
        type="info"
        showIcon
        title="旧物理表保留"
        description="构建由 dbt 执行。删除定义或发布空目录不会自动删除以前的物理表；旧构建和发布历史保留。"
      />
      <Collapse
        items={[
          {
            key: 'history',
            label: `发布历史与错误详情（最近 ${releases.length} 条，最多 100 条）`,
            children: (
              <Table<DbtRelease>
                size="small"
                rowKey="releaseId"
                dataSource={releases}
                pagination={{ pageSize: 5 }}
                scroll={{ x: 720 }}
                columns={[
                  {
                    title: '创建时间',
                    dataIndex: 'createdAt',
                    render: (value: string) => new Date(value).toLocaleString(DATE_LOCALE),
                  },
                  { title: '目标 SHA', dataIndex: 'targetCommitSha', ellipsis: true },
                  {
                    title: '发布状态',
                    dataIndex: 'state',
                    render: (value: string) => (
                      <Tag
                        color={
                          value === STATE.failed
                            ? 'error'
                            : value === STATE.published
                              ? 'success'
                              : 'processing'
                        }
                      >
                        {LABELS[value] ?? value}
                      </Tag>
                    ),
                  },
                  {
                    title: '清理状态',
                    render: (_, item) =>
                      item.releaseId === project.activeReleaseId
                        ? '当前版本保留'
                        : (LABELS[item.cleanupState] ?? item.cleanupState),
                  },
                  {
                    title: '错误信息',
                    render: (_, item) =>
                      item.errorCode
                        ? `${ERROR_HELP[item.errorCode]?.title ?? UNKNOWN_ERROR.title} (${item.errorCode})`
                        : DASH,
                  },
                ]}
              />
            ),
          },
        ]}
      />
    </Space>
  );
}
