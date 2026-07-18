import { Button, Card, Descriptions, Space, Tabs } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { Dataset, DatasetRelationship, SchemaDiff } from '../../api/types';
import { RelationshipCreateDrawer } from '../relationships/RelationshipCreateDrawer';
import { RelationshipDrawer } from '../relationships/RelationshipDrawer';
import { RelationshipGraph } from '../relationships/RelationshipGraph';
import { FieldTable } from './FieldTable';
import { SchemaDiffModal } from './SchemaDiffModal';

export function DatasetDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const [dataset, setDataset] = useState<Dataset>();
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [relationships, setRelationships] = useState<DatasetRelationship[]>([]);
  const [selected, setSelected] = useState<DatasetRelationship>();
  const [creatingRelationship, setCreatingRelationship] = useState(false);
  const [diff, setDiff] = useState<SchemaDiff>();
  const load = () => {
    const params = new URLSearchParams({ size: '200' });
    return Promise.all([
      metricPlatformApi.getDataset(id),
      metricPlatformApi.listRelationships(id),
      metricPlatformApi.listDatasets(params),
    ]).then(([current, relations, page]) => {
      setDataset(current);
      setRelationships(relations);
      setDatasets(page.items);
    });
  };
  useEffect(() => {
    void load();
  }, [id]);
  const graphDatasets = useMemo(() => {
    const ids = new Set([
      id,
      ...relationships.flatMap((relation) => [relation.sourceDatasetId, relation.targetDatasetId]),
    ]);
    const related = datasets.filter((item) => ids.has(item.id));
    return related.some((item) => item.id === id) || !dataset
      ? [...related]
      : [dataset, ...related];
  }, [datasets, relationships, id, dataset]);
  if (!dataset) return <Card loading />;

  const relationshipTab = (
    <Space direction="vertical" size="middle" style={{ display: 'flex' }}>
      <Space style={{ justifyContent: 'space-between' }}>
        <span>关系在数据集之间定义，查询时可直接复用已校验的 LEFT JOIN 路径。</span>
        <Button type="primary" onClick={() => setCreatingRelationship(true)}>
          新建关系
        </Button>
      </Space>
      <RelationshipGraph
        datasets={graphDatasets}
        relationships={relationships}
        onSelect={setSelected}
      />
    </Space>
  );

  return (
    <Card
      title={dataset.name}
      extra={
        <Space>
          <Button onClick={() => void metricPlatformApi.detectFieldChanges(id).then(setDiff)}>
            检测字段变化
          </Button>
          <Button type="primary" onClick={() => navigate(`/datasets/${id}/edit`)}>
            编辑
          </Button>
        </Space>
      }
    >
      <Tabs
        items={[
          {
            key: 'overview',
            label: '概览',
            children: (
              <Descriptions
                items={[
                  { key: 'source', label: '数据源', children: dataset.dataSourceId },
                  {
                    key: 'object',
                    label: '物理对象',
                    children: `${dataset.schemaName}.${dataset.objectName}`,
                  },
                  { key: 'type', label: '对象类型', children: dataset.objectType },
                  { key: 'description', label: '描述', children: dataset.description || '-' },
                ]}
              />
            ),
          },
          { key: 'fields', label: '字段', children: <FieldTable fields={dataset.fields} /> },
          {
            key: 'filters',
            label: '固定筛选',
            children: <pre>{JSON.stringify(dataset.filterExpression ?? {}, null, 2)}</pre>,
          },
          { key: 'relationships', label: '数据集关系', children: relationshipTab },
          {
            key: 'preview',
            label: '预览',
            children: <p>查询预览会应用固定筛选和已校验的数据集关系。</p>,
          },
        ]}
      />
      <RelationshipCreateDrawer
        open={creatingRelationship}
        sourceDataset={dataset}
        datasets={datasets}
        onClose={() => setCreatingRelationship(false)}
        onCreated={() => {
          setCreatingRelationship(false);
          void load();
        }}
      />
      <RelationshipDrawer
        open={!!selected}
        value={selected}
        onClose={() => setSelected(undefined)}
        onRevalidate={(relationshipId) =>
          void metricPlatformApi.revalidateRelationship(relationshipId).then(load)
        }
        onDelete={(relationshipId) =>
          void metricPlatformApi.deleteRelationship(relationshipId).then(() => {
            setSelected(undefined);
            return load();
          })
        }
      />
      <SchemaDiffModal
        open={!!diff}
        diff={diff}
        onCancel={() => setDiff(undefined)}
        onConfirm={() =>
          void metricPlatformApi.syncFields(id, dataset.version, diff!.fingerprint).then(() => {
            setDiff(undefined);
            return load();
          })
        }
      />
    </Card>
  );
}
