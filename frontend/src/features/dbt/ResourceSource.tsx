import { AimOutlined, CopyOutlined, FileTextOutlined } from '@ant-design/icons';
import { Alert, Button, Checkbox, Space, Typography } from 'antd';
import { useRef, useState } from 'react';
import type { DbtSourceDocument } from '../../api/types';

const COPY = {
  copy: '复制源码',
  copied: '已复制',
  failed: '复制失败，请手动选择源码复制',
  wrap: '自动换行',
  locate: '定位定义',
  sha: '来源提交：',
  empty: '该文件没有源码内容',
  label: 'YAML 源码内容',
  ambiguous: '存在多个同名定义，请结合源码上下文确认。',
};
const EMPTY = '';
const NEWLINE = '\n';
const CODE_PATTERN = /^(\s*(?:-\s+)?)([\w.-]+)(\s*:)(.*)$/;
const SOURCE_SECTIONS: Record<string, string> = {
  METRIC: 'metrics',
  SEMANTIC_MODEL: 'semantic_models',
  TABLE: 'models',
  VIEW: 'models',
  MODEL: 'models',
};

export function ResourceSource({
  source,
  name,
  kind,
}: {
  source: DbtSourceDocument;
  name: string;
  kind: string;
}) {
  const [wrap, setWrap] = useState(true);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState(EMPTY);
  const definition = useRef<HTMLLIElement>(null);
  const lines = source.content.split(/\r?\n/);
  let section = EMPTY;
  const matches: number[] = [];
  // 同文件中的度量和指标可能同名；限定顶层集合，仍有歧义时不误指向首个匹配。
  lines.forEach((line, index) => {
    section = line.match(/^([\w-]+):/)?.[1] ?? section;
    const value = line.match(/^\s*(?:-\s+)?name:\s*(.*?)\s*(?:#.*)?$/)?.[1];
    if (
      (!SOURCE_SECTIONS[kind] || section === SOURCE_SECTIONS[kind]) &&
      value?.replace(/^['"]|['"]$/g, EMPTY) === name
    )
      matches.push(index);
  });
  const target = matches.length === 1 ? matches[0] : -1;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(source.content);
      setCopied(true);
      setError(EMPTY);
    } catch {
      setError(COPY.failed);
    }
  };
  return (
    <section className="source-reader">
      <div className="resource-toolbar">
        <Space>
          <FileTextOutlined />
          <strong className="source-path">{source.sourcePath}</strong>
        </Space>
        <Space wrap>
          <Checkbox checked={wrap} onChange={(event) => setWrap(event.target.checked)}>
            {COPY.wrap}
          </Checkbox>
          <Button
            icon={<AimOutlined aria-hidden />}
            disabled={target < 0}
            onClick={() =>
              definition.current?.scrollIntoView({ block: 'center', behavior: 'auto' })
            }
          >
            {COPY.locate}
          </Button>
          <Button icon={<CopyOutlined aria-hidden />} onClick={() => void copy()}>
            {copied ? COPY.copied : COPY.copy}
          </Button>
        </Space>
      </div>
      <div className="source-metadata">
        <span>{source.buildSourceSha ? COPY.sha : '发布版本：'}</span>
        <Typography.Text code copyable>
          {source.buildSourceSha || source.releaseId}
        </Typography.Text>
      </div>
      {error && <Alert type="warning" title={error} />}
      {matches.length > 1 && <Alert type="info" title={COPY.ambiguous} />}
      {source.content ? (
        <div
          className={wrap ? 'source-code is-wrapped' : 'source-code'}
          role="region"
          aria-label={COPY.label}
        >
          <ol>
            {lines.map((line, index) => {
              const match = line.match(CODE_PATTERN);
              return (
                <li
                  key={index}
                  ref={index === target ? definition : undefined}
                  className={index === target ? 'source-definition' : undefined}
                >
                  <code>
                    {match ? (
                      <>
                        {match[1]}
                        <span className="source-key">{match[2]}</span>
                        {match[3]}
                        <span className="source-value">{match[4]}</span>
                      </>
                    ) : (
                      line || NEWLINE
                    )}
                  </code>
                </li>
              );
            })}
          </ol>
        </div>
      ) : (
        <div className="resource-empty">{COPY.empty}</div>
      )}
    </section>
  );
}
