import {
  AimOutlined,
  ApartmentOutlined,
  FileTextOutlined,
  FolderOutlined,
  MinusSquareOutlined,
  PlusSquareOutlined,
  TableOutlined,
  FunctionOutlined,
  TagsOutlined,
  DownOutlined,
} from '@ant-design/icons';
import { Button, Radio, Skeleton, Tooltip, Tree } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useEffect, useMemo, useRef, useState, type Key } from 'react';
import { Link } from 'react-router-dom';
import type { DbtResource } from '../../api/types';
import { RESOURCE_LABELS } from './resourceDisplay';

const COPY = {
  collapse: '全部折叠',
  expand: '全部展开',
  locate: '定位当前资源',
  explore: '探索数据',
  label: '资源树',
  other: '其他资源',
  byType: '按类型',
  bySource: '按来源',
  grouping: '资源分组方式',
  noSource: '未提供来源',
};
const TYPE = 'type';
const SOURCE = 'source';
const PATH_SEPARATOR = '/';
const DIMENSION = 'DIMENSION';
const TABLE_KINDS = new Set(['TABLE', 'VIEW', 'MODEL']);
const CATEGORY_PREFIX = 'kind:';
const FILE_PREFIX = 'file:';
const RESOURCE_PREFIX = 'resource:';
const METRIC = 'METRIC';
const TITLE_SEPARATOR = '\n';

type ResourceTreeNode = Omit<DataNode, 'children'> & {
  /** 显式声明扁平 DOM 中的树层级，供辅助技术识别分组和资源。 */
  'aria-level': number;
  /** 当前分组下的资源节点。 */
  children?: ResourceTreeNode[];
};

export function ResourceTree({
  resources,
  selected,
  onSelect,
  buildId,
  queryParameter,
  loading = false,
}: {
  resources: DbtResource[];
  selected: string;
  onSelect: (id: string) => void;
  projectId: string;
  buildId: string;
  queryParameter?: 'metricResourceId';
  /** 替换列表内容但保留分组方式，避免筛选加载期间重置导航偏好。 */
  loading?: boolean;
}) {
  const host = useRef<HTMLDivElement>(null);
  /** 分组只影响导航结构，不改变固定版本、筛选范围或选中资源。 */
  const [grouping, setGrouping] = useState(TYPE);
  const [expanded, setExpanded] = useState<Key[]>([]);
  const [locateRequest, setLocateRequest] = useState(0);
  const previousFolders = useRef(new Set<string>());
  const { data, folders, ancestors } = useMemo(() => {
    const data: ResourceTreeNode[] = [];
    const folders: string[] = [];
    const ancestors = new Map<string, string[]>();
    const groups = new Map<string, ResourceTreeNode>();
    resources.forEach((resource) => {
      const kind = resource.kind.toUpperCase();
      const path = resource.sourcePath;
      const bySource = grouping === SOURCE;
      const groupKey = bySource ? FILE_PREFIX + (path ?? '') : CATEGORY_PREFIX + kind;
      let group = groups.get(groupKey);
      if (!group) {
        group = {
          key: groupKey,
          'aria-level': 1,
          title: bySource ? (
            <span title={path ?? undefined} className="resource-tree-group">
              {path?.split(PATH_SEPARATOR).at(-1) || COPY.noSource}
              {path?.includes(PATH_SEPARATOR) && (
                <small>{path.slice(0, path.lastIndexOf(PATH_SEPARATOR))}</small>
              )}
            </span>
          ) : (
            (RESOURCE_LABELS[kind] ?? COPY.other)
          ),
          icon: bySource ? <FileTextOutlined aria-hidden /> : <FolderOutlined aria-hidden />,
          selectable: false,
          children: [],
        };
        groups.set(groupKey, group);
        data.push(group);
        folders.push(groupKey);
      }
      ancestors.set(resource.resourceId, [groupKey]);
      group.children!.push({
        key: RESOURCE_PREFIX + resource.resourceId,
        'aria-level': 2,
        isLeaf: true,
        icon:
          kind === METRIC ? (
            <FunctionOutlined aria-hidden />
          ) : kind === DIMENSION ? (
            <TagsOutlined aria-hidden />
          ) : TABLE_KINDS.has(kind) ? (
            <TableOutlined aria-hidden />
          ) : (
            <ApartmentOutlined aria-hidden />
          ),
        title: (
          <span className="resource-tree-leaf">
            <span
              className="resource-tree-leaf-copy"
              title={[resource.name, path, resource.description]
                .filter(Boolean)
                .join(TITLE_SEPARATOR)}
            >
              <span className="resource-tree-leaf-name">{resource.name}</span>
              <small aria-hidden>
                {bySource ? (RESOURCE_LABELS[kind] ?? COPY.other) : path || COPY.noSource}
              </small>
            </span>
            {kind === METRIC && resource.queryable && (
              <Link
                onClick={(event) => event.stopPropagation()}
                to={`/dbt/builds/${encodeURIComponent(buildId)}/query?${new URLSearchParams({ buildId, [queryParameter ?? 'metric']: queryParameter ? resource.resourceId : resource.name })}`}
              >
                {COPY.explore}
              </Link>
            )}
            {resource.granted !== undefined && (
              <span className="resource-access" data-granted={resource.granted}>
                {resource.granted ? '有权限' : '无权限'}
              </span>
            )}
          </span>
        ),
      });
    });
    return { data, folders, ancestors };
  }, [resources, buildId, queryParameter, grouping]);
  // 新加载的目录默认展开；用户随后折叠不会被普通重渲染覆盖。
  useEffect(() => {
    const added = folders.filter((key) => !previousFolders.current.has(key));
    previousFolders.current = new Set(folders);
    setExpanded((previous) => [
      ...previous.filter((key) => previousFolders.current.has(String(key))),
      ...added,
    ]);
  }, [folders]);
  useEffect(() => {
    const path = ancestors.get(selected);
    if (!path) return;
    setExpanded((previous) => [...new Set([...previous, ...path])]);
    const timer = window.setTimeout(
      () =>
        host.current
          ?.querySelector('[aria-selected="true"]')
          ?.scrollIntoView?.({ block: 'nearest' }),
      0,
    );
    return () => window.clearTimeout(timer);
  }, [selected, ancestors, locateRequest]);
  return (
    <div ref={host} className="resource-tree">
      <div className="resource-tree-toolbar">
        <Radio.Group
          size="small"
          value={grouping}
          onChange={(event) => setGrouping(event.target.value)}
          aria-label={COPY.grouping}
        >
          <Radio.Button value={TYPE}>{COPY.byType}</Radio.Button>
          <Radio.Button value={SOURCE}>{COPY.bySource}</Radio.Button>
        </Radio.Group>
        <div className="resource-tree-actions">
          <Tooltip title={COPY.expand}>
            <Button
              size="small"
              type="text"
              aria-label={COPY.expand}
              icon={<PlusSquareOutlined />}
              onClick={() => setExpanded(folders)}
            />
          </Tooltip>
          <Tooltip title={COPY.collapse}>
            <Button
              size="small"
              type="text"
              aria-label={COPY.collapse}
              icon={<MinusSquareOutlined />}
              onClick={() => setExpanded([])}
            />
          </Tooltip>
          <Tooltip title={COPY.locate}>
            <Button
              size="small"
              type="text"
              aria-label={COPY.locate}
              disabled={!selected}
              icon={<AimOutlined />}
              onClick={() => setLocateRequest((value) => value + 1)}
            />
          </Tooltip>
        </div>
      </div>
      <div className="resource-tree-body">
        {loading ? (
          <Skeleton active title={false} paragraph={{ rows: 6 }} className="catalog-tree-loading" />
        ) : (
          <Tree
            aria-label={COPY.label}
            showIcon
            switcherIcon={<DownOutlined aria-hidden />}
            blockNode
            virtual={false}
            treeData={data}
            expandedKeys={expanded}
            autoExpandParent={false}
            selectedKeys={selected ? [RESOURCE_PREFIX + selected] : []}
            onExpand={(keys) => setExpanded(keys)}
            onSelect={(keys) => {
              const key = String(keys[0] ?? '');
              if (key.startsWith(RESOURCE_PREFIX)) onSelect(key.slice(RESOURCE_PREFIX.length));
            }}
          />
        )}
      </div>
    </div>
  );
}
