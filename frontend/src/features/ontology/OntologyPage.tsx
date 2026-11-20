import { Alert, Button, Empty } from 'antd';
import { ArrowLeftOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useBlocker, useParams } from 'react-router-dom';
import { getAuthSession } from '../../api/auth';
import { environmentApi } from '../../api/environmentApi';
import { ApiProblem } from '../../api/httpClient';
import { ontologyApi, type Finding, type Topology } from '../../api/ontologyApi';
import { ObjectEditor } from './ObjectEditor';
import { cardinalities, RelationEditor } from './RelationEditor';
import { OntologyGraph } from './OntologyGraph';
import './ontology.css';

type Selection = { kind: 'object' | 'relation'; id?: string; deleting?: boolean };
const reasons: Record<string, string> = {
  NO_DEPLOYMENT: '此环境尚无可绑定的数据集',
  UPSTREAM_UNAVAILABLE: '部署服务暂时不可用',
  AMBIGUOUS_DEPLOYMENT: '无法唯一定位当前部署',
  DEPLOYMENT_CHANGED: '当前部署已切换',
  ASSET_NOT_DEPLOYED: '当前部署中未找到绑定资产',
  AMBIGUOUS_ASSET: '无法唯一定位绑定资产',
  COLUMN_UNDECLARED: '当前构建未声明该列',
  TYPE_UNKNOWN: '类型未核实',
  TYPE_CONFLICT: '声明类型不兼容',
  MATCHED: '绑定匹配',
};
function Health({ findings }: { findings: Finding[] }) {
  return (
    <>
      {findings.map((f) => (
        <span key={[f.kind, f.id, f.reason].join('/')} className={'ontology-health ' + f.status}>
          {{ COMPATIBLE: '绑定兼容', UNVERIFIED: '未核实', NEEDS_REPAIR: '需修复' }[f.status]} ·{' '}
          {reasons[f.reason] ?? f.reason}
        </span>
      ))}
    </>
  );
}

export function OntologyPage() {
  const { environmentId = '' } = useParams();
  if (getAuthSession()?.identity.role !== 'TENANT_MANAGER')
    return <Alert type="warning" title="仅租户管理者可维护业务本体" />;
  return <EnvironmentOntology key={environmentId} environmentId={environmentId} />;
}

function EnvironmentOntology({ environmentId }: { environmentId: string }) {
  const [name, setName] = useState('');
  const [graph, setGraph] = useState<Topology>();
  const [refresh, setRefresh] = useState(0);
  const [loadError, setLoadError] = useState('');
  const [loading, setLoading] = useState(true);
  const [selection, setSelection] = useState<Selection>();
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [failure, setFailure] = useState<Error>();
  const blocker = useBlocker(dirty);
  useEffect(() => {
    if (!dirty) return;
    const guard = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [dirty]);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError('');
    const environment = environmentApi.get(environmentId).then((e) => {
      if (active) setName(e.name);
    });
    Promise.all([environment, ontologyApi.get(environmentId)])
      .then(([, g]) => {
        if (active) setGraph(g);
      })
      .catch((reason) => {
        if (active) setLoadError(reason.message);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [environmentId, refresh]);
  const select = useCallback((kind: Selection['kind'], id?: string, deleting = false) => {
    setSelection({ kind, id, deleting });
    setFailure(undefined);
    setDirty(false);
  }, []);
  function close() {
    setSelection(undefined);
    setFailure(undefined);
    setDirty(false);
  }
  function reload() {
    close();
    setRefresh((value) => value + 1);
  }
  async function save(write: () => Promise<unknown>) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setFailure(undefined);
    try {
      await write();
      setGraph(undefined);
      reload();
    } catch (reason) {
      setFailure(reason instanceof Error ? reason : new Error('本体保存失败'));
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  const object = graph?.objects.find((o) => o.id === selection?.id);
  const relation = graph?.relations.find((r) => r.id === selection?.id);
  const selected = selection?.kind === 'object' ? object : relation;
  const blockers =
    selection?.kind === 'object' && selection.deleting
      ? (graph?.relations.filter(
          (r) => r.sourceObjectId === object?.id || r.targetObjectId === object?.id,
        ) ?? [])
      : [];
  const problem = failure instanceof ApiProblem ? failure.problem : undefined;
  const findings = (kind: Finding['kind'], id?: string) =>
    graph?.bindingReport.findings.filter(
      (f) => f.kind === kind && (kind === 'ENVIRONMENT' || f.id === id),
    ) ?? [];
  function endpoint(objectId: string, propertyId: string) {
    const o = graph?.objects.find((o) => o.id === objectId);
    return (
      (o?.name ?? objectId) +
      ' · ' +
      (o?.properties.find((p) => p.id === propertyId)?.name ?? propertyId)
    );
  }
  return (
    <section className="environment-page ontology-page">
      <Link className="back-link" to={'/dbt/environments/' + environmentId}>
        <ArrowLeftOutlined aria-hidden /> 返回环境详情
      </Link>
      <header className="page-heading">
        <div>
          <p>{name || environmentId}</p>
          <h2>本体建模</h2>
        </div>
        {!selection && (
          <Button icon={<ReloadOutlined aria-hidden />} loading={loading} onClick={reload}>
            刷新拓扑
          </Button>
        )}
      </header>
      {blocker.state === 'blocked' && (
        <div role="alertdialog" aria-label="放弃未保存的修改" className="ontology-notice">
          <p>离开后将放弃此环境中未保存的修改。</p>
          <div className="ontology-actions">
            <Button onClick={() => blocker.reset()}>留在此页</Button>
            <Button danger onClick={() => blocker.proceed()}>
              放弃并离开
            </Button>
          </div>
        </div>
      )}
      {loadError && <Alert type="error" title={'无法读取本体：' + loadError} />}
      {loading && <p role="status">正在核对当前环境的绑定…</p>}
      {failure && (
        <Alert
          type="error"
          title={failure.message}
          description={
            <>
              {problem?.references?.length ? (
                <p>引用此项的关系：{problem.references.map((r) => r.name).join('、')}</p>
              ) : null}
              {problem?.status === 409 && (
                <div>
                  请核对最新的拓扑及部署后再提交。{' '}
                  <Button onClick={reload}>放弃本次编辑并刷新</Button>
                </div>
              )}
            </>
          }
        />
      )}
      {graph && !loading && (
        <>
          <Health findings={findings('ENVIRONMENT')} />
          {selection ? (
            <section className="management-panel ontology-editor">
              {selection.deleting && selected ? (
                <div role="alertdialog" aria-label="删除确认">
                  <h3>
                    删除{selection.kind === 'object' ? '对象' : '关系'}“{selected.name}”
                  </h3>
                  {blockers.length > 0 ? (
                    <p>请先处理引用此对象的关系：{blockers.map((r) => r.name).join('、')}</p>
                  ) : (
                    <p>删除将在当前环境立即生效。</p>
                  )}
                  <div className="ontology-actions">
                    <Button
                      danger
                      disabled={!!blockers.length}
                      loading={busy}
                      onClick={() =>
                        save(() =>
                          ontologyApi.remove(
                            environmentId,
                            selection.kind === 'object' ? 'objects' : 'relations',
                            selected.id,
                            graph.revision,
                          ),
                        )
                      }
                    >
                      确认删除
                    </Button>
                    <Button disabled={busy} onClick={close}>
                      取消删除
                    </Button>
                  </div>
                </div>
              ) : selection.kind === 'object' ? (
                <ObjectEditor
                  environmentId={environmentId}
                  original={object}
                  busy={busy}
                  onChange={() => setDirty(true)}
                  onClose={close}
                  onSave={(value, buildId) =>
                    save(() =>
                      ontologyApi.saveObject(
                        environmentId,
                        graph.revision,
                        buildId,
                        value,
                        !object,
                      ),
                    )
                  }
                />
              ) : (
                <RelationEditor
                  objects={graph.objects}
                  original={relation}
                  buildId={graph.bindingReport.buildId}
                  busy={busy}
                  onChange={() => setDirty(true)}
                  onClose={close}
                  onSave={(value, buildId) =>
                    save(() =>
                      ontologyApi.saveRelation(
                        environmentId,
                        graph.revision,
                        buildId,
                        value,
                        !relation,
                      ),
                    )
                  }
                />
              )}
            </section>
          ) : (
            <>
              <section className="management-panel">
                <header className="panel-heading">
                  <h3>对象关系拓扑</h3>
                  <span>
                    {graph.objects.length} 个对象 · {graph.relations.length} 条关系
                  </span>
                </header>
                {graph.objects.length ? (
                  <OntologyGraph
                    objects={graph.objects}
                    relations={graph.relations}
                    onSelect={select}
                  />
                ) : (
                  <Empty description="暂无对象" />
                )}
              </section>
              <section className="management-panel">
                <header className="panel-heading">
                  <h3>对象</h3>
                  <Button
                    type="primary"
                    icon={<PlusOutlined aria-hidden />}
                    disabled={loading}
                    onClick={() => select('object')}
                  >
                    新建对象
                  </Button>
                </header>
                <ul className="ontology-list">
                  {graph.objects.map((o) => (
                    <li key={o.id}>
                      <div className="ontology-row">
                        <strong>{o.name}</strong>
                        <div className="ontology-actions">
                          <Button
                            aria-label={'编辑对象 ' + o.name}
                            onClick={() => select('object', o.id)}
                          >
                            编辑
                          </Button>
                          <Button
                            danger
                            aria-label={'删除对象 ' + o.name}
                            onClick={() => select('object', o.id, true)}
                          >
                            删除
                          </Button>
                        </div>
                      </div>
                      {o.description && <p>{o.description}</p>}
                      <Health findings={findings('OBJECT', o.id)} />
                      <dl>
                        {o.properties.map((p) => (
                          <div key={p.id}>
                            <dt>
                              {p.name}
                              {p.id === o.primaryPropertyId && <small>声明主键</small>}
                            </dt>
                            <dd>
                              <code>{p.columnName}</code> · {p.declaredType || '类型未核实'}{' '}
                              <Health findings={findings('PROPERTY', p.id)} />
                            </dd>
                          </div>
                        ))}
                      </dl>
                    </li>
                  ))}
                </ul>
              </section>
              <section className="management-panel">
                <header className="panel-heading">
                  <h3>关系</h3>
                  <Button
                    icon={<PlusOutlined aria-hidden />}
                    disabled={loading || !graph.objects.length}
                    onClick={() => select('relation')}
                  >
                    新建关系
                  </Button>
                </header>
                {graph.relations.length === 0 && <p className="ontology-empty">暂无关系</p>}
                <ul className="ontology-list">
                  {graph.relations.map((r) => (
                    <li key={r.id}>
                      <div className="ontology-row">
                        <strong>{r.name}</strong>
                        <div className="ontology-actions">
                          <Button
                            aria-label={'编辑关系 ' + r.name}
                            onClick={() => select('relation', r.id)}
                          >
                            编辑
                          </Button>
                          <Button
                            danger
                            aria-label={'删除关系 ' + r.name}
                            onClick={() => select('relation', r.id, true)}
                          >
                            删除
                          </Button>
                        </div>
                      </div>
                      <p>
                        {endpoint(r.sourceObjectId, r.sourcePropertyId)} →{' '}
                        {endpoint(r.targetObjectId, r.targetPropertyId)} · 声明基数{' '}
                        {cardinalities[r.cardinality]}
                      </p>
                      {r.description && <p>{r.description}</p>}
                      <Health findings={findings('RELATION', r.id)} />
                    </li>
                  ))}
                </ul>
              </section>
            </>
          )}
        </>
      )}
    </section>
  );
}
