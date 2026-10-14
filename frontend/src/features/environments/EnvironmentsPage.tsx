import { Alert, Button, Card, Empty } from 'antd';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  environmentApi,
  type EnvironmentApi,
  type EnvironmentInput,
  type ExecutionEnvironment,
} from '../../api/environmentApi';
import { EnvironmentForm } from './EnvironmentForm';

export function EnvironmentsPage({ api = environmentApi }: { api?: EnvironmentApi }) {
  const [items, setItems] = useState<ExecutionEnvironment[]>([]);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    api
      .list()
      .then((page) => {
        if (active) setItems(page.items);
      })
      .catch((reason) => {
        if (active) setError(String(reason.message));
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
      <header>
        <div>
          <h2>执行环境</h2>
          <p>环境决定构建的物理落点；仓库由服务器统一配置，每次构建选择分支。</p>
        </div>
        <Button type="primary" onClick={() => setEditing(!editing)}>
          新建环境
        </Button>
      </header>
      {error && <Alert type="error" title={error} />}
      {editing && (
        <Card title="新建执行环境">
          <EnvironmentForm busy={busy} onSave={(input) => void save(input)} />
        </Card>
      )}
      {!items.length && !editing && <Empty description="尚未配置执行环境" />}
      {items.map((item) => (
        <Card
          key={item.environmentId}
          title={<Link to={`/dbt/environments/${item.environmentId}`}>{item.name}</Link>}
        >
          <p>
            {item.execution.connection.instanceId} / {item.execution.catalog} /{' '}
            {item.execution.schema}
          </p>
          <span>配置版本 {item.configVersion}</span>
        </Card>
      ))}
      <Link to="/dbt/projects">查看旧版部署记录</Link>
    </section>
  );
}
