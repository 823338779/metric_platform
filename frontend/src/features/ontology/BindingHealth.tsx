import type { Finding } from '../../api/ontologyApi';

const reasons: Record<string, string> = {
  NO_DEPLOYMENT: '此环境尚无可绑定的数据集',
  UPSTREAM_UNAVAILABLE: '部署服务暂时不可用',
  SERVICE_AUTHENTICATION_FAILED: '平台连接部署服务的凭据无效',
  AMBIGUOUS_DEPLOYMENT: '无法唯一定位当前部署',
  DEPLOYMENT_CHANGED: '当前部署已切换',
  ASSET_NOT_DEPLOYED: '当前部署中未找到绑定资产',
  AMBIGUOUS_ASSET: '无法唯一定位绑定资产',
  COLUMN_UNDECLARED: '当前构建未声明该列',
  TYPE_UNKNOWN: '类型未核实',
  TYPE_CONFLICT: '声明类型不兼容',
  MATCHED: '绑定匹配',
};
export function Health({ findings }: { findings: Finding[] }) {
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
