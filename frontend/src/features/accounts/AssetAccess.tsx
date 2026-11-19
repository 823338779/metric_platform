import { useEffect, useState } from 'react';
import { Alert, Button, Card, Space } from 'antd';
import { Link } from 'react-router-dom';
import { createHttpClient } from '../../api/httpClient';
import {
  buildCatalogApi,
  type DeploymentKey,
  type PublishedResult,
} from '../../api/buildCatalogApi';
import { DbtQueryResults } from '../dbt/DbtQueryResults';
import { publishedResult } from '../dbt/publishedResourceAdapter';

const ACCOUNTS = '/tenant/accounts',
  ASSETS = '/assets';
const LABEL = { METRIC: '指标', DIMENSION: '维度', DATASET: '数据集' };
type Asset = {
  assetId: string;
  target: DeploymentKey;
  kind: keyof typeof LABEL;
  nodeId: string;
  name: string;
  dimensionName: string;
  selector: string;
  buildId: string | null;
  granted: boolean;
};
type Account = { id: string; username: string };
const errorText = (error: unknown) => (error instanceof Error ? error.message : '读取失败，请重试');
const accountAssets = (id: string) => ACCOUNTS + '/' + encodeURIComponent(id) + ASSETS;

/** 每次切换账号或重试均忽略先前请求的返回值。 */
function useList<T>(path: string, refresh: number) {
  const [items, setItems] = useState<T[] | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setItems(null);
    setError('');
    createHttpClient()<T[]>(path)
      .then((data) => {
        if (!Array.isArray(data)) throw new Error('资产服务响应无效');
        if (active) setItems(data);
      })
      .catch((e) => {
        if (active) setError(errorText(e));
      });
    return () => {
      active = false;
    };
  }, [path, refresh]);
  return { items, error };
}
function AssetName({ asset }: { asset: Asset }) {
  return (
    <>
      <strong>{asset.dimensionName || asset.name}</strong> · {LABEL[asset.kind]}
      <br />
      <small>
        {asset.nodeId} · {asset.target.repository} · {asset.target.branchName} ·{' '}
        {asset.target.environmentId}
      </small>
    </>
  );
}
export function AssetGrantPanel() {
  const [open, setOpen] = useState(false);
  return open ? <ReaderPicker /> : <Button onClick={() => setOpen(true)}>管理资产授权</Button>;
}
function ReaderPicker() {
  const [user, setUser] = useState('');
  const [refresh, setRefresh] = useState(0);
  const { items, error } = useList<Account>(ACCOUNTS, refresh);
  return (
    <section>
      <h2>资产授权</h2>
      <label>
        授权账号{' '}
        <select value={user} onChange={(e) => setUser(e.target.value)}>
          <option value="">选择只读账号</option>
          {items?.map((a) => (
            <option key={a.id} value={a.id}>
              {a.username}
            </option>
          ))}
        </select>
      </label>{' '}
      <Button onClick={() => setRefresh((n) => n + 1)}>刷新账号</Button>
      {error && <Alert type="error" title={error} />}
      {user && <AccountGrants key={user} user={user} />}
    </section>
  );
}
function AccountGrants({ user }: { user: string }) {
  const [refresh, setRefresh] = useState(0);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState('');
  const path = accountAssets(user);
  const { items, error } = useList<Asset>(path, refresh);
  async function toggle(a: Asset) {
    setSaving(true);
    setFailure('');
    try {
      await createHttpClient()(path + '/' + encodeURIComponent(a.assetId), {
        method: a.granted ? 'DELETE' : 'PUT',
      });
      setRefresh((n) => n + 1);
    } catch (e) {
      setFailure(errorText(e));
    } finally {
      setSaving(false);
    }
  }
  return (
    <>
      <Button onClick={() => setRefresh((n) => n + 1)}>刷新资产</Button>
      {(error || failure) && <Alert type="error" title={error || failure} />}
      {items?.length === 0 && <p>暂无可授权资产</p>}
      <ul>
        {items?.map((a) => (
          <li key={a.assetId}>
            <Space>
              <span>
                <AssetName asset={a} />
              </span>
              {!a.buildId && '当前未部署'}
              <Button autoInsertSpace={false} disabled={saving} onClick={() => void toggle(a)}>
                {a.granted ? '撤销' : '授予'}
              </Button>
            </Space>
          </li>
        ))}
      </ul>
    </>
  );
}
export function MyAssets() {
  const [refresh, setRefresh] = useState(0);
  const { items, error } = useList<Asset>(ASSETS, refresh);
  const [preview, setPreview] = useState<Asset | null>(null);
  return (
    <Card
      title="我的资产"
      extra={
        <Button
          onClick={() => {
            setPreview(null);
            setRefresh((n) => n + 1);
          }}
        >
          刷新资产
        </Button>
      }
    >
      {error && <Alert type="error" title={error} />}
      {items?.length === 0 && <p>暂无可见资产</p>}
      <ul>
        {items?.map((a) => (
          <li key={a.assetId}>
            <Space>
              <span>
                <AssetName asset={a} />
              </span>
              {a.kind === 'METRIC' && a.buildId && (
                <Link
                  to={
                    '/dbt/builds/' +
                    encodeURIComponent(a.buildId) +
                    '/query?metricResourceId=' +
                    encodeURIComponent(a.nodeId)
                  }
                >
                  查询汇总
                </Link>
              )}
              {a.kind === 'DATASET' && <Button onClick={() => setPreview(a)}>查看明细</Button>}
            </Space>
          </li>
        ))}
      </ul>
      {preview && <DatasetPreview key={preview.assetId} asset={preview} />}
    </Card>
  );
}
function DatasetPreview({ asset }: { asset: Asset }) {
  const [result, setResult] = useState<PublishedResult | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    if (asset.buildId)
      buildCatalogApi
        .submit(asset.buildId, { mode: 'PREVIEW', selector: asset.selector, limit: 100 })
        .then((r) => {
          if (active) setResult(r);
        })
        .catch((e) => {
          if (active) setError(errorText(e));
        });
    return () => {
      active = false;
    };
  }, [asset]);
  return (
    <section>
      <h2>{asset.name}明细</h2>
      {error && <Alert type="error" title={error} />}
      {result && <DbtQueryResults result={publishedResult(result)} />}
    </section>
  );
}
