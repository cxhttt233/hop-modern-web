import { useEffect, useState } from "react";
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

/**
 * Chinese IME text remains local until composition is finished. This prevents
 * intermediate pinyin from being persisted as Hop native configuration.
 */
function NativeTextCell({ value, aria, disabled, onCommit }: {
  value: string; aria: string; disabled?: boolean; onCommit: (next: string) => void;
}) {
  const [draft, setDraft] = useState(value);
  const [editing, setEditing] = useState(false);
  const [composing, setComposing] = useState(false);
  useEffect(() => { if (!editing) setDraft(value); }, [value, editing]);
  const commit = () => {
    if (composing) return;
    setEditing(false);
    if (draft !== value) onCommit(draft);
  };
  return <input type="text" aria-label={aria} disabled={disabled} value={draft}
    onFocus={() => setEditing(true)}
    onChange={e => setDraft(e.target.value)}
    onCompositionStart={() => setComposing(true)}
    onCompositionEnd={e => { setComposing(false); setDraft(e.currentTarget.value); }}
    onBlur={() => { if (!composing) commit(); }}
    onKeyDown={e => {
      if (e.key === "Escape" && !composing) {
        setDraft(value); setEditing(false); e.currentTarget.blur();
      } else if (e.key === "Enter" && !composing && !e.nativeEvent.isComposing) {
        e.currentTarget.blur();
      }
    }} />;
}

/**
 * Keep incomplete numeric input local until it can be committed. A controlled
 * number input that writes 0 on every empty keystroke prevents entering
 * negatives/decimals and can silently change an authoritative Hop value.
 */
function NativeNumberCell({ value, fallback, disabled, aria, onCommit }: {
  value: unknown; fallback: number; disabled?: boolean; aria: string;
  onCommit: (value: number) => void;
}) {
  const canonical = value == null ? "" : String(value);
  const [draft, setDraft] = useState(canonical);
  useEffect(() => setDraft(canonical), [canonical]);
  const [cancelled, setCancelled] = useState(false);
  const commit = () => {
    if (cancelled) { setCancelled(false); return; }
    const normalized = draft.trim();
    if (normalized === "") {
      if (value !== fallback) onCommit(fallback);
      setDraft(String(fallback));
      return;
    }
    const parsed = Number(normalized);
    if (Number.isFinite(parsed)) {
      if (parsed !== value) onCommit(parsed);
      setDraft(String(parsed));
    } else {
      setDraft(canonical);
    }
  };
  return <input type="number" step="any" aria-label={aria} disabled={disabled}
    value={draft} onChange={event => setDraft(event.target.value)}
    onFocus={() => setCancelled(false)}
    onBlur={commit}
    onKeyDown={event => {
      if (event.key === "Enter") event.currentTarget.blur();
      if (event.key === "Escape") {
        setCancelled(true);
        setDraft(canonical);
        event.currentTarget.blur();
      }
    }} />;
}

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
  // Duplicate the entire native row, including properties not shown in the editor.
  // This lets users add similar fields without silently dropping Hop metadata.
  const duplicate = (index: number) => {
    const row = rows[index] as Record<string, unknown>;
    onChange([...rows.slice(0, index + 1), { ...row }, ...rows.slice(index + 1)]);
  };
  const add = () => onChange([...rows, Object.fromEntries(properties.map(p =>
    [p.key, p.shape === "BOOLEAN" ? (p.nativeBooleanDefault ?? false) :
      p.shape === "NUMBER" ? (p.nativeNumberDefault ?? 0) :
      p.shape === "ENUM" ? (p.nativeStringDefault ?? p.options?.[0]?.value ?? "") : (p.nativeStringDefault ?? "")]))]);
  return <div className="config-native-list">
    <div className="config-native-list-scroll">
      <table aria-label={title + "原生字段列表"}>
        <thead><tr><th scope="col">序号</th>{properties.map(column =>
          <th scope="col" key={column.key}>{LABELS[column.key] ?? column.key}</th>)}<th scope="col">操作</th></tr></thead>
        <tbody>{rows.map((entry, index) => {
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
                    {val != null && !options.some(opt => opt.value === String(val)) &&
                      <option value={String(val)}>当前原生值：{String(val)}（未列入可选项）</option>}
                    {options.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                  </select>
                : column.shape === "STRING"
                ? <NativeTextCell aria={aria} disabled={disabled}
                    value={val == null ? "" : String(val)}
                    onCommit={next => edit(index, column, next)} />
                : <NativeNumberCell aria={aria} disabled={disabled}
                    value={val} fallback={column.nativeNumberDefault ?? 0}
                    onCommit={next => edit(index, column, next)} />
            }</td>;
          })}<td className="config-native-list-actions">
              <button type="button" aria-label={title + " 上移第" + (index + 1) + "行"}
                title="上移一行" disabled={disabled || index === 0} onClick={() => move(index, index - 1)}>↑</button>
              <button type="button" aria-label={title + " 下移第" + (index + 1) + "行"}
                title="下移一行" disabled={disabled || index === rows.length - 1} onClick={() => move(index, index + 1)}>↓</button>
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
    {rows.length > 0 && <small aria-live="polite">共 {rows.length} 行字段</small>}
    <button type="button" className="config-native-list-add" disabled={disabled} onClick={add}>
      添加字段行
    </button>
  </div>;
}