import { useCallback, useEffect, useMemo, useState } from "react";
import { Background, Controls, MiniMap, ReactFlow, applyNodeChanges, type Edge, type Node, type NodeChange } from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";
import { DatabaseConnectionPanel, type DatabaseConnection } from "./editor/DatabaseConnectionPanel";
import { movePipelineTransforms, openPipelineGraph, readPipelineTransformConfig, writePipelineTransformConfig, type GraphSource, type TransformConfigDocument } from "./editor/graphProvider";
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
  const initialPipelinePath = import.meta.env.VITE_HOP_PIPELINE_PATH?.trim() ?? "";
  const [document, setDocument] = useState<HopGraphDocument>(sample);
  const [source, setSource] = useState<GraphSource>({ kind: "sample" });
  const [nodes, setNodes] = useState<Node[]>(() => graphNodes(sample));
  const [selectedId, setSelectedId] = useState<string>();
  const [configId, setConfigId] = useState<string>();
  const [serverConfig, setServerConfig] = useState<TransformConfigDocument>();
  const [configLoading, setConfigLoading] = useState(false);
  const [configError, setConfigError] = useState<string>();
  const [metadataName, setMetadataName] = useState<string>();
  const [connections, setConnections] = useState(initialConnections);
  const [tableInputConfigs, setTableInputConfigs] = useState<Record<string, TableInputConfig>>({ input: { connection: "Warehouse", sql: "SELECT *\nFROM orders" } });
  const [pipelinePath, setPipelinePath] = useState(initialPipelinePath);
  const [pipelinePathDraft, setPipelinePathDraft] = useState(initialPipelinePath);

  useEffect(() => {
    if (!pipelinePath) return;
    const controller = new AbortController();
    setSource({ kind: "loading", path: pipelinePath });
    openPipelineGraph(pipelinePath, controller.signal).then((graph) => {
      setDocument(graph);
      setNodes(graphNodes(graph));
      setSelectedId(undefined);
      setConfigId(undefined);
      setServerConfig(undefined);
      setConfigError(undefined);
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
  const onNodeDragStop = useCallback((_: unknown, node: Node) => {
    if (source.kind !== "server") return;
    const authoritative = document.nodes.find((candidate) => candidate.id === node.id && candidate.kind === "transform");
    if (!authoritative) {
      setNodes(graphNodes(document));
      return;
    }
    const dx = Math.round(node.position.x - authoritative.x);
    const dy = Math.round(node.position.y - authoritative.y);
    if (dx === 0 && dy === 0) {
      setNodes(graphNodes(document));
      return;
    }
    movePipelineTransforms(document.id, [node.id], dx, dy).then((graph) => {
      setDocument(graph);
      setNodes(graphNodes(graph));
    }).catch(() => {
      setNodes(graphNodes(document));
    });
  }, [document, source]);
  const openTransformConfig = useCallback((nodeId: string) => {
    if (source.kind !== "server") {
      setServerConfig(undefined);
      setConfigError(undefined);
      setConfigId(nodeId);
      return;
    }
    setConfigLoading(true);
    setConfigError(undefined);
    setServerConfig(undefined);
    readPipelineTransformConfig(document.id, nodeId).then((result) => {
      setServerConfig(result);
      setConfigId(nodeId);
    }).catch((error: unknown) => {
      setConfigId(undefined);
      setConfigError(error instanceof Error ? error.message : "Transform config read failed");
    }).finally(() => setConfigLoading(false));
  }, [document.id, source]);

  const applyTransformConfig = useCallback((nodeId: string, value: TableInputConfig) => {
    if (source.kind !== "server") {
      setTableInputConfigs((current) => ({ ...current, [nodeId]: value }));
      setConfigId(undefined);
      return;
    }
    if (!serverConfig || serverConfig.nodeId !== nodeId) return;
    setConfigLoading(true);
    setConfigError(undefined);
    const nextConfig = { ...serverConfig.config, connection: value.connection, sql: value.sql };
    writePipelineTransformConfig(document.id, nodeId, nextConfig)
      .then(() => readPipelineTransformConfig(document.id, nodeId))
      .then((authoritative) => {
        setServerConfig(authoritative);
        setConfigId(nodeId);
      })
      .catch((error: unknown) => {
        setConfigError(error instanceof Error ? error.message : "Transform config write failed");
      })
      .finally(() => setConfigLoading(false));
  }, [document.id, serverConfig, source]);

  const selected = nodes.find((node) => node.id === selectedId);
  const configured = nodes.find((node) => node.id === configId);
  const metadata = connections.find((connection) => connection.name === metadataName);
  const canConfigure = selected?.data.pluginId === "TableInput";
  const isRealGraph = source.kind === "server";
  const sourceLabel = source.kind === "server" ? "Server graph" : source.kind === "loading" ? "Loading real pipeline…" : "Development sample";

  return (
    <main className="shell">
      <header>
        <div><strong>Hop Modern Web</strong><span>{document.name}</span><span className="preview-badge" title={isRealGraph ? source.path : "Development fallback; open a server-visible .hpl path or configure VITE_HOP_PIPELINE_PATH."}>{sourceLabel}</span></div>
        <form className="pipeline-open" onSubmit={(event) => { event.preventDefault(); const path = pipelinePathDraft.trim(); if (path) setPipelinePath(path); }}>
          <input aria-label="Server-visible pipeline path" value={pipelinePathDraft} onChange={(event) => setPipelinePathDraft(event.target.value)} placeholder="Server-visible .hpl path" />
          <button type="submit" disabled={!pipelinePathDraft.trim() || source.kind === "loading"}>Open pipeline</button>
        </form>
        <div className="selection-actions">
          <small>{selected ? String(selected.data.label) : "Select a transform"}</small>
          <button type="button" onClick={() => setMetadataName((selected?.data.pluginId === "TableInput" && selected ? tableInputConfigs[selected.id]?.connection : undefined) ?? connections[0]?.name)}>Connections</button>
          {canConfigure && selected && <button type="button" disabled={configLoading} onClick={() => openTransformConfig(selected.id)}>Configure</button>}
        </div>
      </header>
      <section className="workspace">
        {source.kind !== "server" && <div className="preview-note">{source.kind === "loading" ? `Opening ${source.path}` : source.reason ? `Development sample fallback · ${source.reason}` : "Walking skeleton · open a server-visible .hpl above or set VITE_HOP_PIPELINE_PATH"}</div>}
        <ReactFlow nodes={nodes} edges={edges} onNodesChange={onNodesChange} onNodeClick={(_, node) => setSelectedId(node.id)} onNodeDoubleClick={(_, node) => node.data.pluginId === "TableInput" && openTransformConfig(node.id)} onNodeDragStop={onNodeDragStop} onPaneClick={() => setSelectedId(undefined)} fitView nodesDraggable nodesConnectable={false} panOnDrag zoomOnScroll zoomOnPinch>
          <MiniMap /><Controls /><Background />
        </ReactFlow>
        {configError && <div className="preview-note" role="alert">{configError}</div>}
        {configured?.data.pluginId === "TableInput" && (!isRealGraph || serverConfig?.nodeId === configured.id) && (
          <TableInputConfigPanel
            transformName={String(configured.data.label)}
            value={isRealGraph ? { connection: String(serverConfig?.config.connection ?? ""), sql: String(serverConfig?.config.sql ?? "") } : tableInputConfigs[configured.id] ?? { connection: "", sql: "" }}
            connections={connections.map((connection) => ({ id: connection.name, name: connection.name }))}
            onClose={() => { setConfigId(undefined); setServerConfig(undefined); setConfigError(undefined); }}
            onEditConnection={setMetadataName}
            onApply={(value) => applyTransformConfig(configured.id, value)}
          />
        )}
        {metadata && <DatabaseConnectionPanel value={metadata} onClose={() => setMetadataName(undefined)} onApply={(value) => { setConnections((current) => current.map((item) => item.name === metadata.name ? value : item)); setTableInputConfigs((current) => Object.fromEntries(Object.entries(current).map(([id, config]) => [id, config.connection === metadata.name ? { ...config, connection: value.name } : config]))); setMetadataName(undefined); }} />}
      </section>
    </main>
  );
}
