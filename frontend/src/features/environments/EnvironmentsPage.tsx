import { getAuthSession } from '../../api/auth';
import { Alert, Button, Empty, Skeleton } from 'antd';
import { ArrowRightOutlined, PlusOutlined, DatabaseOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  environmentApi,
  type EnvironmentApi,
  type EnvironmentInput,
  type ExecutionEnvironment,
} from '../../api/environmentApi';
import { EnvironmentForm } from './EnvironmentForm';

const COPY = {
  description: '管理构建的执行目标，从这里发布并探索你的指标。',
  list: '环境列表',
  name: '环境名称',
  adapter: '数据库',
  target: '执行目标',
  version: '配置版本',
  action: '操作',
  enter: '查看构建',
  cancel: '取消新建',
  loading: '正在加载环境',
  empty: '新建一个执行环境，连接构建与指标分析。',
};
const ADAPTER_NAMES: Record<string, string> = {
  starrocks: 'StarRocks',
  postgres: 'PostgreSQL',
  duckdb: 'DuckDB',
};

export function EnvironmentsPage({ api = environmentApi }: { api?: EnvironmentApi }) {
  const manager = getAuthSession()?.identity.role === 'TENANT_MANAGER';
  const [items, setItems] = useState<ExecutionEnvironment[]>([]);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let active = true;
    api
      .list()
      .then((page) => {
        if (active) setItems(page.items);
      })
      .catch((reason) => {
        if (active) setError(String(reason.message));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [api]);
  async function save(input: EnvironmentInput) {
    setBusy(true);
    setError('');
    try {
      const item = await api.create(input);
      setItems((previous) => [...previous, item]);
      setEditing(false);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '环境保存失败');
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="environment-page">
      <header className="page-heading">
        <div>
          <h2>执行环境</h2>
          <p>{manager ? COPY.description : '选择执行环境，浏览已构建的资源。'}</p>
        </div>
        {manager && (
          <Button
            type="primary"
            icon={<PlusOutlined aria-hidden />}
            onClick={() => setEditing(true)}
          >
            新建环境
          </Button>
        )}
      </header>
      {error && <Alert type="error" title={error} />}
      {manager && editing && (
        <section className="management-panel environment-editor">
          <header className="panel-heading">
            <h3>新建执行环境</h3>
            <Button type="text" onClick={() => setEditing(false)}>
              {COPY.cancel}
            </Button>
          </header>
          <EnvironmentForm busy={busy} onSave={(input) => void save(input)} />
        </section>
      )}
      <section className="management-panel">
        <header className="panel-heading">
          <h3>
            {COPY.list}
            <span className="count-label">{items.length}</span>
          </h3>
        </header>
        {loading ? (
          <div className="panel-loading" aria-label={COPY.loading}>
            <Skeleton active />
          </div>
        ) : !items.length ? (
          <Empty description={manager ? COPY.empty : '暂无执行环境'} />
        ) : (
          <div className="management-table-scroll">
            <table className="management-table">
              <thead>
                <tr>
                  <th>{COPY.name}</th>
                  <th>{COPY.adapter}</th>
                  <th>{COPY.target}</th>
                  <th>{COPY.version}</th>
                  <th>{COPY.action}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.environmentId}>
                    <td>
                      <Link
                        className="environment-name"
                        to={`/dbt/environments/${item.environmentId}`}
                      >
                        <DatabaseOutlined aria-hidden />
                        {item.name}
                      </Link>
                      <small>{item.execution.connection.instanceId}</small>
                    </td>
                    <td>
                      {ADAPTER_NAMES[item.execution.connection.type] ??
                        item.execution.connection.type}
                    </td>
                    <td>
                      <span>{item.execution.catalog}</span>
                      <small>{item.execution.schema}</small>
                    </td>
                    <td>
                      <span className="neutral-badge">v{item.configVersion}</span>
                    </td>
                    <td>
                      <Link
                        to={`/dbt/environments/${item.environmentId}`}
                        aria-label={`${COPY.enter}：${item.name}`}
                      >
                        {COPY.enter} <ArrowRightOutlined aria-hidden />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </section>
  );
}
