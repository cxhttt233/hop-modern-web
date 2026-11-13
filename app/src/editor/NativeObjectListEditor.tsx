import { readNativeWireList, resolveNativeWireListItem, writeNativeWireList, type HopNativeWireDescriptor } from "@hop-modern/contracts";
import type { ConfigPropertyDescriptor } from "./configDescriptor";
import { canEditNativeList, NativeListEditor } from "./NativeListEditor";

/** The only nested-object table layout admitted by the pinned Hop producer. */
const SELECT_LISTS = [
  { key: "field", title: "选取与重命名字段" },
  { key: "remove", title: "移除字段" },
  { key: "meta", title: "转换字段元数据" },
] as const;

function asRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function canEditSelectValuesObject(
  descriptor: HopNativeWireDescriptor & { className: string },
  property: ConfigPropertyDescriptor, value: unknown,
): value is Record<string, unknown> | null | undefined {
  if (descriptor.className !== "org.apache.hop.pipeline.transforms.selectvalues.SelectValuesMeta" ||
      property.key !== "fields" || property.shape !== "OBJECT" ||
      (value != null && !asRecord(value))) return false;
  const children = property.children ?? [];
  // Never discard a future native property by pretending that the UI owns it.
  if (children.length !== 4 ||
      !children.some(child => child.key === "select_unspecified" && child.shape === "BOOLEAN")) return false;
  return SELECT_LISTS.every(({ key }) => {
    const propertyPath = "fields." + key;
    const child = children.find(candidate => candidate.key === key);
    if (!child || child.shape !== "LIST") return false;
    // A wire pointer must be explicitly attested by the server, not guessed from groupKey.
    const pointer = resolveNativeWireListItem(descriptor, propertyPath, 0)?.listPointer;
    if (pointer !== "/fields/" + key) return false;
    const rows = asRecord(value) ? value[key] : undefined;
    return canEditNativeList(rows == null ? [] : rows, child.elementProperties ?? []);
  });
}

interface Props {
  descriptor: HopNativeWireDescriptor & { className: string };
  property: ConfigPropertyDescriptor;
  value: Record<string, unknown>;
  disabled?: boolean;
  onChange: (value: Record<string, unknown>) => void;
}

/** Scoped native SelectValues editor. All updates remain local until the parent Save. */
export function NativeObjectListEditor({ descriptor, property, value, disabled, onChange }: Props) {
  const children = property.children ?? [];
  return <div className="config-native-object-lists">
    <p>原生字段操作将保持三组列表各自的顺序；修改仅暂存，保存配置时才写入 Hop。</p>
    {SELECT_LISTS.map(({ key, title }) => {
      const child = children.find(item => item.key === key);
      if (!child) return null;
      const path = "fields." + key;
      // Producer-attested native path: defensive copy-on-write preserves every other list.
      const rows = readNativeWireList({ fields: value }, descriptor, path) ?? [];
      return <section className="config-native-list-section" key={key} aria-label={title}>
        <h4>{title}</h4>
        <NativeListEditor title={title} properties={child.elementProperties ?? []} value={rows}
          disabled={disabled} onChange={nextRows => {
            const changed = writeNativeWireList({ fields: value }, descriptor, path, nextRows);
            const nextFields = changed.fields;
            if (!asRecord(nextFields)) throw new Error("Native SelectValues fields must be an object");
            onChange(nextFields);
          }} />
      </section>;
    })}
    <label className="config-native-select-unspecified">
      <input type="checkbox" checked={value.select_unspecified === true} disabled={disabled}
        onChange={event => onChange({ ...value, select_unspecified: event.target.checked })} />
      同时保留并排序未选择的字段
    </label>
  </div>;
}
