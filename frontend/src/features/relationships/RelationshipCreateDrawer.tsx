import { Button, Divider, Drawer, Form, Input, Select, Space, Typography, message } from 'antd';
import type { FormInstance } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { CreateRelationshipRequest, Dataset, DatasetField } from '../../api/types';

export function RelationshipCreateDrawer({
  open,
  sourceDataset,
  datasets,
  onClose,
  onCreated,
}: {
  open: boolean;
  sourceDataset: Dataset;
  datasets: Dataset[];
  onClose: () => void;
  onCreated: () => void;
}) {
  const [form] = Form.useForm<CreateRelationshipRequest>();
  const targetDatasetId = Form.useWatch('targetDatasetId', form);
  const targetDataset = datasets.find((dataset) => dataset.id === targetDatasetId);
  const targetOptions = datasets
    .filter(
      (dataset) =>
        dataset.id !== sourceDataset.id && dataset.dataSourceId === sourceDataset.dataSourceId,
    )
    .map((dataset) => ({ value: dataset.id, label: dataset.name }));
  const sourceFields = sourceDataset.fields.filter((field) => field.selected);
  const targetFields = (targetDataset?.fields ?? []).filter((field) => field.selected);

  const close = () => {
    form.resetFields();
    onClose();
  };
  const submit = async () => {
    const values = await form.validateFields();
    await metricPlatformApi.createRelationship({ ...values, sourceDatasetId: sourceDataset.id });
    message.success('数据集关系已创建并校验');
    form.resetFields();
    onCreated();
  };

  return (
    <Drawer
      width={640}
      open={open}
      onClose={close}
      title="新建数据集关系"
      extra={
        <Space>
          <Button onClick={close}>取消</Button>
          <Button type="primary" onClick={() => void submit()}>
            创建
          </Button>
        </Space>
      }
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ cardinality: 'MANY_TO_ONE', fieldPairs: [{}] }}
      >
        <Form.Item
          name="name"
          label="关系名称"
          rules={[{ required: true, message: '请输入关系名称' }]}
        >
          <Input />
        </Form.Item>
        <Form.Item label="源数据集">
          <Input disabled value={sourceDataset.name} />
        </Form.Item>
        <Form.Item
          name="targetDatasetId"
          label="目标数据集"
          rules={[{ required: true, message: '请选择目标数据集' }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            options={targetOptions}
            placeholder="选择同一数据源下的已有数据集"
            onChange={() => form.setFieldValue('fieldPairs', [{}])}
          />
        </Form.Item>
        <Form.Item name="cardinality" label="关系基数" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'MANY_TO_ONE', label: '多对一（MANY TO ONE）' },
              { value: 'ONE_TO_ONE', label: '一对一（ONE TO ONE）' },
            ]}
          />
        </Form.Item>
        <Form.Item label="连接方式">
          <Input disabled value="LEFT JOIN（固定）" />
        </Form.Item>
        <Divider>关联字段</Divider>
        <Form.List name="fieldPairs">
          {(fields, { add, remove }) => (
            <>
              {fields.map((field) => (
                <RelationshipFieldPair
                  key={field.key}
                  name={field.name}
                  form={form}
                  sourceFields={sourceFields}
                  targetFields={targetFields}
                  removable={fields.length > 1}
                  onRemove={() => remove(field.name)}
                />
              ))}
              <Button
                type="dashed"
                block
                icon={<PlusOutlined />}
                disabled={!targetDataset}
                onClick={() => add()}
              >
                添加复合关联键
              </Button>
            </>
          )}
        </Form.List>
        {!targetOptions.length && (
          <Typography.Text type="secondary">当前数据源下还没有其他可关联的数据集。</Typography.Text>
        )}
      </Form>
    </Drawer>
  );
}

function RelationshipFieldPair({
  name,
  form,
  sourceFields,
  targetFields,
  removable,
  onRemove,
}: {
  name: number;
  form: FormInstance<CreateRelationshipRequest>;
  sourceFields: DatasetField[];
  targetFields: DatasetField[];
  removable: boolean;
  onRemove: () => void;
}) {
  const sourceFieldId = Form.useWatch(['fieldPairs', name, 'sourceFieldId'], form);
  const compatibleTargets = sourceFieldId
    ? targetFields.filter((target) =>
        compatible(
          sourceFields.find((source) => source.id === sourceFieldId)?.physicalType,
          target.physicalType,
        ),
      )
    : targetFields;
  const options = (fields: DatasetField[]) =>
    fields.map((field) => ({
      value: field.id,
      label: `${field.displayName}（${field.physicalName} · ${field.physicalType}）`,
    }));
  return (
    <Space align="start" style={{ display: 'flex', marginBottom: 8 }}>
      <Form.Item
        name={[name, 'sourceFieldId']}
        rules={[{ required: true, message: '请选择源字段' }]}
        style={{ minWidth: 245 }}
      >
        <Select
          showSearch
          optionFilterProp="label"
          options={options(sourceFields)}
          placeholder="源字段"
          onChange={() => form.setFieldValue(['fieldPairs', name, 'targetFieldId'], undefined)}
        />
      </Form.Item>
      <Form.Item
        name={[name, 'targetFieldId']}
        rules={[{ required: true, message: '请选择目标字段' }]}
        style={{ minWidth: 245 }}
      >
        <Select
          showSearch
          optionFilterProp="label"
          disabled={!sourceFieldId}
          options={options(compatibleTargets)}
          placeholder="兼容的目标字段"
        />
      </Form.Item>
      {removable && <Button type="text" danger icon={<MinusCircleOutlined />} onClick={onRemove} />}
    </Space>
  );
}

function compatible(left?: string, right?: string) {
  const base = (type?: string) => (type ?? '').split('(')[0].trim().toUpperCase();
  const a = base(left),
    b = base(right);
  if (a === b) return true;
  const groups = [
    new Set([
      'TINYINT',
      'SMALLINT',
      'INT',
      'INTEGER',
      'BIGINT',
      'LARGEINT',
      'FLOAT',
      'DOUBLE',
      'DECIMAL',
      'DECIMALV2',
      'DECIMAL32',
      'DECIMAL64',
      'DECIMAL128',
    ]),
    new Set(['CHAR', 'VARCHAR', 'STRING']),
    new Set(['DATE', 'DATETIME', 'TIMESTAMP']),
  ];
  return groups.some((group) => group.has(a) && group.has(b));
}
