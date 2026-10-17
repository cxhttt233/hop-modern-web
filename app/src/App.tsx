import { useCallback, useEffect, useMemo, useState } from "react";
import { Background, Controls, MiniMap, ReactFlow, applyNodeChanges, type Connection, type Edge, type Node, type NodeChange, type ReactFlowInstance } from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";
import { DatabaseConnectionPanel, type DatabaseConnection } from "./editor/DatabaseConnectionPanel";
import { addPipelineTransform, connectPipelineTransforms, deletePipelineTransform, movePipelineTransforms, openPipelineGraph, readPipelineTransformConfig, redoPipelineEdit, savePipeline, undoPipelineEdit, writePipelineTransformConfig, type GraphSource, type TransformConfigDocument } from "./editor/graphProvider";
import { GenericConfigPanel } from "./editor/GenericConfigPanel";
import { TransformNode, TransformNodeActionsContext } from "./editor/TransformNode";
import { CATEGORY_ORDER, P0_TRANSFORMS, transformMeta, transformPrimaryName, transformSecondaryName } from "./transformCatalog";

const NODE_TYPES = { transform: TransformNode };

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
  return document.nodes.map((node) => {
    const meta = transformMeta(node.pluginId ?? "Unknown");
    return {
      id: node.id,
      type: "transform",
      className: "hop-transform-node category-" + meta.category,
      position: { x: node.x, y: node.y },
      data: {
        pluginId: node.pluginId,
        title: transformPrimaryName(node),
        secondary: transformSecondaryName(node),
        glyph: meta.glyph,
      },
    };
  });
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
  const [tableInputConfigs, setTableInputConfigs] = useState<Record<string, Record<string, unknown>>>({ input: { connection: "Warehouse", sql: "SELECT *\nFROM orders" } });
  const [pipelinePath, setPipelinePath] = useState(initialPipelinePath);
  const [pipelineOpenRevision, setPipelineOpenRevision] = useState(0);
  const [pipelinePathDraft, setPipelinePathDraft] = useState(initialPipelinePath);
  const [paletteQuery, setPaletteQuery] = useState("");
  const [flow, setFlow] = useState<ReactFlowInstance<Node, Edge> | null>(null);

  useEffect(() => {
    if (!pipelinePath) return;
    // Never show the last opened document after a different file fails to load.
    setNodes([]);
    setDocument({ id: "", name: "", revision: "", nodes: [], edges: [] });
    setSelectedId(undefined);
    setConfigId(undefined);
    setServerConfig(undefined);
    setConfigError(undefined);
    const controller = new AbortController();
    setSource({ kind: "loading", path: pipelinePath });
    openPipelineGraph(pipelinePath, controller.signal).then((graph) => {
      if (controller.signal.aborted) return;
      setDocument(graph);
      setNodes(graphNodes(graph));
      setSelectedId(undefined);
      setConfigId(undefined);
      setServerConfig(undefined);
      setConfigError(undefined);
      setSource({ kind: "server", path: pipelinePath });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setDocument({ id: "", name: "", revision: "", nodes: [], edges: [] });
      setNodes([]);
      setSource({ kind: "sample", reason: error instanceof Error ? error.message : "流程打开失败" });
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

  // Reuse the authoritative Modern Web semantic command API: no Workbench transport.
  const addTransformAt = useCallback((pluginId: string, x: number, y: number) => {
    if (source.kind !== "server") return;
    const stem = pluginId.replace(/([a-z0-9])([A-Z])/g, "$1-$2")
      .replace(/[^a-zA-Z0-9]+/g, "-").toLowerCase();
    let index = 1;
    let nodeId = `${stem}-${index}`;
    while (document.nodes.some((node) => node.id === nodeId)) nodeId = `${stem}-${++index}`;
    runGraphCommand(() => addPipelineTransform(document.id, {
      nodeId, pluginId, x: Math.round(x), y: Math.round(y),
    }));
  }, [document, runGraphCommand, source]);

  const onCanvasDrop = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    if (source.kind !== "server" || !flow) return;
    const pluginId = event.dataTransfer.getData("application/x-hop-transform");
    if (!P0_TRANSFORMS.includes(pluginId as (typeof P0_TRANSFORMS)[number])) return;
    const point = flow.screenToFlowPosition({ x: event.clientX, y: event.clientY });
    addTransformAt(pluginId, point.x, point.y);
  }, [addTransformAt, flow, source]);

  const filteredTransforms = useMemo(() => {
    const query = paletteQuery.trim().toLowerCase();
    return P0_TRANSFORMS.filter((pluginId) => {
      const meta = transformMeta(pluginId);
      return !query || pluginId.toLowerCase().includes(query) || meta.name.toLowerCase().includes(query);
    });
  }, [paletteQuery]);

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
  const sourceLabel = source.kind === "server" ? "实际流程" : source.kind === "loading" ? "正在加载…" : "开发预览";

  return (
    <main className="shell">
      <header className="modern-topbar">
        <div><strong>Hop 流程设计器</strong><span>{document.name}</span><span className="preview-badge" title={isRealGraph ? source.path : "Development fallback; open a server-visible .hpl path or configure VITE_HOP_PIPELINE_PATH."}>{sourceLabel}</span></div>
        <form className="pipeline-open" onSubmit={(event) => { event.preventDefault(); const path = pipelinePathDraft.trim(); if (!path) return; if (path === pipelinePath) setPipelineOpenRevision((current) => current + 1); else setPipelinePath(path); }}>
          <input aria-label="Server-visible pipeline path" value={pipelinePathDraft} onChange={(event) => setPipelinePathDraft(event.target.value)} placeholder="服务器上的 .hpl 文件路径" />
          <button type="submit" disabled={!pipelinePathDraft.trim() || source.kind === "loading"}>打开流程</button>
        </form>
        <div className="selection-actions">
          <small>{selected ? String(selected.data.title) : "选择转换节点"}</small>
          <button type="button" onClick={() => { const connection = selected ? tableInputConfigs[selected.id]?.connection : undefined; setMetadataName((typeof connection === "string" ? connection : undefined) ?? connections[0]?.name); }}>连接（示例）</button>
          
          {isRealGraph && selected && <button type="button" onClick={deleteSelected}>删除</button>}
          {isRealGraph && <button type="button" onClick={() => runGraphCommand(() => undoPipelineEdit(document.id))}>撤销</button>}
          {isRealGraph && <button type="button" onClick={() => runGraphCommand(() => redoPipelineEdit(document.id))}>重做</button>}
          {isRealGraph && <button type="button" onClick={() => runGraphCommand(() => savePipeline(document.id))}>保存</button>}
          {canConfigure && selected && <button type="button" disabled={configLoading} onClick={() => openTransformConfig(selected.id)}>配置</button>}
        </div>
      </header>
      <section className="workspace">
        <aside className="transform-palette" aria-label="转换组件库">
          <div className="palette-heading">
            <strong>转换组件</strong>
            <small>拖拽到画布</small>
            <span>{P0_TRANSFORMS.length}</span>
          </div>
          <label className="palette-search">
            <span className="sr-only">搜索转换</span>
            <input aria-label="搜索转换" placeholder="搜索组件名称或 ID…" value={paletteQuery}
              onChange={(event) => setPaletteQuery(event.target.value)} />
          </label>
          <div className="transform-list">
            {CATEGORY_ORDER.map((category) => {
              const items = filteredTransforms.filter((id) => transformMeta(id).category === category.id);
              if (!items.length) return null;
              return <section className="transform-group" key={category.id}>
                <h3>{category.label} <small>{items.length}</small></h3>
                {items.map((pluginId) => {
                  const meta = transformMeta(pluginId);
                  return <button type="button" key={pluginId} className="transform-palette-item"
                    draggable={isRealGraph}
                    disabled={!isRealGraph}
                    title={meta.name + " · " + pluginId}
                    onDragStart={(event) => {
                      event.dataTransfer.setData("application/x-hop-transform", pluginId);
                      event.dataTransfer.effectAllowed = "copy";
                    }}>
                    <span className={"palette-icon category-" + meta.category}>{meta.glyph}</span>
                    <span className="palette-copy"><strong>{meta.name}</strong><small>{pluginId}</small></span>
                    <span className="palette-grip" aria-hidden="true">⋮⋮</span>
                  </button>;
                })}
              </section>;
            })}
          </div>
        </aside>
        <div className="editor-canvas">
          {source.kind !== "server" &&
            <div className={"preview-note" + (source.reason ? " error-note" : "")} role={source.reason ? "alert" : "status"}>
              {source.kind === "loading" ? `正在打开 ${source.path}` : source.reason ? `流程打开失败：${source.reason}` : "打开服务器上的 .hpl 文件后开始编辑"}
            </div>}
          {isRealGraph && document.nodes.length === 0 &&
            <div className="empty-graph-state" role="status">
              <span aria-hidden="true">◇</span>
              <strong>此流程暂无转换节点</strong>
              <p>从左侧组件库拖入转换，开始设计流程。</p>
            </div>}
          <TransformNodeActionsContext.Provider value={{
            configure: openTransformConfig,
            remove: (nodeId) => runGraphCommand(() => deletePipelineTransform(document.id, nodeId)),
            editable: isRealGraph,
            loading: configLoading,
          }}>
            <ReactFlow nodeTypes={NODE_TYPES} nodes={nodes} edges={edges} onInit={setFlow}
              onNodesChange={onNodesChange}
              onNodeClick={(_, node) => setSelectedId(node.id)}
              onNodeDoubleClick={(_, node) => openTransformConfig(node.id)}
              onNodeDragStop={onNodeDragStop}
              onConnect={connectNodes}
              onPaneClick={() => setSelectedId(undefined)}
              onDragOver={(event) => { if (isRealGraph) { event.preventDefault(); event.dataTransfer.dropEffect = "copy"; } }}
              onDrop={onCanvasDrop}
              fitView fitViewOptions={{ padding: 0.24, maxZoom: 1 }}
              maxZoom={1.5} nodesDraggable={isRealGraph} nodesConnectable={isRealGraph}
              panOnDrag zoomOnScroll zoomOnPinch>
              {nodes.length > 0 && <MiniMap pannable zoomable />}
              {nodes.length > 0 && <Controls />}
              <Background gap={24} size={1} />
            </ReactFlow>
          </TransformNodeActionsContext.Provider>
          {configError && <div className="preview-note error-note" role="alert">
            {configError}
            <button type="button" aria-label="关闭提示" onClick={() => setConfigError(undefined)}>×</button>
          </div>}
        </div>
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
