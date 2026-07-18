import { Button, Drawer, Form, Input, Radio, Select } from 'antd';
import { useEffect } from 'react';
import type { Dataset, Dimension, TimeGranularity } from '../../api/types';

type DimensionForm = {
  datasetId: string;
  datasetFieldId: string;
  name: string;
  displayName: string;
  type: Dimension['type'];
  allowedGranularities: TimeGranularity[];
};

export function DimensionDrawer({
  open,
  value,
  datasets,
  onClose,
  onSave,
}: {
  open: boolean;
  value?: Dimension;
  datasets: Dataset[];
  onClose: () => void;
  onSave: (value: DimensionForm) => Promise<void>;
}) {
  const [form] = Form.useForm<DimensionForm>();
  const datasetId = Form.useWatch('datasetId', form);
  const fieldId = Form.useWatch('datasetFieldId', form);
  const type = Form.useWatch('type', form);
  const dataset = datasets.find((item) => item.id === datasetId);
  const field = dataset?.fields.find((item) => item.id === fieldId);
  const temporal = field
    ? ['DATE', 'DATETIME', 'TIMESTAMP'].includes(field.physicalType.split('(')[0].toUpperCase())
    : false;
  useEffect(() => {
    form.resetFields();
    form.setFieldsValue(value ?? { type: 'STANDARD', allowedGranularities: [] });
  }, [open, value, form]);
  return (
    <Drawer
      open={open}
      width={520}
      title={value ? '编辑维度' : '新建维度'}
      onClose={onClose}
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
            onChange={() =>
              form.setFieldsValue({
                datasetFieldId: undefined,
                type: 'STANDARD',
                allowedGranularities: [],
              })
            }
          />
        </Form.Item>
        <Form.Item
          name="datasetFieldId"
          label="字段"
          rules={[{ required: true, message: '请选择字段' }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            disabled={!datasetId}
            placeholder="选择数据集中的已有字段"
            options={dataset?.fields
              .filter((item) => item.selected)
              .map((item) => ({
                value: item.id,
                label: `${item.displayName}（${item.physicalName} · ${item.physicalType}）`,
              }))}
            onChange={(selected) => {
              const selectedField = dataset?.fields.find((item) => item.id === selected);
              if (!isTemporal(selectedField?.physicalType))
                form.setFieldsValue({ type: 'STANDARD', allowedGranularities: [] });
            }}
          />
        </Form.Item>
        <Form.Item name="type" label="维度类型" rules={[{ required: true }]}>
          <Radio.Group
            options={[
              { value: 'STANDARD', label: '标准维度' },
              { value: 'TIME', label: '时间维度', disabled: !!field && !temporal },
            ]}
          />
        </Form.Item>
        {type === 'TIME' && (
          <Form.Item
            name="allowedGranularities"
            label="允许粒度"
            rules={[{ required: true, type: 'array', min: 1, message: '请至少选择一个时间粒度' }]}
          >
            <Select
              mode="multiple"
              options={[
                { value: 'DAY', label: '日' },
                { value: 'WEEK', label: '周' },
                { value: 'MONTH', label: '月' },
                { value: 'QUARTER', label: '季度' },
                { value: 'YEAR', label: '年' },
              ]}
            />
          </Form.Item>
        )}
        <Form.Item name="name" label="编码" rules={[{ required: true, message: '请输入维度编码' }]}>
          <Input />
        </Form.Item>
        <Form.Item
          name="displayName"
          label="显示名称"
          rules={[{ required: true, message: '请输入显示名称' }]}
        >
          <Input />
        </Form.Item>
      </Form>
    </Drawer>
  );
}

function isTemporal(type?: string) {
  return ['DATE', 'DATETIME', 'TIMESTAMP'].includes((type ?? '').split('(')[0].toUpperCase());
}
