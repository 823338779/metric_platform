import {
  ApartmentOutlined,
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { Button, Input } from 'antd';
import type { Topology } from '../../api/ontologyApi';
import { Health } from './BindingHealth';
import { cardinalities } from './RelationEditor';

export function DefinitionLibrary({
  graph,
  search,
  onSearch,
  onSelect,
}: {
  graph: Topology;
  search: string;
  onSearch: (value: string) => void;
  onSelect: (kind: 'object' | 'relation', id: string, deleting?: boolean) => void;
}) {
  const query = search.trim().toLocaleLowerCase();
  const matches = (value: string) => value.toLocaleLowerCase().includes(query);
  const findings = graph.bindingReport.findings;
  const byId = new Map(graph.objects.map((object) => [object.id, object]));
  function endpoint(objectId: string, propertyId: string) {
    const object = byId.get(objectId);
    return (
      (object?.name ?? objectId) +
      ' · ' +
      (object?.properties.find((p) => p.id === propertyId)?.name ?? propertyId)
    );
  }
  const objects = graph.objects.filter((o) =>
    [o.name, o.datasetAssetId, ...o.properties.flatMap((p) => [p.name, p.columnName])].some(
      matches,
    ),
  );
  const relations = graph.relations.filter((r) =>
    [
      r.name,
      endpoint(r.sourceObjectId, r.sourcePropertyId),
      endpoint(r.targetObjectId, r.targetPropertyId),
    ].some(matches),
  );
  function actions(kind: 'object' | 'relation', id: string, name: string) {
    const label = (kind === 'object' ? '对象 ' : '关系 ') + name;
    return (
      <div className="ontology-row-actions">
        <Button
          type="text"
          icon={<EditOutlined />}
          aria-label={'编辑' + label}
          title={'编辑' + label}
          onClick={() => onSelect(kind, id)}
        />
        <Button
          type="text"
          icon={<DeleteOutlined />}
          aria-label={'删除' + label}
          title={'删除' + label}
          onClick={() => onSelect(kind, id, true)}
        />
      </div>
    );
  }
  return (
    <section className="ontology-library" aria-label="定义目录">
      <div className="ontology-search">
        <Input
          allowClear
          prefix={<SearchOutlined aria-hidden />}
          aria-label="搜索定义"
          placeholder="搜索对象、关系或字段"
          value={search}
          onChange={(e) => onSearch(e.target.value)}
        />
      </div>
      <div className="ontology-records">
        {!objects.length && !relations.length && query ? (
          <div className="ontology-no-results" role="status">
            未找到匹配的定义
            <Button type="link" onClick={() => onSearch('')}>
              清除搜索
            </Button>
          </div>
        ) : (
          <>
            <h4>
              对象 <span>{objects.length}</span>
            </h4>
            <ul className="ontology-list">
              {objects.map((o) => {
                const propertyIds = new Set(o.properties.map((p) => p.id));
                const fieldsNeedAttention = findings.some(
                  (f) =>
                    f.kind === 'PROPERTY' &&
                    f.status !== 'COMPATIBLE' &&
                    f.id &&
                    propertyIds.has(f.id),
                );
                return (
                  <li key={o.id}>
                    <div className="ontology-row">
                      <strong>{o.name}</strong>
                      {actions('object', o.id, o.name)}
                    </div>
                    <code className="ontology-asset">{o.datasetAssetId}</code>
                    {o.description && <p>{o.description}</p>}
                    <Health
                      findings={findings.filter((f) => f.kind === 'OBJECT' && f.id === o.id)}
                    />
                    <details
                      open={
                        fieldsNeedAttention ||
                        (!!query &&
                          o.properties.some((p) => matches(p.name) || matches(p.columnName))) ||
                        undefined
                      }
                    >
                      <summary>
                        属性映射 <span>{o.properties.length}</span>
                      </summary>
                      <dl>
                        {o.properties.map((p) => (
                          <div key={p.id}>
                            <dt>
                              {p.name}{' '}
                              {o.primaryPropertyId === p.id && (
                                <small>
                                  <KeyOutlined aria-hidden /> 声明主键
                                </small>
                              )}
                            </dt>
                            <dd>
                              <code>{p.columnName}</code> · {'模型声明 ' + (p.declaredType || '无')}{' '}
                              <Health
                                findings={findings.filter(
                                  (f) => f.kind === 'PROPERTY' && f.id === p.id,
                                )}
                              />
                            </dd>
                          </div>
                        ))}
                      </dl>
                    </details>
                  </li>
                );
              })}
            </ul>
            <h4>
              关系 <span>{relations.length}</span>
            </h4>
            {!relations.length && (
              <p className="ontology-empty">{query ? '无匹配关系' : '暂无关系'}</p>
            )}
            <ul className="ontology-list">
              {relations.map((r) => (
                <li key={r.id}>
                  <div className="ontology-row">
                    <strong>{r.name}</strong>
                    {actions('relation', r.id, r.name)}
                  </div>
                  <p className="ontology-endpoints">
                    {endpoint(r.sourceObjectId, r.sourcePropertyId)} →{' '}
                    {endpoint(r.targetObjectId, r.targetPropertyId)}
                  </p>
                  <small className="ontology-cardinality">
                    <ApartmentOutlined aria-hidden /> {cardinalities[r.cardinality]}
                  </small>
                  {r.description && <p>{r.description}</p>}
                  <Health
                    findings={findings.filter((f) => f.kind === 'RELATION' && f.id === r.id)}
                  />
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </section>
  );
}
