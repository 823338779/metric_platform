import {
  AimOutlined,
  ApartmentOutlined,
  FileTextOutlined,
  FolderOutlined,
  MinusSquareOutlined,
  PlusSquareOutlined,
} from '@ant-design/icons';
import { Button, Tooltip, Tree } from 'antd';
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
  unknown: '未提供来源文件',
};
const CATEGORY_PREFIX = 'kind:';
const FILE_PREFIX = 'file:';
const RESOURCE_PREFIX = 'resource:';
const METRIC = 'METRIC';

export function ResourceTree({
  resources,
  selected,
  onSelect,
  projectId,
  releaseId,
}: {
  resources: DbtResource[];
  selected: string;
  onSelect: (id: string) => void;
  projectId: string;
  releaseId: string;
}) {
  const host = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState<Key[]>([]);
  const [locateRequest, setLocateRequest] = useState(0);
  const previousFolders = useRef(new Set<string>());
  const { data, folders, ancestors } = useMemo(() => {
    const data: DataNode[] = [];
    const folders: string[] = [];
    const ancestors = new Map<string, string[]>();
    const categories = new Map<string, DataNode>();
    const files = new Map<string, DataNode>();
    resources.forEach((resource) => {
      const kind = resource.kind.toUpperCase();
      const categoryKey = CATEGORY_PREFIX + kind;
      let category = categories.get(kind);
      if (!category) {
        category = {
          key: categoryKey,
          title: RESOURCE_LABELS[kind] ?? COPY.other,
          icon: <FolderOutlined />,
          selectable: false,
          children: [],
        };
        categories.set(kind, category);
        data.push(category);
        folders.push(categoryKey);
      }
      const path = resource.sourcePath ?? COPY.unknown;
      const fileKey = FILE_PREFIX + kind + path;
      let file = files.get(fileKey);
      if (!file) {
        file = {
          key: fileKey,
          title: <span title={path}>{path}</span>,
          icon: <FileTextOutlined />,
          selectable: false,
          children: [],
        };
        files.set(fileKey, file);
        category.children!.push(file);
        folders.push(fileKey);
      }
      ancestors.set(resource.resourceId, [categoryKey, fileKey]);
      file.children!.push({
        key: RESOURCE_PREFIX + resource.resourceId,
        isLeaf: true,
        icon: <ApartmentOutlined />,
        title: (
          <span className="resource-tree-leaf">
            <span
              className="resource-tree-leaf-name"
              title={resource.description ?? resource.resourceId}
            >
              {resource.name}
            </span>
            {kind === METRIC && resource.queryable && (
              <Link
                onClick={(event) => event.stopPropagation()}
                to={`/dbt/projects/${encodeURIComponent(projectId)}/query?releaseId=${encodeURIComponent(releaseId)}&metric=${encodeURIComponent(resource.name)}`}
              >
                {COPY.explore}
              </Link>
            )}
          </span>
        ),
      });
    });
    return { data, folders, ancestors };
  }, [resources, projectId, releaseId]);
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
    <div ref={host}>
      <div className="resource-tree-toolbar">
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
      <div className="resource-tree-body">
        <Tree
          aria-label={COPY.label}
          showIcon
          showLine
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
      </div>
    </div>
  );
}
