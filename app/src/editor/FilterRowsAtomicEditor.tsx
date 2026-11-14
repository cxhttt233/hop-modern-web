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


// Native Hop Condition trees use the first child's operator '-' and subsequent
// children 'AND' / 'OR'. Only expose a flat, recognized tree; nested or unknown
// native structures remain in the original JSON editor without normalization.
export function canEditCompositeFilterRows(className: string, property: ConfigPropertyDescriptor, value: unknown): value is Record<string, unknown> {
  if (className !== "org.apache.hop.pipeline.transforms.filterrows.FilterRowsMeta" ||
      property.key !== "compare" || property.shape !== "OBJECT" || !record(value)) return false;
  const root = value.condition;
  if (!record(root) || root.operator !== "-" || !record(root.conditions)) return false;
  const children = root.conditions.condition;
  if (!Array.isArray(children) || children.length < 2) return false;
  return children.every((child, index) => record(child) &&
    (index !== 0 || child.operator === "-") &&
    (index === 0 || child.operator === "AND" || child.operator === "OR") &&
    FUNCTIONS.some(([code]) => code === child.function) &&
    typeof child.negated === "boolean" && typeof child.leftvalue === "string" &&
    (child.conditions == null || (record(child.conditions) &&
      (child.conditions.condition == null || (Array.isArray(child.conditions.condition) && child.conditions.condition.length === 0)))) &&
    (child.value == null ? (child.rightvalue == null || typeof child.rightvalue === "string") :
      record(child.value) && child.value.type === "Number" && typeof child.value.text === "string" && child.rightvalue == null));
}

export function FilterRowsCompositeEditor({ value, disabled, onChange }: {
  value: Record<string, unknown>; disabled?: boolean;
  onChange: (next: Record<string, unknown>) => void;
}) {
  const root = value.condition as Record<string, unknown>;
  const container = root.conditions as Record<string, unknown>;
  const children = container.condition as Record<string, unknown>[];
  const change = (index: number, child: Record<string, unknown>) => {
    const next = children.map((item, i) => i === index ? child : item);
    onChange({ ...value, condition: { ...root, conditions: { ...container, condition: next } } });
  };
  return <div className="config-native-filter-condition" role="group" aria-label="过滤复合条件编辑">
    <p>Apache Hop 原生条件树 · 按顺序组合 AND / OR。未知嵌套条件请使用高级 JSON。</p>
    {children.map((child, index) => <fieldset key={index}>
      <legend>条件 {index + 1}</legend>
      {index > 0 && <label>逻辑连接
        <select aria-label={`条件${index + 1}逻辑连接`} disabled={disabled} value={String(child.operator)}
          onChange={e => change(index, { ...child, operator: e.target.value })}>
          <option value="AND">并且 AND</option><option value="OR">或者 OR</option>
        </select></label>}
      <label>左侧字段<input aria-label={`条件${index + 1}左侧字段`} disabled={disabled} value={String(child.leftvalue)}
        onChange={e => change(index, { ...child, leftvalue: e.target.value })}/></label>
      <label>比较方式<select aria-label={`条件${index + 1}比较方式`} disabled={disabled} value={String(child.function)}
        onChange={e => change(index, { ...child, function: e.target.value })}>
        {FUNCTIONS.map(([code, label]) => <option key={code} value={code}>{label}（{code}）</option>)}
      </select></label>
      {record(child.value) ? <label>数字常量<input aria-label={`条件${index + 1}数字常量`} disabled={disabled}
        value={String(child.value.text)} onChange={e => change(index, { ...child, value: { ...(child.value as Record<string, unknown>), text: e.target.value } })}/></label>
        : <label>右侧字段<input aria-label={`条件${index + 1}右侧字段`} disabled={disabled}
          value={String(child.rightvalue ?? "")} onChange={e => change(index, { ...child, rightvalue: e.target.value })}/></label>}
      <label><input aria-label={`条件${index + 1}取反`} type="checkbox" disabled={disabled} checked={child.negated === true}
        onChange={e => change(index, { ...child, negated: e.target.checked })}/>取反</label>
    </fieldset>)}
  </div>;
}