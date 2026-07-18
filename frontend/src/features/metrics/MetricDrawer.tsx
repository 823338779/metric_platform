import { Button, Drawer, Form, Input, Select } from 'antd';
import { useEffect } from 'react';
import type { Dataset, Metric } from '../../api/types';

type MetricForm = {
  datasetId: string;
  datasetFieldId?: string;
  name: string;
  displayName: string;
  aggregation: Metric['aggregation'];
  aliases: string[];
};

export function MetricDrawer({
  open,
  value,
  datasets,
  onClose,
  onSave,
}: {
  open: boolean;
  value?: Metric;
  datasets: Dataset[];
  onClose: () => void;
  onSave: (value: MetricForm) => Promise<void>;
}) {
  const [form] = Form.useForm<MetricForm>();
  const datasetId = Form.useWatch('datasetId', form);
  const aggregation = Form.useWatch('aggregation', form);
  const dataset = datasets.find((item) => item.id === datasetId);
  useEffect(() => {
    form.resetFields();
    form.setFieldsValue(value ?? { aggregation: 'COUNT' });
  }, [value, open, form]);
  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={value ? '编辑指标' : '新建指标'}
      width={520}
      extra={
        <Button type="primary" onClick={() => void form.validateFields().then(onSave)}>
          保存
        </Button>
      }
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="datasetId"
          label="数据集"
          rules={[{ required: true, message: '请选择数据集' }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            disabled={!!value}
            placeholder="选择已有数据集"
            options={datasets.map((item) => ({ value: item.id, label: item.name }))}
            onChange={() => form.setFieldValue('datasetFieldId', undefined)}
          />
        </Form.Item>
        <Form.Item name="aggregation" label="聚合方式" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'COUNT', label: '计数（COUNT）' },
              { value: 'COUNT_DISTINCT', label: '去重计数（COUNT DISTINCT）' },
              { value: 'SUM', label: '求和（SUM）' },
              { value: 'AVG', label: '平均值（AVG）' },
              { value: 'MIN', label: '最小值（MIN）' },
              { value: 'MAX', label: '最大值（MAX）' },
            ]}
          />
        </Form.Item>
        <Form.Item
          name="datasetFieldId"
          label="字段"
          rules={[{ required: aggregation !== 'COUNT', message: '请选择聚合字段' }]}
          extra={aggregation === 'COUNT' ? '可留空，表示 COUNT(*)' : ''}
        >
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            disabled={!datasetId}
            placeholder="选择数据集中的已有字段"
            options={dataset?.fields
              .filter((field) => field.selected)
              .map((field) => ({
                value: field.id,
                label: `${field.displayName}（${field.physicalName} · ${field.physicalType}）`,
              }))}
          />
        </Form.Item>
        <Form.Item name="name" label="编码" rules={[{ required: true, message: '请输入指标编码' }]}>
          <Input />
        </Form.Item>
        <Form.Item
          name="displayName"
          label="显示名称"
          rules={[{ required: true, message: '请输入显示名称' }]}
        >
          <Input />
        </Form.Item>
        <Form.Item name="aliases" label="别名">
          <Select mode="tags" tokenSeparators={[',', '，']} placeholder="输入别名后按回车" />
        </Form.Item>
      </Form>
    </Drawer>
  );
}
