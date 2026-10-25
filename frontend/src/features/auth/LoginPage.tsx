import {
  LockOutlined,
  UserOutlined,
  ArrowRightOutlined,
  ApartmentOutlined,
  BarChartOutlined,
} from '@ant-design/icons';
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

const COPY = {
  title: '从指标口径，到业务洞察。',
  description: '在同一版本中理解数据、追溯来源、验证你的分析。',
  resources: '理解每一个指标',
  resourcesHint: '定义、血缘与源码，让数据来源清晰可见。',
  analysis: '让分析自然发生',
  analysisHint: '选择指标与维度，用图表和明细回答问题。',
  welcome: '登录工作台',
  welcomeHint: '使用你的平台账号，继续资源管理与分析。',
  footer: 'Metric Platform · 指标与分析工作台',
};

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
      <section className="login-introduction">
        <div className="login-wordmark">
          <span className="platform-monogram">
            M<span>.</span>
          </span>
          Metric Platform
        </div>
        <div className="login-story">
          <h1>{COPY.title}</h1>
          <p>{COPY.description}</p>
          <div className="login-capability">
            <ApartmentOutlined aria-hidden />
            <div>
              <h2>{COPY.resources}</h2>
              <p>{COPY.resourcesHint}</p>
            </div>
          </div>
          <div className="login-capability">
            <BarChartOutlined aria-hidden />
            <div>
              <h2>{COPY.analysis}</h2>
              <p>{COPY.analysisHint}</p>
            </div>
          </div>
        </div>
        <span className="login-footer">{COPY.footer}</span>
      </section>
      <section className="login-form-panel">
        <Card className="login-card" variant="borderless">
          <div className="login-brand">
            <Typography.Title level={2}>{COPY.welcome}</Typography.Title>
            <Typography.Text type="secondary">{COPY.welcomeHint}</Typography.Text>
          </div>

          {expired && (
            <Alert className="login-alert" type="warning" showIcon title="登录已过期，请重新登录" />
          )}

          {error && <Alert className="login-alert" type="error" showIcon title={error} />}

          <Form<LoginValues> layout="vertical" requiredMark={false} onFinish={submit}>
            <Form.Item
              name="username"
              label="账号"
              rules={[{ required: true, whitespace: true, message: '请输入账号' }]}
            >
              <Input
                autoComplete="username"
                prefix={<UserOutlined aria-hidden />}
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
                prefix={<LockOutlined aria-hidden />}
                placeholder="请输入密码"
                size="large"
              />
            </Form.Item>

            <Button
              block
              htmlType="submit"
              loading={submitting}
              size="large"
              type="primary"
              icon={<ArrowRightOutlined aria-hidden />}
              iconPlacement="end"
            >
              登录
            </Button>
          </Form>
        </Card>
      </section>
    </main>
  );
}
