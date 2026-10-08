/** AutomaticConfigDescriptor.Property JSON contract; frontend only. */
export type ConfigShape = "STRING" | "NUMBER" | "BOOLEAN" | "ENUM" | "OBJECT" | "LIST";
export type TextEditorHint = "NONE" | "SQL" | "SCRIPT" | "TEMPLATE";
export type ConfigPresentation = "FIELD" | "TABLE";
export interface ConfigOption { label: string; value: string; }
export interface ConfigLayoutHint { order: number; group: string | null; presentation: ConfigPresentation; }
export interface ConfigPropertyDescriptor {
  key: string; javaField: string; shape: ConfigShape; javaType: string;
  groupKey?: string | null; sensitive?: boolean; storeWithName?: boolean; storeWithCode?: boolean;
  defaultBoolean?: boolean; enumNameWhenNotFound?: string | null; elementJavaType?: string | null;
  children?: ConfigPropertyDescriptor[] | null; elementProperties?: ConfigPropertyDescriptor[] | null;
  options?: ConfigOption[] | null; textEditorHint?: TextEditorHint | null; layout?: ConfigLayoutHint | null;
}
export interface ConfigDescriptor { className: string; properties: ConfigPropertyDescriptor[]; }
export interface VisibleConfigProperty { property: ConfigPropertyDescriptor; path: string; group: string; }
export type ConfigEditorKind = "PASSWORD" | "BOOLEAN" | "NUMBER" | "ENUM" | "JSON" |
  "SQL" | "SCRIPT" | "TEMPLATE" | "TEXT";
export function configEditorKind(p: ConfigPropertyDescriptor): ConfigEditorKind {
  if (p.sensitive) return "PASSWORD";
  if (p.shape === "BOOLEAN") return "BOOLEAN";
  if (p.shape === "NUMBER") return "NUMBER";
  if (p.shape === "ENUM") return "ENUM";
  if (p.shape === "OBJECT" || p.shape === "LIST") return "JSON";
  if (p.shape === "STRING" &&
      (p.textEditorHint === "SQL" || p.textEditorHint === "SCRIPT" || p.textEditorHint === "TEMPLATE")) {
    return p.textEditorHint;
  }
  return "TEXT";
}
export function visibleConfigProperties(
  properties: ConfigPropertyDescriptor[], prefix = "", inheritedGroup = "General",
): VisibleConfigProperty[] {
  return properties.flatMap(p => {
    const path = prefix && !p.key.startsWith(prefix + ".") ? prefix + "." + p.key : p.key;
    const group = p.groupKey?.trim() || p.layout?.group?.trim() || inheritedGroup;
    if (p.shape === "OBJECT" && Array.isArray(p.children) && p.children.length) {
      return visibleConfigProperties(p.children, path, group);
    }
    return [{ property: p, path, group }];
  });
}
export function readConfigPath(source: Record<string, unknown>, path: string): unknown {
  return path.split(".").reduce<unknown>((value, key) =>
    value && typeof value === "object" && !Array.isArray(value)
      ? (value as Record<string, unknown>)[key] : undefined, source);
}
function writeConfigPath(source: Record<string, unknown>, path: string, value: unknown): Record<string, unknown> {
  const root = { ...source };
  const parts = path.split(".");
  let cursor = root;
  for (let i = 0; i < parts.length; i++) {
    const key = parts[i];
    if (i === parts.length - 1) cursor[key] = value;
    else {
      const old = cursor[key];
      const next = old && typeof old === "object" && !Array.isArray(old)
        ? { ...(old as Record<string, unknown>) } : {};
      cursor[key] = next;
      cursor = next;
    }
  }
  return root;
}
export function applyGenericConfigDraft(
  original: Record<string, unknown>, descriptor: ConfigDescriptor, draft: Record<string, unknown>,
): Record<string, unknown> {
  return visibleConfigProperties(descriptor.properties).reduce((next, { property, path }) => {
    if (!Object.prototype.hasOwnProperty.call(draft, path)) return next;
    const value = draft[path];
    if (value === undefined || (property.sensitive && value === "")) return next;
    return writeConfigPath(next, path, value);
  }, { ...original });
}
