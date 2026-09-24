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

export interface HopConfigField {
  key: string;
  label: string;
  kind: "text" | "password" | "metadata" | "sql" | "table" | "file";
  metadataType?: string;
  sensitive?: boolean;
}

export interface HopConfigSchema {
  pluginId: string;
  fields: HopConfigField[];
}
