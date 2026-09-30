import type { DbtResource, DbtResourcePresentation } from '../../api/types';

const OBJECT = 'object';
const STRING = 'string';
const BOOLEAN = 'boolean';
const NODE_FLAGS = ['valueNode', 'containerNode', 'missingNode', 'textual', 'bigDecimal'];
const CATALOG_METADATA = '_platformCatalog';
const PHYSICAL_SECTION = '物理关系';
const INVALID_DEFINITION = '原始资源定义无法还原展示字段';

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === OBJECT && !Array.isArray(value);
}

/** 识别 Jackson 2 节点被 Jackson 3 当作 Bean 输出的完整特征，避免误伤同名业务字段。 */
export function hasSerializedNode(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(hasSerializedNode);
  if (!isRecord(value)) return false;
  return (
    (typeof value.nodeType === STRING &&
      NODE_FLAGS.every((key) => typeof value[key] === BOOLEAN)) ||
    Object.values(value).some(hasSerializedNode)
  );
}

/** 旧服务尚未重启时，按接口已有的分区和字段从同版本原始定义恢复真实值。 */
export function restoreArtifactPresentation(
  presentation: DbtResourcePresentation,
  resource: DbtResource,
): DbtResourcePresentation {
  if (resource.resourceId !== presentation.resourceId || resource.kind !== presentation.kind)
    throw new Error(INVALID_DEFINITION);
  const definition: unknown = JSON.parse(resource.definitionJson);
  if (!isRecord(definition)) throw new Error(INVALID_DEFINITION);
  const { [CATALOG_METADATA]: catalog, ...nativeDetails } = definition;
  const sections = presentation.sections.map((section) => {
    const original = section.title === PHYSICAL_SECTION ? catalog : nativeDetails;
    const fields = Object.fromEntries(
      Object.entries(section.fields).map(([key, value]) => {
        if (!hasSerializedNode(value)) return [key, value];
        if (!isRecord(original) || !Object.hasOwn(original, key))
          throw new Error(INVALID_DEFINITION);
        return [key, original[key]];
      }),
    );
    return { ...section, fields };
  });
  const restored = { ...presentation, sections, nativeDetails };
  if (hasSerializedNode(restored)) throw new Error(INVALID_DEFINITION);
  return restored;
}
