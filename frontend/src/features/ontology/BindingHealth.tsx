import type { EndpointEvidence, Finding } from '../../api/ontologyApi';

export const reasons: Record<string, string> = {
  NO_DEPLOYMENT: '此环境尚无可绑定的数据集',
  UPSTREAM_UNAVAILABLE: '部署服务暂时不可用',
  SERVICE_AUTHENTICATION_FAILED: '平台连接部署服务的凭据无效',
  AMBIGUOUS_DEPLOYMENT: '无法唯一定位当前部署',
  DEPLOYMENT_CHANGED: '当前部署已切换',
  ASSET_NOT_DEPLOYED: '当前部署中未找到绑定资产',
  AMBIGUOUS_ASSET: '无法唯一定位绑定资产',
  COLUMN_UNDECLARED: '当前构建未声明该列',
  TYPE_UNKNOWN: '类型未核实',
  TYPE_CONFLICT: '字段类型不兼容',
  COLUMN_MISSING: '完整的仓库列清单中缺少此列',
  TARGET_NOT_ALLOWED: '目标未授权',
  TARGET_UNRESOLVED: '无法定位物理表',
  METADATA_PERMISSION_DENIED: '无权读取列目录',
  METADATA_CREDENTIALS_MISSING: '未配置元数据身份',
  METADATA_TIMEOUT: '读取列目录超时',
  METADATA_UNAVAILABLE: '列目录暂不可用',
  METADATA_INVISIBLE: '无法取得完整可见的列清单',
  MATCHED: '绑定匹配',
};
export function Evidence({ detail, label }: { detail?: EndpointEvidence | null; label?: string }) {
  if (!detail) return null;
  const observed = detail.lastObservation;
  return (
    <span className="ontology-evidence">
      {label} {detail.columnName} · 模型声明 {detail.declaredType || '无'} · 基准{' '}
      {detail.acceptedPhysicalType || '无'}
      {observed && (
        <>
          {' '}
          · {detail.typeSource === 'WAREHOUSE' ? '仓库实测' : '上次实测'} {observed.physicalType}
          {' · '}
          {observed.table.catalog}.{observed.table.schema}.{observed.table.table}
          {' · '}
          <time dateTime={observed.observedAt}>
            {new Date(observed.observedAt).toLocaleString()}
          </time>
        </>
      )}
    </span>
  );
}
export function Health({ findings }: { findings: Finding[] }) {
  return (
    <>
      {findings.map((f) => (
        <span key={[f.kind, f.id, f.reason].join('/')} className={'ontology-health ' + f.status}>
          {{ COMPATIBLE: '绑定兼容', UNVERIFIED: '未核实', NEEDS_REPAIR: '需修复' }[f.status]} ·{' '}
          {reasons[f.reason] ?? f.reason}
          <Evidence detail={f.evidence} />
          <Evidence detail={f.sourceEvidence} label="源端" />
          <Evidence detail={f.targetEvidence} label="目标端" />
        </span>
      ))}
    </>
  );
}
