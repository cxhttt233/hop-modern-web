import { useEffect, useRef, useState } from "react";
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
  const pendingFocusRow = useRef<number | null>(null);
  const tableRef = useRef<HTMLTableElement>(null);
  const [query, setQuery] = useState("");
  const needle = query.trim().toLocaleLowerCase();
  const filtering = needle.length > 0;
  // All mutations must use the original, unfiltered row index.
  const visibleRows = rows.map((entry, index) => ({ entry, index })).filter(({ entry }) =>
    !filtering || properties.some(column => {
      const value = (entry as Record<string, unknown>)[column.key];
      return String(value ?? "").toLocaleLowerCase().includes(needle) ||
        (column.shape === "ENUM" && column.options?.some(option =>
          option.value === String(value) && option.label.toLocaleLowerCase().includes(needle)));
    }));
  useEffect(() => {
    const index = pendingFocusRow.current;
    if (index === null || index >= rows.length) return;
    pendingFocusRow.current = null;
    tableRef.current?.querySelectorAll<HTMLTableRowElement>("tbody tr")[index]
      ?.querySelector<HTMLInputElement | HTMLSelectElement>("input:not([type=checkbox]), select, input[type=checkbox]")?.focus();
  }, [rows]);
  const edit = (index: number, column: ConfigPropertyDescriptor, nextValue: unknown) =>
    onChange(rows.map((row, i) => i === index ?
      { ...(row as Record<string, unknown>), [column.key]: nextValue } : row));
  const move = (from: number, to: number) => {
    const changed = [...rows];
    const [item] = changed.splice(from, 1);
    changed.splice(to, 0, item);
    onChange(changed);
  };
  // Duplicate the entire native row, including properties not shown in the editor.
  // This lets users add similar fields without silently dropping Hop metadata.
  const duplicate = (index: number) => {
    const row = rows[index] as Record<string, unknown>;
    onChange([...rows.slice(0, index + 1), { ...row }, ...rows.slice(index + 1)]);
  };
  const add = () => {
    pendingFocusRow.current = rows.length;
    onChange([...rows, Object.fromEntries(properties.map(p =>
    [p.key, p.shape === "BOOLEAN" ? (p.nativeBooleanDefault ?? false) :
      p.shape === "NUMBER" ? (p.nativeNumberDefault ?? 0) :
      p.shape === "ENUM" ? (p.nativeStringDefault ?? p.options?.[0]?.value ?? "") : (p.nativeStringDefault ?? "")]))]);
  };
  return <div className="config-native-list" onKeyDown={event => {
    // Native field rows live inside the parent save form. Enter must not
    // accidentally submit the entire Transform while editing one cell.
    if (event.key === "Enter" && event.target instanceof HTMLElement &&
        event.target.matches("input, select") && !event.nativeEvent.isComposing) {
      event.preventDefault();
    }
  }}>
    <div className="config-native-list-filter">
      <input type="search" aria-label={title + " 搜索字段行"} placeholder="搜索字段名称或配置值"
        value={query} onChange={event => setQuery(event.target.value)} disabled={disabled} />
      {filtering && <button type="button" onClick={() => setQuery("")} disabled={disabled}>清除筛选</button>}
      <small aria-live="polite">{filtering ? `匹配 ${visibleRows.length} / ${rows.length} 行` : `共 ${rows.length} 行字段`}</small>
      {filtering && <small>筛选期间暂停行排序</small>}
    </div>
    <div className="config-native-list-scroll">
      <table ref={tableRef} aria-label={title + "原生字段列表"}>
        <thead><tr><th scope="col">序号</th>{properties.map(column =>
          <th scope="col" key={column.key}>{LABELS[column.key] ?? column.key}</th>)}<th scope="col">操作</th></tr></thead>
        <tbody>{visibleRows.map(({ entry, index }) => {
          const row = entry as Record<string, unknown>;
          return <tr key={index}><th scope="row">{index + 1}</th>{properties.map(column => {
            const val = row[column.key];
            const aria = title + " 第" + (index + 1) + "行 " + (LABELS[column.key] ?? column.key);
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
                    step={column.shape === "NUMBER" ? "any" : undefined}
                    value={val == null ? "" : String(val)}
                    onChange={e => {
                      const raw = e.target.value;
                      // Do not push NaN or Infinity into the authoritative native config.
                      const number = Number(raw);
                      if (column.shape === "NUMBER" && !Number.isFinite(number)) return;
                      edit(index, column, column.shape === "NUMBER" ? (raw === "" ? (column.nativeNumberDefault ?? 0) : number) : raw);
                    }} />
            }</td>;
          })}<td className="config-native-list-actions">
              <button type="button" aria-label={title + " 上移第" + (index + 1) + "行"}
                title="上移一行" disabled={disabled || filtering || index === 0} onClick={() => move(index, index - 1)}>↑</button>
              <button type="button" aria-label={title + " 下移第" + (index + 1) + "行"}
                title="下移一行" disabled={disabled || filtering || index === rows.length - 1} onClick={() => move(index, index + 1)}>↓</button>
              <button type="button" aria-label={title + " 复制第" + (index + 1) + "行"}
                title="复制此行并保留原生属性" disabled={disabled}
                onClick={() => duplicate(index)}>复制</button>
              <button type="button" aria-label={title + " 删除第" + (index + 1) + "行"}
                title="删除此行" disabled={disabled} onClick={() => onChange(rows.filter((_, i) => i !== index))}>删除</button>
            </td></tr>;
        })}</tbody>
      </table>
    </div>
    {rows.length === 0 && <small className="config-native-list-empty">当前没有字段行</small>}
    {rows.length > 0 && visibleRows.length === 0 && <small className="config-native-list-empty">没有匹配的字段行，可清除筛选查看全部</small>}
    <button type="button" className="config-native-list-add" disabled={disabled} onClick={() => { setQuery(""); add(); }}>
      添加字段行
    </button>
  </div>;
}
