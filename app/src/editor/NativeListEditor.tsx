import type { ConfigPropertyDescriptor } from "./configDescriptor";

interface Props {
  title: string;
  value: unknown[];
  properties: ConfigPropertyDescriptor[];
  disabled?: boolean;
  onChange: (rows: unknown[]) => void;
}

const LABELS: Record<string, string> = {
  name: "字段名称", type: "数据类型", format: "格式", length: "长度",
  precision: "精度", ascending: "升序", case_sensitive: "大小写敏感",
};
const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value);

export function canEditNativeList(value: unknown, properties: ConfigPropertyDescriptor[]): value is unknown[] {
  return Array.isArray(value) && value.every(isRecord) && properties.length > 0 &&
    properties.every(p => !p.sensitive && ["STRING", "NUMBER", "BOOLEAN", "ENUM"].includes(p.shape));
}

/**
 * Typed rows of a producer-attested native LIST; untouched row properties remain intact.
 * Hop's Java serializer remains authoritative for JSON/XML persistence and validation.
 */
export function NativeListEditor({ title, value: rows, properties, disabled, onChange }: Props) {
  const edit = (index: number, column: ConfigPropertyDescriptor, nextValue: unknown) =>
    onChange(rows.map((row, i) => i === index ?
      { ...(row as Record<string, unknown>), [column.key]: nextValue } : row));
  const move = (from: number, to: number) => {
    const changed = [...rows];
    const [item] = changed.splice(from, 1);
    changed.splice(to, 0, item);
    onChange(changed);
  };
  const add = () => onChange([...rows, Object.fromEntries(properties.map(p =>
    [p.key, p.shape === "BOOLEAN" ? (p.nativeBooleanDefault ?? false) :
      p.shape === "NUMBER" ? (p.nativeNumberDefault ?? 0) : (p.nativeStringDefault ?? "")]))]);
  return <div className="config-native-list">
    <div className="config-native-list-scroll">
      <table aria-label={title + "原生字段列表"}>
        <thead><tr>{properties.map(column =>
          <th scope="col" key={column.key}>{LABELS[column.key] ?? column.key}</th>)}<th scope="col">操作</th></tr></thead>
        <tbody>{rows.map((entry, index) => {
          const row = entry as Record<string, unknown>;
          return <tr key={index}>{properties.map(column => {
            const val = row[column.key];
            const aria = title + " 第" + (index + 1) + "行 " + column.key;
            const options = column.options ?? [];
            return <td key={column.key}>{
              column.shape === "BOOLEAN"
                ? <input type="checkbox" aria-label={aria} disabled={disabled}
                    checked={val === true} onChange={e => edit(index, column, e.target.checked)} />
                : column.shape === "ENUM" && options.length > 0
                ? <select aria-label={aria} disabled={disabled} value={val == null ? "" : String(val)}
                    onChange={e => edit(index, column, e.target.value)}>
                    {options.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                  </select>
                : <input aria-label={aria} disabled={disabled}
                    type={column.shape === "NUMBER" ? "number" : "text"}
                    step={column.shape === "NUMBER" ? "1" : undefined}
                    value={val == null ? "" : String(val)}
                    onChange={e => {
                      const raw = e.target.value;
                      edit(index, column, column.shape === "NUMBER" ? (raw === "" ? 0 : Number(raw)) : raw);
                    }} />
            }</td>;
          })}<td className="config-native-list-actions">
              <button type="button" aria-label={title + " 上移第" + (index + 1) + "行"}
                disabled={disabled || index === 0} onClick={() => move(index, index - 1)}>↑</button>
              <button type="button" aria-label={title + " 下移第" + (index + 1) + "行"}
                disabled={disabled || index === rows.length - 1} onClick={() => move(index, index + 1)}>↓</button>
              <button type="button" aria-label={title + " 删除第" + (index + 1) + "行"}
                disabled={disabled} onClick={() => onChange(rows.filter((_, i) => i !== index))}>删除</button>
            </td></tr>;
        })}</tbody>
      </table>
    </div>
    {rows.length === 0 && <small className="config-native-list-empty">当前没有字段行</small>}
    <button type="button" className="config-native-list-add" disabled={disabled} onClick={add}>
      添加字段行
    </button>
  </div>;
}
