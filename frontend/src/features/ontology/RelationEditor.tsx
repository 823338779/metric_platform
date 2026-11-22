import { Button } from 'antd';
import { useState } from 'react';
import type { Cardinality, ObjectType, Relation } from '../../api/ontologyApi';

export const cardinalities: Record<Cardinality, string> = {
  ONE_TO_ONE: '一对一',
  MANY_TO_ONE: '多对一',
  ONE_TO_MANY: '一对多',
};

export function RelationEditor({
  objects,
  original,
  buildId,
  busy,
  refreshed,
  deploymentChanged,
  onChange,
  onClose,
  onSave,
}: {
  objects: ObjectType[];
  original?: Relation;
  buildId: string | null;
  busy: boolean;
  refreshed: boolean;
  deploymentChanged: boolean;
  onChange: () => void;
  onClose: () => void;
  onSave: (relation: Relation, buildId: string | null) => void;
}) {
  const [draft, setDraft] = useState<Relation>(
    () =>
      original ?? {
        id: crypto.randomUUID(),
        name: '',
        description: '',
        cardinality: 'MANY_TO_ONE',
        sourceObjectId: '',
        sourcePropertyId: '',
        targetObjectId: '',
        targetPropertyId: '',
      },
  );
  function update(changes: Partial<Relation>) {
    onChange();
    setDraft({ ...draft, ...changes });
  }
  const bindingChanged =
    !original ||
    draft.sourcePropertyId !== original.sourcePropertyId ||
    draft.targetPropertyId !== original.targetPropertyId ||
    draft.sourceObjectId !== original.sourceObjectId ||
    draft.targetObjectId !== original.targetObjectId;
  const valid =
    draft.name.trim() &&
    draft.sourcePropertyId &&
    draft.targetPropertyId &&
    (!bindingChanged || (buildId && !deploymentChanged));
  function endpoint(side: 'source' | 'target', label: string) {
    const objectKey = side === 'source' ? 'sourceObjectId' : 'targetObjectId';
    const propertyKey = side === 'source' ? 'sourcePropertyId' : 'targetPropertyId';
    const object = objects.find((o) => o.id === draft[objectKey]);
    return (
      <div className="ontology-fields">
        <label>
          {label}对象
          <select
            required
            value={draft[objectKey]}
            onChange={(e) => update({ [objectKey]: e.target.value, [propertyKey]: '' })}
          >
            <option value="">选择对象</option>
            {objects.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          {label}属性
          <select
            required
            disabled={!object}
            value={draft[propertyKey]}
            onChange={(e) => update({ [propertyKey]: e.target.value })}
          >
            <option value="">{object ? '选择属性' : '先选择对象'}</option>
            {object?.properties.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
      </div>
    );
  }
  return (
    <form
      className="ontology-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (valid) onSave(draft, bindingChanged ? buildId : null);
      }}
    >
      <fieldset disabled={busy}>
        <div className="ontology-fields">
          <label>
            关系名称
            <input required value={draft.name} onChange={(e) => update({ name: e.target.value })} />
          </label>
          <label>
            关系说明
            <input
              value={draft.description ?? ''}
              onChange={(e) => update({ description: e.target.value })}
            />
          </label>
        </div>
        <h3>关系方向</h3>
        {endpoint('source', '源')}
        {endpoint('target', '目标')}
        <label>
          声明基数
          <select
            value={draft.cardinality}
            onChange={(e) => update({ cardinality: e.target.value as Cardinality })}
          >
            {Object.entries(cardinalities).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <small>关系方向从源属性指向目标属性。基数由管理者声明。</small>
        {bindingChanged && !buildId && <p role="status">尚无可用于核对关系的部署，请刷新拓扑。</p>}
        <div className="ontology-actions">
          <Button type="primary" htmlType="submit" loading={busy} disabled={!valid}>
            {refreshed && bindingChanged ? '确认绑定并保存关系' : '保存关系'}
          </Button>
          <Button onClick={onClose}>取消编辑</Button>
        </div>
      </fieldset>
    </form>
  );
}
