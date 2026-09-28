import { useCallback, useEffect, useMemo, useState } from "react";
import { Background, Controls, MiniMap, ReactFlow, applyNodeChanges, type Connection, type Edge, type Node, type NodeChange } from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";
import { DatabaseConnectionPanel, type DatabaseConnection } from "./editor/DatabaseConnectionPanel";
import { addPipelineTransform, connectPipelineTransforms, deletePipelineTransform, movePipelineTransforms, openPipelineGraph, readPipelineTransformConfig, redoPipelineEdit, savePipeline, undoPipelineEdit, writePipelineTransformConfig, type GraphSource, type TransformConfigDocument } from "./editor/graphProvider";
import { GenericConfigPanel } from "./editor/GenericConfigPanel";
import type { TableInputConfig } from "./editor/TableInputConfigPanel";

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
  const [pipelineOpenRevision, setPipelineOpenRevision] = useState(0);
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
  }, [pipelinePath, pipelineOpenRevision]);

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
  const reconcileGraph = useCallback((graph: HopGraphDocument) => {
    setDocument(graph);
    setNodes(graphNodes(graph));
    setSelectedId((current) => current && graph.nodes.some((node) => node.id === current) ? current : undefined);
  }, []);

  const runGraphCommand = useCallback((command: () => Promise<HopGraphDocument>) => {
    if (source.kind !== "server") return;
    command().then(reconcileGraph).catch((error: unknown) => {
      setConfigError(error instanceof Error ? error.message : "Pipeline edit failed");
      setNodes(graphNodes(document));
    });
  }, [document, reconcileGraph, source]);

  const addTransform = useCallback(() => {
    if (source.kind !== "server") return;
    let index = document.nodes.length + 1;
    let nodeId = `new-transform-${index}`;
    while (document.nodes.some((node) => node.id === nodeId)) nodeId = `new-transform-${++index}`;
    const nextX = Math.max(160, ...document.nodes.map((node) => node.x + 180));
    runGraphCommand(() => addPipelineTransform(document.id, { nodeId, pluginId: "TableInput", x: nextX, y: 160 }));
  }, [document, runGraphCommand, source]);

  const deleteSelected = useCallback(() => {
    if (source.kind !== "server" || !selectedId) return;
    runGraphCommand(() => deletePipelineTransform(document.id, selectedId));
  }, [document.id, runGraphCommand, selectedId, source]);

  const connectNodes = useCallback((connection: Connection) => {
    if (source.kind !== "server" || !connection.source || !connection.target) return;
    runGraphCommand(() => connectPipelineTransforms(document.id, connection.source, connection.target));
  }, [document.id, runGraphCommand, source]);

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

  const applyTransformConfig = useCallback((nodeId: string, value: Record<string, unknown>) => {
    if (source.kind !== "server") {
      setTableInputConfigs((current) => ({ ...current, [nodeId]: value }));
      setConfigId(undefined);
      return;
    }
    if (!serverConfig || serverConfig.nodeId !== nodeId) return;
    setConfigLoading(true);
    setConfigError(undefined);
    writePipelineTransformConfig(document.id, nodeId, value)
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
  const canConfigure = Boolean(selected);
  const isRealGraph = source.kind === "server";
  const sourceLabel = source.kind === "server" ? "Server graph" : source.kind === "loading" ? "Loading real pipeline…" : "Development sample";

  return (
    <main className="shell">
      <header>
        <div><strong>Hop Modern Web</strong><span>{document.name}</span><span className="preview-badge" title={isRealGraph ? source.path : "Development fallback; open a server-visible .hpl path or configure VITE_HOP_PIPELINE_PATH."}>{sourceLabel}</span></div>
        <form className="pipeline-open" onSubmit={(event) => { event.preventDefault(); const path = pipelinePathDraft.trim(); if (!path) return; if (path === pipelinePath) setPipelineOpenRevision((current) => current + 1); else setPipelinePath(path); }}>
          <input aria-label="Server-visible pipeline path" value={pipelinePathDraft} onChange={(event) => setPipelinePathDraft(event.target.value)} placeholder="Server-visible .hpl path" />
          <button type="submit" disabled={!pipelinePathDraft.trim() || source.kind === "loading"}>Open pipeline</button>
        </form>
        <div className="selection-actions">
          <small>{selected ? String(selected.data.label) : "Select a transform"}</small>
          <button type="button" onClick={() => setMetadataName((selected?.data.pluginId === "TableInput" && selected ? tableInputConfigs[selected.id]?.connection : undefined) ?? connections[0]?.name)}>Connections</button>
          {isRealGraph && <button type="button" onClick={addTransform}>Add Table Input</button>}
          {isRealGraph && selected && <button type="button" onClick={deleteSelected}>Delete</button>}
          {isRealGraph && <button type="button" onClick={() => runGraphCommand(() => undoPipelineEdit(document.id))}>Undo</button>}
          {isRealGraph && <button type="button" onClick={() => runGraphCommand(() => redoPipelineEdit(document.id))}>Redo</button>}
          {isRealGraph && <button type="button" onClick={() => runGraphCommand(() => savePipeline(document.id))}>Save</button>}
          {canConfigure && selected && <button type="button" disabled={configLoading} onClick={() => openTransformConfig(selected.id)}>Configure</button>}
        </div>
      </header>
      <section className="workspace">
        {source.kind !== "server" && <div className="preview-note">{source.kind === "loading" ? `Opening ${source.path}` : source.reason ? `Development sample fallback · ${source.reason}` : "Walking skeleton · open a server-visible .hpl above or set VITE_HOP_PIPELINE_PATH"}</div>}
        <ReactFlow nodes={nodes} edges={edges} onNodesChange={onNodesChange} onNodeClick={(_, node) => setSelectedId(node.id)} onNodeDoubleClick={(_, node) => openTransformConfig(node.id)} onNodeDragStop={onNodeDragStop} onConnect={connectNodes} onPaneClick={() => setSelectedId(undefined)} fitView nodesDraggable nodesConnectable={isRealGraph} panOnDrag zoomOnScroll zoomOnPinch>
          <MiniMap /><Controls /><Background />
        </ReactFlow>
        {configError && <div className="preview-note" role="alert">{configError}</div>}
        {configured && isRealGraph && serverConfig?.nodeId === configured.id && (
          <GenericConfigPanel
            descriptor={serverConfig.descriptor}
            config={serverConfig.config}
            disabled={configLoading}
            onCancel={() => { setConfigId(undefined); setServerConfig(undefined); setConfigError(undefined); }}
            onSave={(value) => applyTransformConfig(configured.id, value)}
          />
        )}
        {metadata && <DatabaseConnectionPanel value={metadata} onClose={() => setMetadataName(undefined)} onApply={(value) => { setConnections((current) => current.map((item) => item.name === metadata.name ? value : item)); setTableInputConfigs((current) => Object.fromEntries(Object.entries(current).map(([id, config]) => [id, config.connection === metadata.name ? { ...config, connection: value.name } : config]))); setMetadataName(undefined); }} />}
      </section>
    </main>
  );
}
