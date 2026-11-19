import { useState, type FormEvent } from 'react';
import { Alert, Button, Input, Modal } from 'antd';
import { ApartmentOutlined, PlusOutlined } from '@ant-design/icons';
import { createHttpClient, ApiProblem } from '../../api/httpClient';
import { getAuthSession, type AuthIdentity } from '../../api/auth';
import { AssetGrantPanel } from './AssetAccess';
import './accounts.css';

export const ACCOUNTS_PATH = '/tenant/accounts';

export function TenantAccountsPage() {
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<AuthIdentity | null>(null);
  const identity = getAuthSession()?.identity;
  if (identity?.role !== 'TENANT_MANAGER') return <p>无权访问此页面</p>;

  return (
    <section className="accounts-page" aria-labelledby="accounts-title">
      <header className="accounts-heading">
        <div>
          <h1 id="accounts-title">账号管理</h1>
          <p>
            <ApartmentOutlined aria-hidden /> 当前租户 <code>{identity.tenant_id}</code>
          </p>
        </div>
        <Button
          type="primary"
          icon={<PlusOutlined aria-hidden />}
          onClick={() => setCreating(true)}
        >
          新建账号
        </Button>
      </header>
      {created && <Alert type="success" showIcon role="status" title="只读账号已创建" />}
      <AssetGrantPanel key={created?.identityId} initialId={created?.identityId} />
      {creating && (
        <CreateAccount
          onCancel={() => setCreating(false)}
          onCreated={(account) => {
            setCreated(account);
            setCreating(false);
          }}
        />
      )}
    </section>
  );
}

function CreateAccount({
  onCancel,
  onCreated,
}: {
  onCancel: () => void;
  onCreated: (account: AuthIdentity) => void;
}) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (saving) return;
    setSaving(true);
    setMessage('');
    try {
      const account = await createHttpClient()<AuthIdentity>(ACCOUNTS_PATH, {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      });
      onCreated(account);
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
    <Modal
      open
      title="新建只读账号"
      onCancel={onCancel}
      footer={null}
      closable={!saving}
      keyboard={!saving}
      mask={{ closable: !saving }}
      width={440}
    >
      <form className="account-form" onSubmit={submit}>
        <p>新账号默认没有资产权限，创建后可逐项授权。</p>
        <label htmlFor="reader-name">登录名</label>
        <Input
          id="reader-name"
          autoFocus
          required
          maxLength={120}
          autoComplete="off"
          placeholder="输入全平台唯一的登录名"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
        />
        <label htmlFor="reader-password">初始密码</label>
        <Input.Password
          id="reader-password"
          required
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        {message && <Alert type="error" showIcon role="status" title={message} />}
        <footer>
          <Button onClick={onCancel} disabled={saving}>
            取消
          </Button>
          <Button htmlType="submit" type="primary" loading={saving}>
            创建只读账号
          </Button>
        </footer>
      </form>
    </Modal>
  );
}
