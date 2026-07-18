import { Alert, Button, Card, Form, Input, Select, Space, Steps, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type {
  DataSourceSummary,
  Dataset,
  FilterExpression,
  SourceColumn,
  SourceObject,
} from '../../api/types';
import { FieldTable } from './FieldTable';
import { FilterBuilder } from './FilterBuilder';
import { PhysicalFieldSelector } from './PhysicalFieldSelector';
const emptyFilter: FilterExpression = { logicalOperator: 'AND', children: [] };

export function DatasetEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [step, setStep] = useState(0);
  const [dataset, setDataset] = useState<Dataset>();
  const [filter, setFilter] = useState<FilterExpression>(emptyFilter);
  const [dataSources, setDataSources] = useState<DataSourceSummary[]>([]);
  const [objects, setObjects] = useState<SourceObject[]>([]);
  const [columns, setColumns] = useState<SourceColumn[]>([]);
  const [loadingObjects, setLoadingObjects] = useState(false);
  const [loadingColumns, setLoadingColumns] = useState(false);
  const [form] = Form.useForm();
  const dataSourceId = Form.useWatch('dataSourceId', { form, preserve: true }) as
    string | undefined;
  const sourceObjectKey = Form.useWatch('sourceObjectKey', { form, preserve: true }) as
    string | undefined;
  useEffect(() => {
    void metricPlatformApi
      .listDataSources()
      .then((values) => setDataSources(values.filter((source) => source.status === 'ACTIVE')))
      .catch(() => message.error('数据源列表加载失败'));
    if (id)
      void metricPlatformApi.getDataset(id).then((value) => {
        setDataset(value);
        setFilter(value.filterExpression ?? emptyFilter);
        form.setFieldsValue(value);
      });
  }, [id, form]);
  useEffect(() => {
    if (id || !dataSourceId) {
      setObjects([]);
      return;
    }
    setLoadingObjects(true);
    void metricPlatformApi
      .listSourceObjects(dataSourceId)
      .then(setObjects)
      .catch(() => {
        setObjects([]);
        message.error('物理对象列表加载失败');
      })
      .finally(() => setLoadingObjects(false));
  }, [dataSourceId, id]);
  useEffect(() => {
    if (id || !dataSourceId || !sourceObjectKey) {
      setColumns([]);
      return;
    }
    const selected = objects.find((object) => objectKey(object) === sourceObjectKey);
    if (!selected) return;
    form.setFieldsValue({
      schemaName: selected.schemaName ?? selected.catalogName,
      objectName: selected.objectName,
      selectedPhysicalFields: [],
    });
    setLoadingColumns(true);
    void metricPlatformApi
      .listSourceFields(dataSourceId, {
        catalogName: selected.catalogName,
        schemaName: selected.schemaName,
        objectName: selected.objectName,
      })
      .then(setColumns)
      .catch(() => {
        setColumns([]);
        message.error('字段列表加载失败');
      })
      .finally(() => setLoadingColumns(false));
  }, [dataSourceId, sourceObjectKey, objects, id, form]);
  const sourceOptions = useMemo(
    () =>
      dataSources.map((source) => ({
        value: source.id,
        label: `${source.name}（${source.databaseType} · ${source.databaseName ?? '默认目录'}）`,
      })),
    [dataSources],
  );
  const objectOptions = useMemo(
    () =>
      objects.map((object) => ({
        value: objectKey(object),
        label: `${[object.catalogName, object.schemaName, object.objectName].filter(Boolean).join('.')}（${object.objectType}）`,
      })),
    [objects],
  );
  const save = async () => {
    await form.validateFields(
      id ? ['name'] : ['name', 'dataSourceId', 'sourceObjectKey', 'selectedPhysicalFields'],
    );
    const values = form.getFieldsValue(true);
    if (id && dataset)
      await metricPlatformApi.updateDataset(id, {
        name: values.name,
        description: values.description,
        aliases: values.aliases ?? [],
        filterExpression: filter,
        fieldUpdates: dataset.fields.map((field) => ({
          fieldId: field.id,
          displayName: field.displayName,
          selected: field.selected,
        })),
        version: dataset.version,
      });
    else
      await metricPlatformApi.createDataset({
        dataSourceId: values.dataSourceId,
        name: values.name,
        description: values.description,
        aliases: values.aliases ?? [],
        schemaName: values.schemaName,
        objectName: values.objectName,
        selectedPhysicalFields: values.selectedPhysicalFields,
      });
    navigate('/datasets');
  };
  const next = async () => {
    if (step === 0)
      await form.validateFields(id ? ['name'] : ['name', 'dataSourceId', 'sourceObjectKey']);
    if (step === 1 && !id) await form.validateFields(['selectedPhysicalFields']);
    setStep((current) => current + 1);
  };
  const basic = (
    <Form form={form} layout="vertical">
      <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入数据集名称' }]}>
        <Input />
      </Form.Item>
      <Form.Item name="description" label="描述">
        <Input.TextArea />
      </Form.Item>
      <Form.Item name="aliases" label="别名">
        <Select mode="tags" tokenSeparators={[',', '，']} placeholder="输入别名后按回车" />
      </Form.Item>
      <Form.Item
        name="dataSourceId"
        label="数据源"
        rules={[{ required: !id, message: '请选择数据源' }]}
      >
        <Select
          showSearch
          optionFilterProp="label"
          disabled={!!id}
          options={sourceOptions}
          placeholder="选择已启用的数据源"
          onChange={() =>
            form.setFieldsValue({
              sourceObjectKey: undefined,
              schemaName: undefined,
              objectName: undefined,
              selectedPhysicalFields: [],
            })
          }
        />
      </Form.Item>
      {id ? (
        <Form.Item label="物理对象">
          <Input disabled value={`${dataset?.schemaName ?? ''}.${dataset?.objectName ?? ''}`} />
        </Form.Item>
      ) : (
        <Form.Item
          name="sourceObjectKey"
          label="表 / 视图"
          rules={[{ required: true, message: '请选择表或视图' }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            loading={loadingObjects}
            disabled={!dataSourceId}
            options={objectOptions}
            placeholder={dataSourceId ? '选择默认目录下的表或视图' : '请先选择数据源'}
          />
        </Form.Item>
      )}
      <Form.Item name="schemaName" hidden>
        <Input />
      </Form.Item>
      <Form.Item name="objectName" hidden>
        <Input />
      </Form.Item>
    </Form>
  );
  const fields =
    id && dataset ? (
      <FieldTable
        fields={dataset.fields}
        onChange={(value) => setDataset({ ...dataset, fields: value })}
      />
    ) : (
      <Form form={form} layout="vertical">
        <Form.Item
          name="selectedPhysicalFields"
          label="字段"
          rules={[{ required: true, type: 'array', min: 1, message: '请至少选择一个字段' }]}
        >
          <PhysicalFieldSelector columns={columns} loading={loadingColumns} />
        </Form.Item>
      </Form>
    );
  const filters = id ? (
    <FilterBuilder value={filter} onChange={setFilter} fields={dataset?.fields ?? []} />
  ) : (
    <Alert
      type="info"
      showIcon
      message="创建后可配置固定筛选"
      description="字段 ID 会在数据集创建后生成，因此固定筛选将在编辑数据集时从已有字段中选择。"
    />
  );
  const content = [
    basic,
    fields,
    filters,
    <Card type="inner" title="预览确认">
      <p>系统将基于已选择的物理对象和字段创建逻辑数据集，不保存自定义 SQL。</p>
      <pre style={{ maxWidth: '100%', maxHeight: 400, overflow: 'auto', marginBottom: 0 }}>
        {JSON.stringify({ values: form.getFieldsValue(true), filter }, null, 2)}
      </pre>
    </Card>,
  ][step];
  return (
    <Card title={id ? '编辑数据集' : '新建数据集'}>
      <Steps
        current={step}
        items={['基本信息', '字段配置', '固定筛选', '预览确认'].map((title) => ({ title }))}
      />
      <div style={{ margin: '28px 0', minWidth: 0 }}>{content}</div>
      <Space>
        <Button disabled={!step} onClick={() => setStep((current) => current - 1)}>
          上一步
        </Button>
        {step < 3 ? (
          <Button type="primary" onClick={() => void next()}>
            下一步
          </Button>
        ) : (
          <Button type="primary" onClick={() => void save()}>
            保存
          </Button>
        )}
      </Space>
    </Card>
  );
}
function objectKey(object: SourceObject) {
  return JSON.stringify([object.catalogName, object.schemaName, object.objectName]);
}
