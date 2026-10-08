import { useCallback, useEffect, useMemo, useState } from "react";
import { Background, Controls, MiniMap, ReactFlow, applyNodeChanges, type Connection, type Edge, type Node, type NodeChange, type ReactFlowInstance } from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";
import { DatabaseConnectionPanel, type DatabaseConnection } from "./editor/DatabaseConnectionPanel";
import { addPipelineTransform, connectPipelineTransforms, deletePipelineTransform, movePipelineTransforms, openPipelineGraph, readPipelineTransformConfig, redoPipelineEdit, savePipeline, undoPipelineEdit, writePipelineTransformConfig, type GraphSource, type TransformConfigDocument } from "./editor/graphProvider";
import { GenericConfigPanel } from "./editor/GenericConfigPanel";
import { TRANSFORM_ICONS_A } from "./transformIconsA";
import { TRANSFORM_ICONS_B } from "./transformIconsB";
import { CATEGORY_ORDER, P0_TRANSFORMS, transformMeta, transformPrimaryName, transformSecondaryName } from "./transformCatalog";
import { TransformNode, TransformNodeActionsContext } from "./editor/TransformNode";

const TRANSFORM_ICONS: Record<string, string> = { ...TRANSFORM_ICONS_A, ...TRANSFORM_ICONS_B };

const NODE_TYPES = { transform: TransformNode };

function uniqueTransformId(pluginId: string, document: HopGraphDocument): string {
  const stem = pluginId.replace(/([a-z0-9])([A-Z])/g, "$1-$2").replace(/[^a-zA-Z0-9]+/g, "-").toLowerCase();
  let index = 1;
  let candidate = `${stem}-${index}`;
  while (document.nodes.some((node) => node.id === candidate)) candidate = `${stem}-${++index}`;
  return candidate;
}

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
      position: { x: node.x, y: node.y },
      className: "hop-transform-node category-" + meta.category,
      data: {
        pluginId: node.pluginId,
        title: transformPrimaryName(node),
        secondary: transformSecondaryName(node),
        icon: TRANSFORM_ICONS[node.pluginId ?? ""],
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
  const [dirty, setDirty] = useState(false);
  const [showOpenPanel, setShowOpenPanel] = useState(!initialPipelinePath);

  useEffect(() => {
    if (!pipelinePath) return;
    const controller = new AbortController();
    setSource({ kind: "loading", path: pipelinePath });
    openPipelineGraph(pipelinePath, controller.signal).then((graph) => {
      setDocument(graph);
      setNodes(graphNodes(graph));
      setConfigId(undefined);
      setServerConfig(undefined);
      setConfigError(undefined);
      setDirty(false);
      setSource({ kind: "server", path: pipelinePath });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setDocument(sample);
      setNodes(graphNodes(sample));
      setDirty(false);
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
      setDirty(true);
    }).catch(() => {
      setNodes(graphNodes(document));
    });
  }, [document, source]);
  const reconcileGraph = useCallback((graph: HopGraphDocument) => {
    setDocument(graph);
    setNodes(graphNodes(graph));
  }, []);

  const runGraphCommand = useCallback((command: () => Promise<HopGraphDocument>) => {
    if (source.kind !== "server") return;
    command().then((graph) => {
      reconcileGraph(graph);
      setDirty(true);
    }).catch((error: unknown) => {
      setConfigError(error instanceof Error ? error.message : "Pipeline edit failed");
      setNodes(graphNodes(document));
    });
  }, [document, reconcileGraph, source]);

  const addTransformAt = useCallback((pluginId: string, x: number, y: number) => {
    if (source.kind !== "server") return;
    const nodeId = uniqueTransformId(pluginId, document);
    runGraphCommand(() => addPipelineTransform(document.id, { nodeId, pluginId, x: Math.round(x), y: Math.round(y) }));
  }, [document, runGraphCommand, source]);

  const onPaletteDragStart = useCallback((event: React.DragEvent, pluginId: string) => {
    event.dataTransfer.setData("application/x-hop-transform", pluginId);
    event.dataTransfer.effectAllowed = "copy";
  }, []);

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
    if (!query) return [...P0_TRANSFORMS];
    return P0_TRANSFORMS.filter((pluginId) => {
      const meta = transformMeta(pluginId);
      return pluginId.toLowerCase().includes(query) || meta.name.toLowerCase().includes(query);
    });
  }, [paletteQuery]);

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
        setDirty(true);
      })
      .catch((error: unknown) => {
        setConfigError(error instanceof Error ? error.message : "Transform config write failed");
      })
      .finally(() => setConfigLoading(false));
  }, [document.id, serverConfig, source]);

  const saveDocument = useCallback(() => {
    if (source.kind !== "server") return;
    setConfigError(undefined);
    savePipeline(document.id)
      .then((graph) => {
        reconcileGraph(graph);
        setDirty(false);
      })
      .catch((error: unknown) => {
        setConfigError(error instanceof Error ? error.message : "Pipeline save failed");
      });
  }, [document.id, reconcileGraph, source]);

  const configured = nodes.find((node) => node.id === configId);
  const metadata = connections.find((connection) => connection.name === metadataName);
  const isRealGraph = source.kind === "server";
  const displayPipelineName = document.name === "real-graph-proof" ? "示例流程" : (document.name || "未命名流程");

  return (
    <main className="shell">
      <header className="topbar">
        <div className="product-brand">
          <span className="brand-mark">H</span>
          <span className="brand-copy">
            <strong>Hop 流程设计器</strong>
            <small>流程编排与配置</small>
          </span>
        </div>

        <div className="file-identity" data-document-name={document.name}>
          <span className="file-dot" aria-hidden="true" />
          <strong>{displayPipelineName}</strong>
          {isRealGraph && dirty && <span className="dirty-state is-dirty" role="status">未保存</span>}
        </div>

        <div className="editor-actions">
          <button type="button" aria-label="Choose pipeline" onClick={() => setShowOpenPanel((current) => !current)}>打开</button>
          {isRealGraph && <>
            <button type="button" aria-label="Undo" onClick={() => runGraphCommand(() => undoPipelineEdit(document.id))}>撤销</button>
            <button type="button" aria-label="Redo" onClick={() => runGraphCommand(() => redoPipelineEdit(document.id))}>重做</button>
            <button type="button" aria-label="Save" className="primary-action" disabled={!dirty} onClick={saveDocument}>保存</button>
          </>}
        </div>

        {showOpenPanel && (
          <form className="pipeline-open" onSubmit={(event) => {
            event.preventDefault();
            const path = pipelinePathDraft.trim();
            if (!path) return;
            if (path === pipelinePath) setPipelineOpenRevision((current) => current + 1);
            else setPipelinePath(path);
            setShowOpenPanel(false);
          }}>
            <div>
              <strong>打开流程</strong>
              <small>输入服务器上的 .hpl 文件路径</small>
            </div>
            <input aria-label="Server-visible pipeline path" value={pipelinePathDraft} onChange={(event) => setPipelinePathDraft(event.target.value)} placeholder="/workspace/example.hpl" autoFocus />
            <div className="pipeline-open-actions">
              {initialPipelinePath && <button type="button" onClick={() => setShowOpenPanel(false)}>取消</button>}
              <button type="submit" aria-label="Open pipeline" className="primary-action" disabled={!pipelinePathDraft.trim() || source.kind === "loading"}>打开文件</button>
            </div>
          </form>
        )}
      </header>

      <section className="workspace">
        {isRealGraph && (
          <aside className="transform-palette" aria-label="Transform palette">
            <div className="palette-heading">
              <div>
                <strong>转换组件</strong>
                <small>拖到画布中使用</small>
              </div>
              <span>{P0_TRANSFORMS.length}</span>
            </div>
            <div className="palette-search">
              <span aria-hidden="true">⌕</span>
              <input aria-label="Search transforms" placeholder="搜索组件…" value={paletteQuery} onChange={(event) => setPaletteQuery(event.target.value)} />
            </div>
            <div className="transform-list">
              {CATEGORY_ORDER.map((category) => {
                const items = filteredTransforms.filter((pluginId) => transformMeta(pluginId).category === category.id);
                if (!items.length) return null;
                return (
                  <section className="transform-group" key={category.id}>
                    <div className="transform-group-title">
                      <span>{category.label}</span>
                      <small>{items.length}</small>
                    </div>
                    <div className="transform-items">
                      {items.map((pluginId) => {
                        const meta = transformMeta(pluginId);
                        return (
                          <button key={pluginId} type="button" aria-label={pluginId} draggable onDragStart={(event) => onPaletteDragStart(event, pluginId)} title={`${meta.name} · ${pluginId}`}>
                            <span className={`palette-icon category-${meta.category}`}>{TRANSFORM_ICONS[pluginId] ? <img src={TRANSFORM_ICONS[pluginId]} alt="" /> : meta.glyph}</span>
                            <span className="palette-item-copy">
                              <strong>{meta.name}</strong>
                              <small>{pluginId}</small>
                            </span>
                            <span className="drag-grip" aria-hidden="true">⋮⋮</span>
                          </button>
                        );
                      })}
                    </div>
                  </section>
                );
              })}
            </div>
          </aside>
        )}

        <div className="canvas-chrome" aria-hidden="true">
          <span>{document.nodes.length} 个转换</span>
          <i />
          <span>{document.edges.length} 条连接</span>
        </div>

        {source.kind !== "server" && (
          <div className="preview-note">
            {source.kind === "loading" ? "正在打开流程…" : source.reason ? "流程打开失败，当前显示示例画布" : "打开一个 .hpl 流程开始编辑"}
          </div>
        )}

        <TransformNodeActionsContext.Provider value={{
          configure: openTransformConfig,
          remove: (nodeId) => runGraphCommand(() => deletePipelineTransform(document.id, nodeId)),
          editable: isRealGraph,
          loading: configLoading,
        }}>
        <ReactFlow nodeTypes={NODE_TYPES} nodes={nodes} edges={edges} onInit={setFlow} onDragOver={(event) => { if (isRealGraph) { event.preventDefault(); event.dataTransfer.dropEffect = "copy"; } }} onDrop={onCanvasDrop} onNodesChange={onNodesChange} onNodeDoubleClick={(_, node) => openTransformConfig(node.id)} onNodeDragStop={onNodeDragStop} onConnect={connectNodes} fitView nodesDraggable nodesConnectable={isRealGraph} panOnDrag zoomOnScroll zoomOnPinch>
          <MiniMap pannable zoomable />
          <Controls />
          <Background gap={24} size={1} />
        </ReactFlow>
        </TransformNodeActionsContext.Provider>

        {configError && <div className="preview-note error-note" role="alert">操作失败：{configError}</div>}
        {configured && isRealGraph && serverConfig?.nodeId === configured.id && (
          <GenericConfigPanel descriptor={serverConfig.descriptor} config={serverConfig.config} disabled={configLoading}
            onCancel={() => { setConfigId(undefined); setServerConfig(undefined); setConfigError(undefined); }}
            onSave={(value) => applyTransformConfig(configured.id, value)} />
        )}
        {metadata && <DatabaseConnectionPanel value={metadata} onClose={() => setMetadataName(undefined)} onApply={(value) => {
          setConnections((current) => current.map((item) => item.name === metadata.name ? value : item));
          setTableInputConfigs((current) => Object.fromEntries(Object.entries(current).map(([id, config]) => [id, config.connection === metadata.name ? { ...config, connection: value.name } : config])));
          setMetadataName(undefined);
        }} />}
      </section>
    </main>
  );
}
