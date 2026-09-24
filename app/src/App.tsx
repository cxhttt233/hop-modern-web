import { useCallback, useEffect, useMemo, useState } from "react";
import { Background, Controls, MiniMap, ReactFlow, applyNodeChanges, type Edge, type Node, type NodeChange } from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";
import { DatabaseConnectionPanel, type DatabaseConnection } from "./editor/DatabaseConnectionPanel";
import { openPipelineGraph, type GraphSource } from "./editor/graphProvider";
import { TableInputConfigPanel, type TableInputConfig } from "./editor/TableInputConfigPanel";

const sample: HopGraphDocument = {
  id: "sample", name: "Pipeline", revision: "0",
  nodes: [
    { id: "input", name: "Table input", kind: "transform", pluginId: "TableInput", x: 80, y: 120 },
    { id: "select", name: "Select values", kind: "transform", pluginId: "SelectValues", x: 360, y: 120 },
    { id: "sort", name: "Sort rows", kind: "transform", pluginId: "SortRows", x: 640, y: 120 },
  ],
  edges: [
    { id: "input-select", source: "input", target: "select", enabled: true },
    { id: "select-sort", source: "select", target: "sort", enabled: true },
  ],
};

function graphNodes(document: HopGraphDocument): Node[] {
  return document.nodes.map((node) => ({ id: node.id, position: { x: node.x, y: node.y }, data: { label: node.name, pluginId: node.pluginId } }));
}

const initialConnections: DatabaseConnection[] = [
  { name: "Warehouse", rdbms: { hostname: "db.internal", databaseName: "warehouse", port: "5432", username: "hop" } },
  { name: "Reporting", rdbms: { hostname: "reports.internal", databaseName: "reporting", port: "5432", username: "hop" } },
];

export function App() {
  const [document, setDocument] = useState<HopGraphDocument>(sample);
  const [source, setSource] = useState<GraphSource>({ kind: "sample" });
  const [nodes, setNodes] = useState<Node[]>(() => graphNodes(sample));
  const [selectedId, setSelectedId] = useState<string>();
  const [configId, setConfigId] = useState<string>();
  const [metadataName, setMetadataName] = useState<string>();
  const [connections, setConnections] = useState(initialConnections);
  const [tableInputConfigs, setTableInputConfigs] = useState<Record<string, TableInputConfig>>({ input: { connection: "Warehouse", sql: "SELECT *\nFROM orders" } });
  const pipelinePath = import.meta.env.VITE_HOP_PIPELINE_PATH?.trim();

  useEffect(() => {
    if (!pipelinePath) return;
    const controller = new AbortController();
    setSource({ kind: "loading", path: pipelinePath });
    openPipelineGraph(pipelinePath, controller.signal).then((graph) => {
      setDocument(graph);
      setNodes(graphNodes(graph));
      setSelectedId(undefined);
      setConfigId(undefined);
      setSource({ kind: "server", path: pipelinePath });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setDocument(sample);
      setNodes(graphNodes(sample));
      setSource({ kind: "sample", reason: error instanceof Error ? error.message : "Pipeline open failed" });
    });
    return () => controller.abort();
  }, [pipelinePath]);

  const edges = useMemo<Edge[]>(() => document.edges.map((edge) => ({ id: edge.id, source: edge.source, target: edge.target })), [document]);
  const onNodesChange = useCallback((changes: NodeChange<Node>[]) => setNodes((current) => applyNodeChanges(changes, current)), []);
  const selected = nodes.find((node) => node.id === selectedId);
  const configured = nodes.find((node) => node.id === configId);
  const metadata = connections.find((connection) => connection.name === metadataName);
  const canConfigure = selected?.data.pluginId === "TableInput";
  const isRealGraph = source.kind === "server";
  const sourceLabel = source.kind === "server" ? "Server graph" : source.kind === "loading" ? "Loading real pipeline…" : "Development sample";

  return (
    <main className="shell">
      <header>
        <div><strong>Hop Modern Web</strong><span>{document.name}</span><span className="preview-badge" title={isRealGraph ? source.path : "Development fallback; configure VITE_HOP_PIPELINE_PATH to open a server-visible .hpl."}>{sourceLabel}</span></div>
        <div className="selection-actions">
          <small>{selected ? String(selected.data.label) : "Select a transform"}</small>
          <button type="button" onClick={() => setMetadataName(connections[0]?.name)}>Connections</button>
          {canConfigure && <button type="button" onClick={() => setConfigId(selected.id)}>Configure</button>}
        </div>
      </header>
      <section className="workspace">
        {source.kind !== "server" && <div className="preview-note">{source.kind === "loading" ? `Opening ${source.path}` : source.reason ? `Development sample fallback · ${source.reason}` : "Walking skeleton · set VITE_HOP_PIPELINE_PATH to load a real server-visible .hpl"}</div>}
        <ReactFlow nodes={nodes} edges={edges} onNodesChange={onNodesChange} onNodeClick={(_, node) => setSelectedId(node.id)} onNodeDoubleClick={(_, node) => node.data.pluginId === "TableInput" && setConfigId(node.id)} onPaneClick={() => setSelectedId(undefined)} fitView nodesDraggable nodesConnectable={false} panOnDrag zoomOnScroll zoomOnPinch>
          <MiniMap /><Controls /><Background />
        </ReactFlow>
        {configured?.data.pluginId === "TableInput" && (
          <TableInputConfigPanel
            transformName={String(configured.data.label)}
            value={tableInputConfigs[configured.id] ?? { connection: "", sql: "" }}
            connections={connections.map((connection) => ({ value: connection.name, label: connection.name }))}
            onClose={() => setConfigId(undefined)}
            onEditConnection={setMetadataName}
            onApply={(value) => {
              setTableInputConfigs((current) => ({ ...current, [configured.id]: value }));
              setConfigId(undefined);
            }}
          />
        )}
        {metadata && <DatabaseConnectionPanel value={metadata} onClose={() => setMetadataName(undefined)} onApply={(value) => { setConnections((current) => current.map((item) => item.name === metadata.name ? value : item)); setTableInputConfigs((current) => Object.fromEntries(Object.entries(current).map(([id, config]) => [id, config.connection === metadata.name ? { ...config, connection: value.name } : config]))); setMetadataName(undefined); }} />}
      </section>
    </main>
  );
}
