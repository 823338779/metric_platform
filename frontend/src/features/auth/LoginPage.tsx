import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { safeReturnTo, saveAuthSession } from '../../api/auth';
import { authApi } from '../../api/authApi';
import { ApiProblem } from '../../api/httpClient';

interface LoginValues {
  username: string;
  password: string;
}

export function LoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const returnTo = safeReturnTo(searchParams.get('returnTo'));
  const expired = searchParams.get('reason') === 'expired';

  async function submit(values: LoginValues) {
    setSubmitting(true);
    setError(null);

    try {
      const session = await authApi.login(values.username.trim(), values.password);
      saveAuthSession(session);
      navigate(returnTo, { replace: true });
    } catch (failure) {
      setError(
        failure instanceof ApiProblem && failure.problem.status === 401
          ? '账号或密码错误'
          : '登录失败，请确认服务可用后重试',
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page">
      <Card className="login-card" bordered={false}>
        <div className="login-brand">
          <Typography.Title level={2}>Metric Platform</Typography.Title>
          <Typography.Text type="secondary">登录后管理数据源与语义指标</Typography.Text>
        </div>

        {expired && (
          <Alert className="login-alert" type="warning" showIcon message="登录已过期，请重新登录" />
        )}

        {error && <Alert className="login-alert" type="error" showIcon message={error} />}

        <Form<LoginValues> layout="vertical" requiredMark={false} onFinish={submit}>
          <Form.Item
            name="username"
            label="账号"
            rules={[{ required: true, whitespace: true, message: '请输入账号' }]}
          >
            <Input
              autoComplete="username"
              prefix={<UserOutlined />}
              placeholder="请输入账号"
              size="large"
            />
          </Form.Item>

          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              autoComplete="current-password"
              prefix={<LockOutlined />}
              placeholder="请输入密码"
              size="large"
            />
          </Form.Item>

          <Button block htmlType="submit" loading={submitting} size="large" type="primary">
            登录
          </Button>
        </Form>
      </Card>
    </main>
  );
}
