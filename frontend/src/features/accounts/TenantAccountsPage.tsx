import { useState, type FormEvent } from 'react';
import { Button, Card, Input, Space } from 'antd';
import { createHttpClient, ApiProblem } from '../../api/httpClient';
import { getAuthSession } from '../../api/auth';
import { AssetGrantPanel } from './AssetAccess';

export const ACCOUNTS_PATH = '/tenant/accounts';
const API_PATH = '/tenant/accounts';

export function TenantAccountsPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  if (getAuthSession()?.identity.role !== 'TENANT_MANAGER') return <p>无权管理账号</p>;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setMessage('');
    try {
      await createHttpClient()(API_PATH, {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      });
      setPassword('');
      setUsername('');
      setMessage('只读账号已创建');
    } catch (error) {
      setMessage(
        error instanceof ApiProblem && error.problem.status === 409
          ? '登录名已被使用'
          : '创建失败，请重试',
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card title="账号管理">
      <form onSubmit={submit}>
        <Space orientation="vertical">
          <label>
            登录名
            <Input
              required
              maxLength={120}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label>
            初始密码
            <Input
              type="password"
              autoComplete="new-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
          <Button htmlType="submit" type="primary" loading={saving}>
            创建只读账号
          </Button>
          <span role="status">{message}</span>
        </Space>
      </form>
      <AssetGrantPanel />
    </Card>
  );
}
