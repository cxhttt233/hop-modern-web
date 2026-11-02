import { useMemo, useRef, useState, type CSSProperties } from "react";

export type ConfigShape = "STRING" | "NUMBER" | "BOOLEAN" | "ENUM" | "OBJECT" | "LIST";
export interface ConfigPropertyDescriptor {
  key: string; javaField: string; shape: ConfigShape; javaType: string;
  groupKey?: string | null; sensitive?: boolean; storeWithName?: boolean; storeWithCode?: boolean;
}
export interface ConfigDescriptor { className: string; properties: ConfigPropertyDescriptor[]; }
export interface GenericConfigPanelProps {
  descriptor: ConfigDescriptor; config: Record<string, unknown>; disabled?: boolean;
  transformName?: string; transformId?: string;
  onCancel: () => void; onSave: (config: Record<string, unknown>) => void | Promise<void>;
}

const GROUP_LABELS: Record<string, string> = {
  General: "常规",
  Input: "输入",
  Output: "输出",
  Fields: "字段",
  Settings: "设置",
  Advanced: "高级",
};

const FIELD_LABELS: Record<string, string> = {
  connection: "连接",
  sql: "SQL",
  name: "名称",
  field: "字段",
  fields: "字段",
  type: "类型",
  format: "格式",
  length: "长度",
  precision: "精度",
  description: "说明",
  expression: "表达式",
  script: "脚本",
  filename: "文件",
  file: "文件",
  url: "URL",
  username: "用户名",
  password: "密码",
  database: "数据库",
  tablename: "表名",
  table: "表",
  schema: "模式",
  limit: "数量限制",
  encoding: "编码",
  separator: "分隔符",
};

const localLabel = (key: string) => {
  const leaf = key.split(".").at(-1) ?? key;
  const compact = leaf.replace(/[_-]/g, "").toLowerCase();
  return FIELD_LABELS[compact] ?? leaf.replace(/([a-z])([A-Z])/g, "$1 $2");
};

const read = (source: Record<string, unknown>, path: string): unknown =>
  path.split(".").reduce<unknown>((value, key) => value && typeof value === "object" ? (value as Record<string, unknown>)[key] : undefined, source);

function write(source: Record<string, unknown>, path: string, value: unknown) {
  const root = { ...source }; const parts = path.split("."); let cursor = root;
  parts.forEach((key, index) => {
    if (index === parts.length - 1) { cursor[key] = value; return; }
    const old = cursor[key];
    const next = old && typeof old === "object" && !Array.isArray(old) ? { ...(old as Record<string, unknown>) } : {};
    cursor[key] = next; cursor = next;
  });
  return root;
}

export function applyGenericConfigDraft(original: Record<string, unknown>, descriptor: ConfigDescriptor, draft: Record<string, unknown>) {
  return descriptor.properties.reduce((next, property) => {
    if (!(property.key in draft)) return next;
    const value = draft[property.key];
    if (property.sensitive && (value === "" || value === undefined)) return next;
    return write(next, property.key, value);
  }, { ...original });
}

export function GenericConfigPanel({ descriptor, config, transformName, transformId, disabled, onCancel, onSave }: GenericConfigPanelProps) {
  const [draft, setDraft] = useState<Record<string, unknown>>(() => Object.fromEntries(
    descriptor.properties.filter((p) => !p.sensitive).map((p) => [p.key, read(config, p.key)])));
  const [invalid, setInvalid] = useState<Record<string, boolean>>({});
  // Field navigation is presentation-only: never search or expose configuration values.
  const [fieldQuery, setFieldQuery] = useState("");
  // Only presentation state: collapsing a group never drops its native Hop draft.
  const [collapsedGroups, setCollapsedGroups] = useState<Record<string, boolean>>({});
  const configFormRef = useRef<HTMLFormElement>(null);
  const nextFieldMatch = useRef(0);
  // Resize only the visible editor: no graph/config write, persistence or schema change.
  const [panelWidth, setPanelWidth] = useState(600);
  const resizeStart = useRef<{ pointerId: number; x: number; width: number } | null>(null);
  const stopResize = (target: HTMLDivElement, pointerId: number) => {
    if (resizeStart.current?.pointerId !== pointerId) return;
    resizeStart.current = null;
    if (target.hasPointerCapture(pointerId)) target.releasePointerCapture(pointerId);
  };
  const groups = useMemo(() => {
    const result = new Map<string, ConfigPropertyDescriptor[]>();
    descriptor.properties.forEach((p) => { const group = p.groupKey?.trim() || "General"; result.set(group, [...(result.get(group) ?? []), p]); });
    return [...result.entries()];
  }, [descriptor]);
  const normalizedFieldQuery = fieldQuery.trim().toLocaleLowerCase();
  const visibleGroups = useMemo(() => groups.map(([group, properties]) => {
    const groupMatches = (GROUP_LABELS[group] ?? group).toLocaleLowerCase().includes(normalizedFieldQuery);
    return [group, properties.filter((property) => !normalizedFieldQuery || invalid[property.key] || groupMatches ||
      localLabel(property.key).toLocaleLowerCase().includes(normalizedFieldQuery) ||
      property.key.toLocaleLowerCase().includes(normalizedFieldQuery))] as const;
  }).filter(([, properties]) => properties.length > 0), [groups, normalizedFieldQuery, invalid]);
  const visibleFieldCount = visibleGroups.reduce((count, [, properties]) => count + properties.length, 0);
  const totalFieldCount = descriptor.properties.length;
  // Count actual unsaved field differences without revealing values, including secrets.
  // This is display-only: the existing native Hop save/cancel contract is unchanged.
  const changedFields = descriptor.properties.filter((property) => {
    if (!Object.prototype.hasOwnProperty.call(draft, property.key)) return false;
    const value = draft[property.key];
    if (property.sensitive && (value === "" || value === undefined)) return false;
    const original = read(config, property.key);
    return !(Object.is(value, original) || JSON.stringify(value) === JSON.stringify(original));
  });
  const changedFieldCount = changedFields.length;
  // Native TableInput gives a non-empty SQL file priority over inline SQL.
  // This notice describes the current unsaved draft; it never rewrites either source.
  const fileSqlSelected = descriptor.className.endsWith(".TableInputMeta") &&
    descriptor.properties.some((property) => property.key === "sql") &&
    descriptor.properties.some((property) => property.key === "sql_from_file") &&
    typeof draft.sql_from_file === "string" && draft.sql_from_file.trim().length > 0;
  const set = (key: string, value: unknown) => setDraft((old) => ({ ...old, [key]: value }));
  const label = (p: ConfigPropertyDescriptor) => localLabel(p.key);
  // Focus only visible descriptor-owned controls. Never inspect configuration values
  // or invoke a Hop edit/write request when navigating between search results.
  const focusNextMatchingField = () => {
    const keys = visibleGroups.flatMap(([, properties]) => properties.map((property) => property.key));
    if (!keys.length || disabled) return;
    const key = keys[nextFieldMatch.current % keys.length];
    const labels = configFormRef.current?.querySelectorAll<HTMLLabelElement>("label[data-config-key]");
    const target = Array.from(labels ?? []).find((element) => element.dataset.configKey === key);
    const control = target?.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>("input, textarea, select");
    if (!control || control.disabled) return;
    nextFieldMatch.current += 1;
    control.focus();
    control.scrollIntoView({ block: "nearest" });
  };

  const field = (p: ConfigPropertyDescriptor) => {
    const value = draft[p.key]; const id = `config-${p.javaField}`;
    if (p.shape === "BOOLEAN") return <input id={id} disabled={disabled} type="checkbox" checked={Boolean(value)} onChange={(e) => set(p.key, e.target.checked)} />;
    if (p.shape === "NUMBER") return <input id={id} disabled={disabled} type="number" value={value == null ? "" : String(value)} onChange={(e) => set(p.key, e.target.value === "" ? undefined : Number(e.target.value))} />;
    if (p.shape === "OBJECT" || p.shape === "LIST") {
      const text = typeof value === "string" ? value : JSON.stringify(value ?? (p.shape === "LIST" ? [] : {}), null, 2);
      return <textarea id={id} disabled={disabled} rows={p.shape === "LIST" ? 8 : 6} value={text} onChange={(e) => {
        const raw = e.target.value;
        try {
          const parsed: unknown = JSON.parse(raw);
          const valid = p.shape === "LIST" ? Array.isArray(parsed) : !!parsed && typeof parsed === "object" && !Array.isArray(parsed);
          if (!valid) throw new Error("shape");
          set(p.key, parsed); setInvalid((old) => ({ ...old, [p.key]: false }));
        } catch { set(p.key, raw); setInvalid((old) => ({ ...old, [p.key]: true })); }
      }} />;
    }
    const long = /sql|query|script|description|expression/i.test(p.key);
    if (long) return <textarea id={id} disabled={disabled} rows={6} value={value == null ? "" : String(value)} onChange={(e) => set(p.key, e.target.value)} />;
    return <input id={id} disabled={disabled} type={p.sensitive ? "password" : "text"} value={value == null ? "" : String(value)}
      placeholder={p.sensitive ? "留空以保留现有值" : undefined} onChange={(e) => set(p.key, e.target.value)} />;
  };

  return <form ref={configFormRef} className="config-panel generic-config" style={{ "--config-panel-width": `${panelWidth}px` } as CSSProperties} onSubmit={(e) => { e.preventDefault(); if (!Object.values(invalid).some(Boolean)) void onSave(applyGenericConfigDraft(config, descriptor, draft)); }}>
    <div className="config-resize-grip" role="separator" aria-label="调整配置面板宽度" aria-orientation="vertical"
      aria-valuemin={480} aria-valuemax={800} aria-valuenow={panelWidth} tabIndex={0}
      onPointerDown={(event) => {
        if (event.button !== 0) return;
        event.preventDefault();
        resizeStart.current = { pointerId: event.pointerId, x: event.clientX, width: panelWidth };
        event.currentTarget.setPointerCapture(event.pointerId);
      }}
      onPointerMove={(event) => {
        const start = resizeStart.current;
        if (start?.pointerId === event.pointerId) {
          setPanelWidth(Math.max(480, Math.min(800, start.width + start.x - event.clientX)));
        }
      }}
      onPointerUp={(event) => stopResize(event.currentTarget, event.pointerId)}
      onPointerCancel={(event) => stopResize(event.currentTarget, event.pointerId)}
      onLostPointerCapture={() => { resizeStart.current = null; }}
      onKeyDown={(event) => {
        if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
          event.preventDefault();
          setPanelWidth((width) => Math.max(480, Math.min(800, width + (event.key === "ArrowLeft" ? 24 : -24))));
        }
      }} />
    <header>
      <strong>转换配置{transformName ? `：${transformName}` : ""}</strong>
      <details className="config-developer-details">
        <summary>开发者信息</summary>
        <div className="config-developer-content">
          {transformId && <p><span>节点 ID</span><code>{transformId}</code></p>}
          <p><span>Java 元数据类</span><code>{descriptor.className}</code></p>
          <details className="config-property-keys">
            <summary>原始字段键（{descriptor.properties.length}）</summary>
            <ul>{descriptor.properties.map((property, index) =>
              <li key={`${property.key}-${index}`}><span>{label(property)}</span><code>{property.key}</code></li>)}</ul>
          </details>
        </div>
      </details>
    </header>
    <div className="config-field-search">
      <input type="search" aria-label="查找配置字段" placeholder="查找字段名称或分组" value={fieldQuery}
        onChange={(event) => { nextFieldMatch.current = 0; setFieldQuery(event.target.value); }}
        onKeyDown={(event) => { if (event.key === "Enter") { event.preventDefault(); focusNextMatchingField(); } }} />
      {fieldQuery && <button type="button" aria-label="定位下一匹配字段" disabled={disabled || visibleFieldCount === 0}
        onClick={focusNextMatchingField}>定位下一项</button>}
      {fieldQuery && <button type="button" onClick={() => { nextFieldMatch.current = 0; setFieldQuery(""); }} aria-label="清除字段查找">清除</button>}
      <small aria-live="polite">显示 {visibleFieldCount} / {totalFieldCount} 项</small>
    </div>
    {fileSqlSelected && <p className="config-sql-source-notice" role="status">SQL 来源提示：当前草稿指定了 SQL 文件；运行时文件内容优先，内联 SQL 会保留但不会执行。</p>}
    {visibleGroups.length === 0 && <p className="config-search-empty" role="status">没有匹配的配置字段，请更换关键词。</p>}
    {visibleGroups.map(([group, properties], index) => {
      // Search always reveals matching controls; clearing it restores each user's
      // collapse choice. Hidden inputs stay mounted so unsaved edits are preserved.
      const expanded = Boolean(normalizedFieldQuery) || !collapsedGroups[group];
      const groupLabel = GROUP_LABELS[group] ?? group;
      const groupId = `config-group-${index}`;
      return <fieldset key={group} className="config-field-group">
        <legend><button type="button" className="config-group-toggle" aria-label={`${groupLabel}配置分组`}
          aria-expanded={expanded} aria-controls={groupId} disabled={Boolean(normalizedFieldQuery)}
          onClick={() => setCollapsedGroups((current) => ({ ...current, [group]: expanded }))}>
          <span aria-hidden="true">{expanded ? "▾" : "▸"}</span>{groupLabel}<small>{properties.length} 项</small>
        </button></legend>
        <div id={groupId} className="config-group-fields" hidden={!expanded}>{properties.map((p) =>
          <label key={p.key} data-config-key={p.key} htmlFor={`config-${p.javaField}`} title={p.key}><span>{label(p)}</span>{field(p)}{invalid[p.key] && <small role="alert">请输入有效的 {p.shape === "LIST" ? "列表" : "对象"} JSON</small>}</label>)}</div>
      </fieldset>;
    })}
    <footer>{changedFieldCount > 0 && <div className="config-change-review">
      <small className="config-change-count" role="status" aria-live="polite">已修改 {changedFieldCount} 项（未保存）</small>
      <details>
        <summary>查看未保存字段</summary>
        <ul aria-label="未保存字段清单">{changedFields.map((property) =>
          <li key={property.key}>{label(property)}</li>)}</ul>
      </details>
    </div>}<button type="button" aria-label="取消配置（Cancel）" onClick={onCancel}>取消</button><button type="submit" aria-label="保存配置（Save）" disabled={disabled || Object.values(invalid).some(Boolean)}>保存</button></footer>
  </form>;
}
