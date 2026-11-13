export type HopNodeKind = "transform" | "note";

export interface HopGraphNode {
  id: string;
  name: string;
  kind: HopNodeKind;
  pluginId?: string;
  pluginType?: string;
  x: number;
  y: number;
  enabled?: boolean;
}

export interface HopGraphEdge {
  id: string;
  source: string;
  target: string;
  enabled: boolean;
  hopType?: string;
}

export interface HopGraphDocument {
  id: string;
  name: string;
  revision: string;
  nodes: HopGraphNode[];
  edges: HopGraphEdge[];
}

export type HopConfigControl =
  | "VariableInput"
  | "EditableTable"
  | "MetadataPicker"
  | "MonacoEditor"
  | "VfsPicker";

export type HopConfigValueType =
  | "string"
  | "boolean"
  | "integer"
  | "number"
  | "string[]"
  | "object"
  | "object[]";

export interface HopConfigOption {
  value: string;
  label: string;
}

export interface HopEditableTableColumn {
  key: string;
  label: string;
  valueType?: HopConfigValueType;
  variable?: boolean;
  options?: HopConfigOption[];
}

export interface HopConfigField {
  /** Stable property path used by the server adapter to read/write Hop Meta. */
  key: string;
  label: string;
  control: HopConfigControl;
  valueType: HopConfigValueType;
  required?: boolean;
  readOnly?: boolean;
  description?: string;

  /** MetadataPicker: Hop metadata interface/type and named-reference semantics. */
  metadataType?: string;
  storeWithName?: boolean;

  /** VariableInput: preserve Hop variable expressions; resolution remains server-side. */
  variable?: boolean;

  /** Sensitive values are transport values, never UI display labels or resolved plaintext. */
  sensitive?: boolean;

  /** MonacoEditor language, for example "sql". */
  language?: string;

  /** EditableTable column contract. */
  columns?: HopEditableTableColumn[];

  /** VfsPicker selection constraints. */
  vfsMode?: "file" | "directory" | "file-or-directory";
  allowMultiple?: boolean;

  options?: HopConfigOption[];
}

export interface HopConfigGroup {
  key: string;
  label: string;
  fields: HopConfigField[];
}

export interface HopConfigTab {
  key: string;
  label: string;
  groups: HopConfigGroup[];
}

export interface HopConfigSchema {
  pluginId: string;
  pluginType: "transform" | "action" | "metadata";
  title: string;
  /** T0 is annotation-derived, T1 declarative layout, T2 purpose-built React UI. */
  tier: "T0" | "T1" | "T2";
  fields: HopConfigField[];
  tabs?: HopConfigTab[];
}

export interface HopConfigDocument {
  pluginId: string;
  pluginType: HopConfigSchema["pluginType"];
  /** Optimistic concurrency token supplied by the server adapter. */
  revision: string;
  values: Record<string, unknown>;
}

export interface HopNativeWireListContract {
  descriptorPath: string;
  listPointer: string;
  itemPointerTemplate: string;
  itemWidgetHint?: string | null;
  sourceTransformPointer?: string | null;
}

export interface HopNativeWireDescriptor {
  wireLists?: HopNativeWireListContract[] | null;
}

/**
 * Resolve only paths attested by the producer. Never construct an unknown
 * list path or accept a malformed template; that could silently overwrite a
 * different same-named nested Hop field during config/write.
 */
export function resolveNativeWireListItem(
  descriptor: HopNativeWireDescriptor,
  descriptorPath: string,
  index: number,
): { listPointer: string; itemPointer: string } | null {
  if (!Number.isSafeInteger(index) || index < 0) {
    throw new RangeError("Native LIST index must be a non-negative safe integer");
  }
  const matches = descriptor.wireLists?.filter(entry => entry.descriptorPath === descriptorPath) ?? [];
  if (matches.length === 0) return null;
  if (matches.length !== 1) {
    throw new Error("Ambiguous native LIST descriptor path: " + descriptorPath);
  }
  const [contract] = matches;
  // A producer must never assign one wire container to two distinct
  // descriptor paths: otherwise editing either table silently edits the other.
  // This is a cross-entry invariant, not just a duplicate descriptor-path check.
  // Native LIST write containers must be disjoint. Even a strict ancestor
  // path aliases its nested descendant and may overwrite its sibling data.
  if (descriptor.wireLists?.some(entry =>
      entry !== contract && (entry.listPointer === contract.listPointer ||
        entry.listPointer.startsWith(contract.listPointer + "/") ||
        contract.listPointer.startsWith(entry.listPointer + "/")))) {
    throw new Error("Aliased native LIST wire pointer: " + contract.listPointer);
  }
  if (!contract.listPointer.startsWith("/") ||
      contract.listPointer.includes("{index}") ||
      !/^\/(?:[^~]|~[01])*$/.test(contract.listPointer) ||
      contract.itemPointerTemplate !== contract.listPointer + "/{index}") {
    throw new Error("Invalid producer-owned native LIST pointer template");
  }
  return {
    listPointer: contract.listPointer,
    itemPointer: contract.listPointer + "/" + String(index),
  };
}

/** Read a native LIST only through a producer-attested canonical JSON Pointer. */
export function readNativeWireList(
  source: Record<string, unknown>, descriptor: HopNativeWireDescriptor, descriptorPath: string,
): unknown[] | null {
  const address = resolveNativeWireListItem(descriptor, descriptorPath, 0);
  if (!address) return null;
  const parts = nativeListPointerParts(address.listPointer);
  let current: unknown = source;
  for (const part of parts) {
    if (current === undefined || current === null) return [];
    if (!isPlainRecord(current)) throw new TypeError("Native LIST parent is not an object");
    current = current[part];
  }
  if (current === undefined || current === null) return [];
  if (!Array.isArray(current)) throw new TypeError("Native LIST wire location is not an array");
  return current;
}

/** Copy-on-write LIST update that preserves unknown siblings and native secrets. */
export function writeNativeWireList(
  source: Record<string, unknown>, descriptor: HopNativeWireDescriptor,
  descriptorPath: string, rows: unknown[],
): Record<string, unknown> {
  const address = resolveNativeWireListItem(descriptor, descriptorPath, 0);
  if (!address) throw new Error("Unattested native LIST path: " + descriptorPath);
  if (!Array.isArray(rows)) throw new TypeError("Native LIST update requires an array");
  const parts = nativeListPointerParts(address.listPointer);
  const root = { ...source };
  let original: unknown = source;
  let result: Record<string, unknown> = root;
  for (const part of parts.slice(0, -1)) {
    if (!isPlainRecord(original)) throw new TypeError("Native LIST parent is not an object");
    const previous: unknown = original[part];
    if (previous !== undefined && previous !== null && !isPlainRecord(previous)) {
      throw new TypeError("Native LIST path would overwrite a non-object parent");
    }
    const child: Record<string, unknown> = isPlainRecord(previous) ? { ...previous } : {};
    result[part] = child;
    result = child;
    original = previous ?? {};
  }
  const leaf = parts[parts.length - 1];
  const oldLeaf = isPlainRecord(original) ? original[leaf] : undefined;
  if (oldLeaf !== undefined && oldLeaf !== null && !Array.isArray(oldLeaf)) {
    throw new TypeError("Native LIST path would overwrite a non-list value");
  }
  result[leaf] = rows;
  return root;
}

function nativeListPointerParts(pointer: string): string[] {
  const parts = pointer.slice(1).split("/").map(part =>
    part.replace(/~1/g, "/").replace(/~0/g, "~"));
  // Defensive even if an invalid producer publishes a prototype-polluting key.
  if (parts.some(part => !part || part === "__proto__" || part === "constructor" ||
      part === "prototype")) throw new Error("Unsafe native LIST JSON Pointer");
  return parts;
}
function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export interface HopMetadataSummary {
  name: string;
  metadataType: string;
}

export {
  databaseConnectionConfigSchema,
  findConfigSchema,
  listConfigSchemas,
  selectValuesConfigSchema,
  sortRowsConfigSchema,
  tableInputConfigSchema,
} from "./configSchemas.js";
