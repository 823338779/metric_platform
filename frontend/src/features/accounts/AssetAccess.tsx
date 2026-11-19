import { useEffect, useState } from 'react';
import { Alert, Button, Input } from 'antd';
import {
  ApartmentOutlined,
  BarChartOutlined,
  BranchesOutlined,
  CheckOutlined,
  DatabaseOutlined,
  FolderOutlined,
  LockOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  TableOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { createHttpClient } from '../../api/httpClient';
import type { DeploymentKey } from '../../api/buildCatalogApi';

const ACCOUNTS = '/tenant/accounts',
  ASSETS = '/assets';
const LABEL = { METRIC: '指标', DIMENSION: '维度', DATASET: '数据集' };
const ASSET_FILTERS = ['ALL', 'METRIC', 'DIMENSION', 'DATASET'] as const;
type Asset = {
  assetId: string;
  target: DeploymentKey;
  /** 执行环境显示名，历史响应回退到环境编号。 */
  environmentName?: string;
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
function useList<T>(path: string, refresh: number, retainRows = false) {
  const [items, setItems] = useState<T[] | null>(null);
  const [loadedRefresh, setLoadedRefresh] = useState(-1);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    if (!retainRows) setItems(null);
    setError('');
    createHttpClient()<T[]>(path)
      .then((data) => {
        if (!Array.isArray(data)) throw new Error('资产服务响应无效');
        if (active) {
          setItems(data);
          setLoadedRefresh(refresh);
        }
      })
      .catch((e) => {
        if (active) setError(errorText(e));
      });
    return () => {
      active = false;
    };
  }, [path, refresh, retainRows]);
  return { items, error, current: loadedRefresh === refresh };
}
export function AssetGrantPanel({ initialId = '' }: { initialId?: string }) {
  const [user, setUser] = useState(initialId);
  const [search, setSearch] = useState('');
  const [refresh, setRefresh] = useState(0);
  const { items, error } = useList<Account>(ACCOUNTS, refresh);
  const selected = items?.find((account) => account.id === user) ?? items?.[0];
  const matches = items?.filter((account) =>
    account.username.toLowerCase().includes(search.trim().toLowerCase()),
  );
  return (
    <div className="account-workspace">
      <aside className="account-directory" aria-label="只读账号目录">
        <header className="directory-heading">
          <h2>只读账号 {items && <span>{items.length}</span>}</h2>
          <Button
            type="text"
            size="small"
            title="刷新账号"
            aria-label="刷新账号"
            icon={<ReloadOutlined />}
            onClick={() => setRefresh((n) => n + 1)}
          />
        </header>
        {error ? (
          <Alert type="error" showIcon title={error} />
        ) : !items ? (
          <p>正在读取账号…</p>
        ) : items.length > 0 ? (
          <>
            <div className="directory-desktop">
              <Input
                aria-label="搜索账号"
                placeholder="搜索账号"
                prefix={<SearchOutlined aria-hidden />}
                value={search}
                allowClear
                onChange={(e) => setSearch(e.target.value)}
              />
              <ul className="reader-list">
                {matches?.map((account) => (
                  <li key={account.id}>
                    <button
                      type="button"
                      aria-pressed={selected?.id === account.id}
                      onClick={() => setUser(account.id)}
                    >
                      <UserOutlined aria-hidden />
                      <span>{account.username}</span>
                      {selected?.id === account.id && <CheckOutlined aria-hidden />}
                    </button>
                  </li>
                ))}
              </ul>
              {matches?.length === 0 && <p>没有匹配的账号</p>}
            </div>
            <select
              className="reader-select"
              aria-label="授权账号"
              value={selected?.id}
              onChange={(e) => setUser(e.target.value)}
            >
              {items.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.username}
                </option>
              ))}
            </select>
          </>
        ) : (
          <p>暂无只读账号</p>
        )}
      </aside>
      {selected ? (
        <AccountGrants key={selected.id} account={selected} />
      ) : (
        <section className="grants-placeholder">
          <SafetyCertificateOutlined aria-hidden />
          <h2>账号资产权限</h2>
          <p>
            {items?.length === 0
              ? '新建只读账号后，即可为其分配资产权限。'
              : '账号载入后，可在此查看和调整授权。'}
          </p>
        </section>
      )}
    </div>
  );
}

const ASSET_ICON = {
  METRIC: BarChartOutlined,
  DIMENSION: ApartmentOutlined,
  DATASET: TableOutlined,
};

function AccountGrants({ account }: { account: Account }) {
  const [kind, setKind] = useState<(typeof ASSET_FILTERS)[number]>('ALL');
  const [refresh, setRefresh] = useState(0);
  const [saving, setSaving] = useState('');
  const [failure, setFailure] = useState('');
  const path = accountAssets(account.id);
  const { items, error, current } = useList<Asset>(path, refresh, true);
  // 环境编号确定分组，同名环境和不同分支的资产仍各自保留授权身份。
  const environments = new Map<string, Asset[]>();
  const counts = { ALL: items?.length ?? 0, METRIC: 0, DIMENSION: 0, DATASET: 0 };
  for (const asset of items ?? []) {
    counts[asset.kind]++;
    if (kind !== 'ALL' && kind !== asset.kind) continue;
    const id = asset.target.environmentId;
    const group = environments.get(id);
    if (group) group.push(asset);
    else environments.set(id, [asset]);
  }
  async function toggle(a: Asset) {
    if (saving || !current) return;
    setSaving(a.assetId);
    setFailure('');
    try {
      await createHttpClient()(path + '/' + encodeURIComponent(a.assetId), {
        method: a.granted ? 'DELETE' : 'PUT',
      });
      setRefresh((n) => n + 1);
    } catch (e) {
      setFailure(errorText(e));
    } finally {
      setSaving('');
    }
  }
  return (
    <section className="account-grants" aria-label={account.username + ' 的资产权限'}>
      <header className="reader-profile">
        <span className="reader-avatar" aria-hidden>
          {Array.from(account.username)[0]?.toUpperCase()}
        </span>
        <div>
          <h2>{account.username}</h2>
          <p>
            <UserOutlined aria-hidden />
            只读账号
          </p>
        </div>
      </header>
      <div className="grant-heading">
        <div>
          <h3>资产权限</h3>
          {items && (
            <p>
              已授权 <strong>{items.filter((asset) => asset.granted).length}</strong> /{' '}
              {items.length} 项资产
            </p>
          )}
        </div>
        <Button
          type="text"
          size="small"
          icon={<ReloadOutlined aria-hidden />}
          onClick={() => {
            setFailure('');
            setRefresh((n) => n + 1);
          }}
        >
          刷新资产
        </Button>
      </div>
      {items && items.length > 0 && (
        <fieldset className="grant-type-filter" aria-label="资产类型">
          {ASSET_FILTERS.map((type) => (
            <label key={type}>
              <input
                type="radio"
                name="grant-asset-type"
                checked={kind === type}
                onChange={() => setKind(type)}
              />
              <span>
                {type === 'ALL' ? '全部' : LABEL[type]} <small>{counts[type]}</small>
              </span>
            </label>
          ))}
        </fieldset>
      )}
      {(error || failure) && <Alert type="error" showIcon title={error || failure} />}
      {!items && !error && <p className="grant-empty">正在读取资产…</p>}
      {items?.length === 0 && <p className="grant-empty">暂无可授权资产</p>}
      {items && items.length > 0 && kind !== 'ALL' && !counts[kind] && (
        <p className="grant-empty">暂无{LABEL[kind]}资产</p>
      )}
      {Array.from(environments, ([id, assets]) => (
        <section key={id} aria-labelledby={`grant-env-${id}`}>
          <h4 id={`grant-env-${id}`}>
            <DatabaseOutlined aria-hidden /> {assets[0].environmentName || id}
          </h4>
          <ul className="grant-list" aria-busy={!current && !error}>
            {assets.map((asset) => {
              const Icon = ASSET_ICON[asset.kind];
              return (
                <li key={asset.assetId}>
                  <div className="grant-identity">
                    <span className="asset-symbol" aria-hidden>
                      <Icon />
                    </span>
                    <div>
                      <div className="asset-title">
                        <strong>{asset.dimensionName || asset.name}</strong>
                        <span>{LABEL[asset.kind]}</span>
                      </div>
                      <code>{asset.nodeId}</code>
                      <div className="asset-source">
                        <span>
                          <FolderOutlined aria-label="仓库" /> {asset.target.repository}
                        </span>
                        <span>
                          <BranchesOutlined aria-label="分支" /> {asset.target.branchName}
                        </span>
                      </div>
                      {!asset.buildId && <span className="asset-absent">当前未部署</span>}
                    </div>
                  </div>
                  <span className={'grant-state' + (asset.granted ? ' is-granted' : '')}>
                    {asset.granted ? <CheckOutlined aria-hidden /> : <LockOutlined aria-hidden />}
                    {asset.granted ? '已授权' : '未授权'}
                  </span>
                  <Button
                    size="small"
                    autoInsertSpace={false}
                    loading={saving === asset.assetId}
                    aria-disabled={!!saving || !current}
                    onClick={() => void toggle(asset)}
                  >
                    {asset.granted ? '撤销' : '授予'}
                  </Button>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </section>
  );
}
