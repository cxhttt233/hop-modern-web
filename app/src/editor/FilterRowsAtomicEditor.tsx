import type { ConfigPropertyDescriptor } from "./configDescriptor";

// Hop FilterRowsMeta.FRCompare.condition is the native Hop Condition object.
// Limit the structured editor to atomic field-to-field comparisons; complex
// trees and literal-valued conditions still use the original JSON editor.
const FUNCTIONS = [
  ["=", "等于"], ["<>", "不等于"], ["<", "小于"], ["<=", "小于或等于"],
  [">", "大于"], [">=", "大于或等于"],
  ["IS NULL", "为空"], ["IS NOT NULL", "不为空"],
] as const;

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function canEditAtomicFilterRows(
  className: string,
  property: ConfigPropertyDescriptor,
  value: unknown,
): value is Record<string, unknown> {
  if (className !== "org.apache.hop.pipeline.transforms.filterrows.FilterRowsMeta" ||
    property.key !== "compare" || property.shape !== "OBJECT" || !record(value)) return false;
  const condition = value.condition;
  if (!record(condition)) return false;
  const nested = record(condition.conditions) ? condition.conditions.condition : undefined;
  if (nested != null && (!Array.isArray(nested) || nested.length !== 0)) return false;
  // Do not reinterpret a populated native CValue literal as a field name.
  if (condition.value != null) return false;
  if (condition.operator !== "-" || !FUNCTIONS.some(([code]) => code === condition.function)) return false;
  return (condition.leftvalue == null || typeof condition.leftvalue === "string") &&
    (condition.rightvalue == null || typeof condition.rightvalue === "string") &&
    typeof condition.negated === "boolean";
}

export function FilterRowsAtomicEditor({ value, disabled, onChange }: {
  value: Record<string, unknown>;
  disabled?: boolean;
  onChange: (next: Record<string, unknown>) => void;
}) {
  const condition = value.condition as Record<string, unknown>;
  const fn = String(condition.function ?? "=");
  const singleInput = fn === "IS NULL" || fn === "IS NOT NULL";
  const edit = (key: string, next: unknown) => onChange({
    ...value,
    condition: { ...condition, [key]: next },
  });
  return <div className="config-native-filter-condition" role="group" aria-label="过滤条件编辑">
    <p>单条件比较 · 使用 Apache Hop 原生字段名；下方的“成立流向 / 不成立流向”指定分支目标。</p>
    <label>左侧字段
      <input aria-label="过滤左侧字段" type="text" disabled={disabled}
        value={String(condition.leftvalue ?? "")}
        onChange={event => edit("leftvalue", event.target.value)} />
    </label>
    <label>比较方式
      <select aria-label="过滤比较方式" disabled={disabled} value={fn}
        onChange={event => edit("function", event.target.value)}>
        {FUNCTIONS.map(([code, label]) =>
          <option key={code} value={code}>{label}（{code}）</option>)}
      </select>
    </label>
    <label>右侧字段
      <input aria-label="过滤右侧字段" type="text" disabled={disabled || singleInput}
        value={String(condition.rightvalue ?? "")}
        onChange={event => edit("rightvalue", event.target.value)} />
    </label>
    <label className="config-native-filter-negated">
      <input aria-label="过滤条件取反" type="checkbox" disabled={disabled}
        checked={condition.negated === true}
        onChange={event => edit("negated", event.target.checked)} />
      对条件结果取反
    </label>
    <small>复杂的 AND/OR 条件树和常量值比较仍需原始 JSON 编辑；此表单不会展开或重写那些结构。</small>
  </div>;
}
