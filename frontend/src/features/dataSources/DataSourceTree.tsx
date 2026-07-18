import {
  DatabaseOutlined,
  FolderOpenOutlined,
  TableOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import { Badge, Input, Tree, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Key } from 'react';
import { metricPlatformApi } from '../../api/metricPlatformApi';
import type { DataSourceSummary, SourceLocation } from '../../api/types';
import { safeDataSourceError, treeKey, type CatalogNodeType } from './dataSourceHelpers';

export type CatalogTreeNode = DataNode & {
  nodeType: CatalogNodeType;
  sourceId: string;
  location?: Partial<SourceLocation>;
  fieldType?: string;
};

export function DataSourceTree({
  sources,
  selectedKey,
  onSelect,
  onError,
}: {
  sources: DataSourceSummary[];
  selectedKey?: Key;
  onSelect: (node: CatalogTreeNode) => void;
  onError: (message: string) => void;
}) {
  const [nodes, setNodes] = useState<CatalogTreeNode[]>([]);
  const [search, setSearch] = useState('');
  const controller = useRef<AbortController | undefined>(undefined);
  useEffect(() => setNodes(sources.map((source) => sourceNode(source))), [sources]);
  const load = async (node: CatalogTreeNode) => {
    if (node.children?.length || node.isLeaf) return;
    controller.current?.abort();
    const current = new AbortController();
    controller.current = current;
    try {
      let children: CatalogTreeNode[] = [];
      const location = node.location ?? {};
      if (node.nodeType === 'source')
        children = (await metricPlatformApi.listCatalogs(node.sourceId, current.signal)).map(
          (item) => catalogNode(node.sourceId, item.name),
        );
      if (node.nodeType === 'catalog')
        children = (
          await metricPlatformApi.listSchemas(node.sourceId, location.catalogName, current.signal)
        ).map((item) => schemaNode(node.sourceId, item.catalogName, item.schemaName));
      if (node.nodeType === 'schema')
        children = (
          await metricPlatformApi.listSourceObjects(node.sourceId, location, current.signal)
        ).map((item) => objectNode(node.sourceId, item));
      if (node.nodeType === 'object')
        children = (
          await metricPlatformApi.listSourceFields(
            node.sourceId,
            location as SourceLocation,
            current.signal,
          )
        ).map((item) =>
          fieldNode(
            node.sourceId,
            location as SourceLocation,
            item.physicalName,
            item.physicalType,
          ),
        );
      setNodes((currentNodes) => replaceChildren(currentNodes, node.key, children));
    } catch (error) {
      const message = safeDataSourceError(error);
      if (message) onError(message);
    }
  };
  const visible = useMemo(
    () => (search ? filterNodes(nodes, search.toLowerCase()) : nodes),
    [nodes, search],
  );
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <Input.Search
        allowClear
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        placeholder="搜索已加载的资源"
        style={{ marginBottom: 12 }}
      />
      <Tree
        blockNode
        showLine
        treeData={visible}
        loadData={(node) => load(node as CatalogTreeNode)}
        selectedKeys={selectedKey ? [selectedKey] : []}
        onSelect={(_, info) => onSelect(info.node as CatalogTreeNode)}
        style={{ overflow: 'auto', flex: 1, background: 'transparent' }}
      />
    </div>
  );
}

function sourceNode(source: DataSourceSummary): CatalogTreeNode {
  return {
    key: treeKey('source', source.id),
    nodeType: 'source',
    sourceId: source.id,
    title: (
      <span>
        <DatabaseOutlined /> {source.name}{' '}
        <Badge
          status={
            source.healthStatus === 'HEALTHY'
              ? 'success'
              : source.healthStatus === 'UNHEALTHY'
                ? 'error'
                : 'default'
          }
        />
      </span>
    ),
    isLeaf: source.status !== 'ACTIVE',
  };
}
function catalogNode(sourceId: string, name: string): CatalogTreeNode {
  return {
    key: treeKey('catalog', sourceId, { catalogName: name }),
    nodeType: 'catalog',
    sourceId,
    location: { catalogName: name },
    title: (
      <span>
        <FolderOpenOutlined /> {name}
      </span>
    ),
  };
}
function schemaNode(
  sourceId: string,
  catalogName: string | undefined,
  schemaName: string,
): CatalogTreeNode {
  return {
    key: treeKey('schema', sourceId, { catalogName, schemaName }),
    nodeType: 'schema',
    sourceId,
    location: { catalogName, schemaName },
    title: (
      <span>
        <FolderOpenOutlined /> {schemaName}
      </span>
    ),
  };
}
function objectNode(
  sourceId: string,
  item: { catalogName?: string; schemaName?: string; objectName: string; objectType: string },
): CatalogTreeNode {
  return {
    key: treeKey('object', sourceId, item),
    nodeType: 'object',
    sourceId,
    location: item,
    isLeaf: false,
    title: (
      <span>
        <TableOutlined /> {item.objectName}{' '}
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {item.objectType}
        </Typography.Text>
      </span>
    ),
  };
}
function fieldNode(
  sourceId: string,
  location: SourceLocation,
  name: string,
  type: string,
): CatalogTreeNode {
  return {
    key: treeKey('field', sourceId, { ...location, fieldName: name }),
    nodeType: 'field',
    sourceId,
    location,
    fieldType: type,
    isLeaf: true,
    title: (
      <span>
        <UnorderedListOutlined /> {name}{' '}
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {type}
        </Typography.Text>
      </span>
    ),
  };
}
function replaceChildren(
  nodes: CatalogTreeNode[],
  key: Key,
  children: CatalogTreeNode[],
): CatalogTreeNode[] {
  return nodes.map((node) =>
    node.key === key
      ? { ...node, children }
      : node.children
        ? { ...node, children: replaceChildren(node.children as CatalogTreeNode[], key, children) }
        : node,
  );
}
function filterNodes(nodes: CatalogTreeNode[], needle: string): CatalogTreeNode[] {
  return nodes.flatMap((node) => {
    const children = node.children ? filterNodes(node.children as CatalogTreeNode[], needle) : [];
    const text = String(typeof node.title === 'string' ? node.title : node.key).toLowerCase();
    return text.includes(needle) || children.length ? [{ ...node, children }] : [];
  });
}
