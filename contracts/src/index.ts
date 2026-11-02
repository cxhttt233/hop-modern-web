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
  /** Native Hop enum/type options may use STRING names or NUMBER codes. */
  value: string | number;
  label: string;
}

export interface HopEditableTableColumn {
  key: string;
  label: string;
  valueType?: HopConfigValueType;
  variable?: boolean;
  /** Native Hop value-meta picker; the descriptor supplies registered options and wire types. */
  pickerHint?: "VALUE_META_TYPE_PICKER" | "VALUE_META_CODE_PICKER";
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
  /** Conditional editor activation only; inactive native values must be retained on save. */
  activation?: { flagKey: string; activeWhen: boolean };

  /** MetadataPicker: Hop metadata interface/type and named-reference semantics. */
  metadataType?: string;
  storeWithName?: boolean;

  /** Optional producer-owned native picker semantics; unsupported UIs may use text fallback. */
  pickerHint?: "INFO_STREAM_TRANSFORM_PICKER" | "LOCAL_DIRECTORY_PICKER";

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
