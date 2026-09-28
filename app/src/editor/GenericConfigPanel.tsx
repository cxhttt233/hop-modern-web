import { useMemo, useState } from "react";

export type ConfigShape = "STRING" | "NUMBER" | "BOOLEAN" | "ENUM" | "OBJECT" | "LIST";
export interface ConfigPropertyDescriptor {
  key: string; javaField: string; shape: ConfigShape; javaType: string;
  groupKey?: string | null; sensitive?: boolean; storeWithName?: boolean; storeWithCode?: boolean;
}
export interface ConfigDescriptor { className: string; properties: ConfigPropertyDescriptor[]; }
export interface GenericConfigPanelProps {
  descriptor: ConfigDescriptor; config: Record<string, unknown>; disabled?: boolean;
  onCancel: () => void; onSave: (config: Record<string, unknown>) => void | Promise<void>;
}

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

export function GenericConfigPanel({ descriptor, config, disabled, onCancel, onSave }: GenericConfigPanelProps) {
  const [draft, setDraft] = useState<Record<string, unknown>>(() => Object.fromEntries(
    descriptor.properties.filter((p) => !p.sensitive).map((p) => [p.key, read(config, p.key)])));
  const [invalid, setInvalid] = useState<Record<string, boolean>>({});
  const groups = useMemo(() => {
    const result = new Map<string, ConfigPropertyDescriptor[]>();
    descriptor.properties.forEach((p) => { const group = p.groupKey?.trim() || "General"; result.set(group, [...(result.get(group) ?? []), p]); });
    return [...result.entries()];
  }, [descriptor]);
  const set = (key: string, value: unknown) => setDraft((old) => ({ ...old, [key]: value }));
  const label = (p: ConfigPropertyDescriptor) => p.key.split(".").at(-1)?.replace(/([a-z])([A-Z])/g, "$1 $2") ?? p.key;

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
      placeholder={p.sensitive ? "Leave blank to preserve existing value" : undefined} onChange={(e) => set(p.key, e.target.value)} />;
  };

  return <form className="config-panel generic-config" onSubmit={(e) => { e.preventDefault(); if (!Object.values(invalid).some(Boolean)) void onSave(applyGenericConfigDraft(config, descriptor, draft)); }}>
    <header><strong>Configuration</strong><small>{descriptor.className}</small></header>
    {groups.map(([group, properties]) => <fieldset key={group}><legend>{group}</legend>{properties.map((p) =>
      <label key={p.key} htmlFor={`config-${p.javaField}`}><span>{label(p)}</span>{field(p)}{invalid[p.key] && <small role="alert">Enter valid {p.shape.toLowerCase()} JSON</small>}</label>)}</fieldset>)}
    <footer><button type="button" onClick={onCancel}>Cancel</button><button type="submit" disabled={disabled || Object.values(invalid).some(Boolean)}>Save</button></footer>
  </form>;
}
