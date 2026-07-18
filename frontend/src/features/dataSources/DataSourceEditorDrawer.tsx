import { Alert, Button, Drawer, Form, Input, Select, Space, Steps, message } from 'antd';
import { useEffect, useState } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type {
  CreateDataSourceRequest,
  DataSourceDetail,
  DatabaseType,
  UpdateDataSourceRequest,
} from '../../api/types';

type SourceForm = {
  name: string;
  description?: string;
  databaseType: DatabaseType;
  jdbcUrl?: string;
  username: string;
  password: string;
  defaultCatalog?: string;
  defaultSchema?: string;
};

export function DataSourceEditorDrawer({
  open,
  value,
  onClose,
  onSaved,
}: {
  open: boolean;
  value?: DataSourceDetail;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [form] = Form.useForm<SourceForm>();
  const [step, setStep] = useState(0);
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState<{
    successful: boolean;
    message: string;
    latencyMs: number;
  }>();
  useEffect(() => {
    if (!open) return;
    setStep(0);
    setTestResult(undefined);
    form.resetFields();
    form.setFieldsValue(
      value
        ? {
            name: value.name,
            description: value.description,
            databaseType: value.databaseType,
            jdbcUrl: value.jdbcUrl,
            username: value.username,
            password: value.password ?? '',
            defaultCatalog: value.defaultCatalog,
            defaultSchema: value.defaultSchema,
          }
        : { databaseType: 'STARROCKS', password: '' },
    );
  }, [open, value, form]);
  const next = async () => {
    await form.validateFields(step === 0 ? ['name', 'databaseType'] : connectionFields());
    setStep((current) => current + 1);
  };
  const test = async () => {
    setTesting(true);
    setTestResult(undefined);
    try {
      const values = await form.validateFields(connectionFields());
      const result = await metricPlatformApi.validateDataSourceConnection({
        databaseType: values.databaseType,
        jdbcUrl: values.jdbcUrl!,
        username: values.username,
        password: values.password,
        defaultCatalog: values.defaultCatalog,
        defaultSchema: values.defaultSchema,
      });
      setTestResult(result);
    } finally {
      setTesting(false);
    }
  };
  const save = async () => {
    const values = form.getFieldsValue(true);
    setSaving(true);
    try {
      if (value) {
        const body: UpdateDataSourceRequest = { ...values, version: value.version };
        await metricPlatformApi.updateDataSource(value.id, body);
      } else await metricPlatformApi.createDataSource(values as CreateDataSourceRequest);
      message.success(value ? '数据源已更新' : '数据源已创建');
      onSaved();
    } finally {
      setSaving(false);
    }
  };
  const content = [
    <>
      <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入数据源名称' }]}>
        <Input />
      </Form.Item>
      <Form.Item name="description" label="说明">
        <Input.TextArea rows={3} />
      </Form.Item>
      <Form.Item name="databaseType" label="数据库类型" rules={[{ required: true }]}>
        <Select
          options={[
            { value: 'STARROCKS', label: 'StarRocks' },
            { value: 'MYSQL', label: 'MySQL' },
            { value: 'POSTGRESQL', label: 'PostgreSQL' },
          ]}
        />
      </Form.Item>
    </>,
    <>
      <Form.Item
        name="jdbcUrl"
        label="JDBC 地址"
        rules={[{ required: !value, message: '请输入 JDBC 地址' }]}
      >
        <Input placeholder="jdbc:mysql://host:9030/database" />
      </Form.Item>
      <Form.Item
        name="username"
        label="用户名"
        rules={[{ required: true, message: '请输入用户名' }]}
      >
        <Input />
      </Form.Item>
      <Form.Item name="password" label="密码" extra="允许为空字符串">
        <Input.Password autoComplete="new-password" visibilityToggle />
      </Form.Item>
      <Space style={{ display: 'flex' }}>
        <Form.Item name="defaultCatalog" label="默认 Catalog" style={{ flex: 1 }}>
          <Input />
        </Form.Item>
        <Form.Item name="defaultSchema" label="默认 Schema" style={{ flex: 1 }}>
          <Input />
        </Form.Item>
      </Space>
    </>,
    <Space direction="vertical" style={{ display: 'flex' }}>
      <Alert
        showIcon
        type="info"
        message="保存前测试连接"
        description="测试使用只读连接，不会执行源表写入。"
      />
      <Button loading={testing} onClick={() => void test()}>
        测试连接
      </Button>
      {testResult && (
        <Alert
          showIcon
          type={testResult.successful ? 'success' : 'error'}
          message={`${testResult.message}（${testResult.latencyMs} ms）`}
        />
      )}
    </Space>,
  ][step];
  return (
    <Drawer
      width={600}
      open={open}
      onClose={onClose}
      title={value ? '编辑数据源' : '新建数据源'}
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          {step > 0 && <Button onClick={() => setStep((current) => current - 1)}>上一步</Button>}
          {step < 2 ? (
            <Button type="primary" onClick={() => void next()}>
              下一步
            </Button>
          ) : (
            <Button
              type="primary"
              loading={saving}
              disabled={!testResult?.successful}
              onClick={() => void save()}
            >
              保存
            </Button>
          )}
        </Space>
      }
    >
      <Steps
        current={step}
        items={['基本信息', '连接配置', '测试确认'].map((title) => ({ title }))}
        style={{ marginBottom: 28 }}
      />
      <Form form={form} layout="vertical">
        {content}
      </Form>
    </Drawer>
  );
}

function connectionFields(): (keyof SourceForm)[] {
  return ['databaseType', 'jdbcUrl', 'username', 'password'];
}
