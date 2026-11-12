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
