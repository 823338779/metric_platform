import { createHttpClient } from './httpClient';

export type ExecutionSnapshot = {
  /** 外部环境 UUID 和不可变配置版本。 */
  environmentId: string;
  configVersion: number;
  /** 物理实例与完整非敏感连接。 */
  connection: {
    type: 'starrocks' | 'postgres' | 'duckdb';
    instanceId: string;
    host?: string | null;
    port?: number | null;
    path?: string | null;
  };
  credentialRef: string;
  catalog: string;
  schema: string;
  threads: number;
  businessTimezone: string;
};
export type EnvironmentInput = Omit<ExecutionSnapshot, 'environmentId' | 'configVersion'> & {
  name: string;
};
export type ExecutionEnvironment = {
  environmentId: string;
  name: string;
  configVersion: number;
  execution: ExecutionSnapshot;
};
export type EnvironmentBuild = {
  buildId: string;
  repository: string;
  branchName: string;
  commitSha: string | null;
  buildStatus: string;
  phase: string;
  createdAt: string;
  configVersion: string;
  execution: ExecutionSnapshot;
  catalogAvailable: boolean;
  queryAvailable: boolean;
  initialDeployment: { deploymentStatus: string } | null;
};
export type BuildCommand = {
  repository: string;
  branchName: string;
  expectedConfigVersion: number;
  deploymentPolicy: 'NONE' | 'ON_SUCCESS';
  idempotencyKey: string;
};
export type EnvironmentBuildPage = { items: EnvironmentBuild[]; nextCursor: string | null };
export type EnvironmentFilter = {
  repository?: string;
  branchName?: string;
  buildStatus?: string;
  cursor?: string;
};
const ROOT = '/api/dbt';
const ENVIRONMENTS = '/environments';
const POST = 'POST';
const PUT = 'PUT';
const request = createHttpClient(undefined, ROOT);
const path = (id: string) => `${ENVIRONMENTS}/${encodeURIComponent(id)}`;
export const environmentApi = {
  list: () => request<{ items: ExecutionEnvironment[] }>(ENVIRONMENTS),
  get: (id: string) => request<ExecutionEnvironment>(path(id)),
  create: (body: EnvironmentInput) =>
    request<ExecutionEnvironment>(ENVIRONMENTS, { method: POST, body: JSON.stringify(body) }),
  update: (id: string, version: number, body: EnvironmentInput) =>
    request<ExecutionEnvironment>(`${path(id)}?expectedConfigVersion=${version}`, {
      method: PUT,
      body: JSON.stringify(body),
    }),
  submitBuild: (id: string, body: BuildCommand) =>
    request<EnvironmentBuild>(`${path(id)}/builds`, { method: POST, body: JSON.stringify(body) }),
  builds: (id: string, filter: EnvironmentFilter = {}) =>
    request<EnvironmentBuildPage>(
      `${path(id)}/builds?${new URLSearchParams(Object.entries(filter).filter(([, value]) => !!value) as [string, string][])}`,
    ),
  getBuild: (id: string) => request<EnvironmentBuild>(`/builds/${encodeURIComponent(id)}`),
  logs: (id: string) =>
    request<{ items: string[]; nextCursor: string | null }>(
      `/builds/${encodeURIComponent(id)}/logs`,
    ),
};
export type EnvironmentApi = typeof environmentApi;
