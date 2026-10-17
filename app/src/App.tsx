import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Background, Controls, MiniMap, ReactFlow, applyNodeChanges, type Connection, type Edge, type Node, type NodeChange, type ReactFlowInstance } from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";
import { addPipelineTransform, connectPipelineTransforms, deletePipelineTransform, movePipelineTransforms, openPipelineGraph, readPipelineTransformConfig, redoPipelineEdit, savePipeline, saveWorkbenchGraph, undoPipelineEdit, writePipelineTransformConfig, type GraphSource, type TransformConfigDocument } from "./editor/graphProvider";
import { GenericConfigPanel } from "./editor/GenericConfigPanel";
import { TRANSFORM_ICONS_A } from "./transformIconsA";
import { TRANSFORM_ICONS_B } from "./transformIconsB";
import { CATEGORY_ORDER, P0_TRANSFORMS, transformMeta, transformPrimaryName, transformSecondaryName } from "./transformCatalog";
import { TransformNode, TransformNodeActionsContext } from "./editor/TransformNode";
import { addDraftTransform, moveDraftTransform, connectDraftTransforms, deleteDraftTransform, renameDraftTransform, validateDraftGraph } from "./editor/draftGraph";

const TRANSFORM_ICONS: Record<string, string> = { ...TRANSFORM_ICONS_A, ...TRANSFORM_ICONS_B };

const NODE_TYPES = { transform: TransformNode };

function uniqueTransformId(pluginId: string, document: HopGraphDocument): string {
  const stem = pluginId.replace(/([a-z0-9])([A-Z])/g, "$1-$2").replace(/[^a-zA-Z0-9]+/g, "-").toLowerCase();
  let index = 1;
  let candidate = `${stem}-${index}`;
  while (document.nodes.some((node) => node.id === candidate)) candidate = `${stem}-${++index}`;
  return candidate;
}

const emptyDocument: HopGraphDocument = {
  id: "", name: "", revision: "", nodes: [], edges: [],
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


export function App({ initialPath, embedded = false, onRegisterSave, onSaveResult }: {
  initialPath?: string; embedded?: boolean;
  onRegisterSave?: (handler: () => void) => void;
  onSaveResult?: (result: { ok: boolean; message: string }) => void;
} = {}) {
  const initialPipelinePath = initialPath ?? (import.meta.env.VITE_HOP_PIPELINE_PATH?.trim() ?? "");
  const [document, setDocument] = useState<HopGraphDocument>(emptyDocument);
  const [source, setSource] = useState<GraphSource>({ kind: "sample" });
  const [nodes, setNodes] = useState<Node[]>([]);
  const [configId, setConfigId] = useState<string>();
  const [serverConfig, setServerConfig] = useState<TransformConfigDocument>();
  const [configLoading, setConfigLoading] = useState(false);
  const [configError, setConfigError] = useState<string>();
  const [draftRenameId, setDraftRenameId] = useState<string>();
  const [draftRenameText, setDraftRenameText] = useState("");
  const [pipelinePath, setPipelinePath] = useState(initialPipelinePath);
  const [pipelineOpenRevision, setPipelineOpenRevision] = useState(0);
  const [pipelinePathDraft, setPipelinePathDraft] = useState(initialPipelinePath);
  const [paletteQuery, setPaletteQuery] = useState("");
  const [flow, setFlow] = useState<ReactFlowInstance<Node, Edge> | null>(null);
  const [dirty, setDirty] = useState(false);
  const [showOpenPanel, setShowOpenPanel] = useState(!initialPipelinePath);
  const documentRef = useRef<HopGraphDocument>(emptyDocument);
  const undoRef = useRef<HopGraphDocument[]>([]);
  const redoRef = useRef<HopGraphDocument[]>([]);
  const savingRef = useRef(false);

  const applyLocalEdit = useCallback((produce: (current: HopGraphDocument) => HopGraphDocument) => {
    const before = documentRef.current;
    const after = produce(before);
    if (after === before || !validateDraftGraph(after)) return;
    undoRef.current = [...undoRef.current, before].slice(-60);
    redoRef.current = [];
    documentRef.current = after;
    setDocument(after);
    setNodes(graphNodes(after));
    setDirty(true);
  }, []);

  const travelHistory = useCallback((direction: "undo" | "redo") => {
    const from = direction === "undo" ? undoRef : redoRef;
    const into = direction === "undo" ? redoRef : undoRef;
    const previous = from.current.pop();
    if (!previous) return;
    into.current.push(documentRef.current);
    documentRef.current = previous;
    setDocument(previous);
    setNodes(graphNodes(previous));
    setDirty(true);
  }, []);

  useEffect(() => {
    // A new open attempt must never display the previous file as the current one.
    documentRef.current = emptyDocument;
    undoRef.current = [];
    redoRef.current = [];
    setDocument(emptyDocument);
    setNodes([]);
    setConfigId(undefined);
    setDraftRenameId(undefined);
    setConfigError(undefined);
    setServerConfig(undefined);
    setConfigError(undefined);
    setConfigLoading(false);
    setDirty(false);
    if (!pipelinePath) {
      setSource({ kind: "sample" });
      return;
    }
    const controller = new AbortController();
    setSource({ kind: "loading", path: pipelinePath });
    openPipelineGraph(pipelinePath, controller.signal).then((graph) => {
      if (controller.signal.aborted) return;
      documentRef.current = graph;
      setDocument(graph);
      setNodes(graphNodes(graph));
      setSource({ kind: "server", path: pipelinePath });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setSource({ kind: "sample", reason: error instanceof Error ? error.message : "无法打开流程" });
      setShowOpenPanel(true);
    });
    return () => controller.abort();
  }, [pipelinePath, pipelineOpenRevision]);

  const edges = useMemo<Edge[]>(() => document.edges.map((edge) => ({ id: edge.id, source: edge.source, target: edge.target })), [document]);
  const onNodesChange = useCallback((changes: NodeChange<Node>[]) => setNodes((current) => applyNodeChanges(changes, current)), []);
  const onNodeDragStop = useCallback((_: unknown, node: Node) => {
    if (source.kind !== "server") return;
    if (embedded) {
      applyLocalEdit(doc => moveDraftTransform(doc, node.id, Math.round(node.position.x), Math.round(node.position.y)));
      return;
    }
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
  }, [document, source, embedded, applyLocalEdit]);
  const reconcileGraph = useCallback((graph: HopGraphDocument) => {
    setDocument(graph);
    setNodes(graphNodes(graph));
  }, []);

  const runGraphCommand = useCallback((command: () => Promise<HopGraphDocument>) => {
    if (source.kind !== "server" || embedded) return;
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
    const current = documentRef.current;
    const nodeId = uniqueTransformId(pluginId, current);
    if (embedded) {
      const name = transformMeta(pluginId).name;
      applyLocalEdit(doc => addDraftTransform(doc, {
        id: nodeId, kind: "transform", pluginId, name: name + " " + (doc.nodes.length + 1),
        x: Math.round(x), y: Math.round(y), enabled: true,
      }));
      return;
    }
    runGraphCommand(() => addPipelineTransform(document.id, { nodeId, pluginId, x: Math.round(x), y: Math.round(y) }));
  }, [document, runGraphCommand, source, embedded, applyLocalEdit]);

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
    if (embedded) {
      applyLocalEdit(doc => connectDraftTransforms(doc, connection.source!, connection.target!));
      return;
    }
    runGraphCommand(() => connectPipelineTransforms(document.id, connection.source!, connection.target!));
  }, [document.id, runGraphCommand, source, embedded, applyLocalEdit]);

  const openTransformConfig = useCallback((nodeId: string) => {
    if (source.kind !== "server") return;
    if (embedded) {
      const selected = documentRef.current.nodes.find(item => item.id === nodeId)
      if (selected) {
        setConfigError(undefined)
        setDraftRenameId(nodeId)
        setDraftRenameText(selected.name)
      }
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
  }, [document.id, source, embedded]);

  const applyTransformConfig = useCallback((nodeId: string, value: Record<string, unknown>) => {
    if (source.kind !== "server") return;
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
    if (source.kind !== "server" || savingRef.current) return;
    if (!dirty && embedded) {
      onSaveResult?.({ ok: true, message: "当前流程没有未保存的修改" });
      return;
    }
    savingRef.current = true;
    setConfigError(undefined);
    const operation = embedded
      ? saveWorkbenchGraph(pipelinePath, documentRef.current)
      : savePipeline(document.id);
    operation.then((graph) => {
      documentRef.current = graph;
      reconcileGraph(graph);
      undoRef.current = [];
      redoRef.current = [];
      setDirty(false);
      onSaveResult?.({ ok: true, message: "流程已保存至 Hop Web" });
    }).catch((error: unknown) => {
      const message = error instanceof Error ? error.message : "Pipeline save failed";
      setConfigError(message);
      onSaveResult?.({ ok: false, message });
    }).finally(() => { savingRef.current = false; });
  }, [dirty, document.id, pipelinePath, embedded, reconcileGraph, source, onSaveResult]);
  useEffect(() => {
    onRegisterSave?.(saveDocument);
  }, [onRegisterSave, saveDocument]);

  const configured = nodes.find((node) => node.id === configId);
  const isRealGraph = source.kind === "server";
  const canEdit = isRealGraph;
  const displayPipelineName = isRealGraph ? (document.name || "未命名流程") : "未打开流程";
  const isEmptyEmbeddedGraph = embedded && isRealGraph && document.nodes.length === 0;

  return (
    <main className={embedded ? "shell embedded-shell" : "shell"} onKeyDownCapture={(event) => {
      if (!embedded || !isRealGraph || !(event.ctrlKey || event.metaKey) || event.altKey) return;
      const target = event.target as HTMLElement;
      if (target.closest?.('input, textarea, [contenteditable="true"]')) return;
      const key = event.key.toLowerCase();
      if (key === "z" || key === "y") {
        event.preventDefault();
        travelHistory(key === "y" || event.shiftKey ? "redo" : "undo");
      }
    }}>
      {!embedded && <header className="topbar">
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
          {!embedded && <button type="button" aria-label="Choose pipeline" onClick={() => setShowOpenPanel((current) => !current)}>打开</button>}
          {canEdit && <>
            <button type="button" aria-label="Undo" onClick={() => runGraphCommand(() => undoPipelineEdit(document.id))}>撤销</button>
            <button type="button" aria-label="Redo" onClick={() => runGraphCommand(() => redoPipelineEdit(document.id))}>重做</button>
            <button type="button" aria-label="Save" className="primary-action" disabled={!dirty} onClick={saveDocument}>保存</button>
          </>}
        </div>

        {!embedded && showOpenPanel && (
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
      </header>}

      <section className={isEmptyEmbeddedGraph ? "workspace workspace-empty-graph" : "workspace"}>
        {isRealGraph && (
          <aside className="transform-palette" aria-label="Transform palette">
            <div className="palette-heading">
              <div>
                <strong>转换组件</strong>
                <small>拖拽组件到画布</small>
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
                          <button key={pluginId} type="button" aria-label={pluginId} draggable={canEdit} onDragStart={(event) => { if (canEdit) onPaletteDragStart(event, pluginId); }} title={`${meta.name} · ${pluginId}`}>
                            <span className={`palette-icon category-${meta.category}`}>{TRANSFORM_ICONS[pluginId] ? <img src={TRANSFORM_ICONS[pluginId]} alt="" /> : meta.glyph}</span>
                            <span className="palette-item-copy">
                              <strong>{meta.name}</strong>
                              <small>拖放至画布</small>
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

        {!embedded && !isEmptyEmbeddedGraph && <div className="canvas-chrome" aria-hidden="true">
          <span>{document.nodes.length} 个转换</span>
          <i />
          <span>{document.edges.length} 条连接</span>
        </div>}
        {isEmptyEmbeddedGraph && (
          <div className="empty-graph-state" role="status">
            <span className="empty-graph-mark" aria-hidden="true">◇</span>
            <strong>此流程暂无转换节点</strong>
            <p>从左侧组件库拖入转换，开始设计流程。</p>
          </div>
        )}

        {source.kind !== "server" && (
          <div className="preview-note">
            {source.kind === "loading" ? "正在打开流程…" : source.reason ? `流程打开失败：${source.reason}` : "打开服务器上的 .hpl 文件后开始编辑"}
          </div>
        )}

        <TransformNodeActionsContext.Provider value={{
          configure: openTransformConfig,
          remove: (nodeId) => embedded
            ? applyLocalEdit(doc => deleteDraftTransform(doc, nodeId))
            : runGraphCommand(() => deletePipelineTransform(document.id, nodeId)),
          editable: canEdit,
          draftMode: embedded,
          loading: configLoading,
        }}>
        <ReactFlow nodeTypes={NODE_TYPES} nodes={nodes} edges={edges} onInit={setFlow} onDragOver={(event) => { if (canEdit) { event.preventDefault(); event.dataTransfer.dropEffect = "copy"; } }} onDrop={onCanvasDrop} onNodesChange={onNodesChange} onNodeDoubleClick={(_, node) => { if (canEdit) openTransformConfig(node.id); }} onNodeDragStop={onNodeDragStop} onConnect={connectNodes} fitView fitViewOptions={{ maxZoom: 1, padding: 0.24 }} maxZoom={1.5} nodesDraggable={canEdit} nodesConnectable={canEdit} panOnDrag zoomOnScroll zoomOnPinch>
          {nodes.length > 0 && <MiniMap pannable zoomable />}
          {nodes.length > 0 && <Controls />}
          <Background gap={24} size={1} />
        </ReactFlow>
        </TransformNodeActionsContext.Provider>

        {configError && <div className="preview-note error-note" role="alert">
          <span>{configError}</span>
          <button type="button" aria-label="关闭提示" onClick={() => setConfigError(undefined)}>×</button>
        </div>}
        {embedded && draftRenameId && (
          <form className="draft-rename-panel" onSubmit={(event) => {
            event.preventDefault();
            applyLocalEdit(doc => renameDraftTransform(doc, draftRenameId, draftRenameText));
            setDraftRenameId(undefined);
          }}>
            <strong>重命名转换</strong>
            <label htmlFor="hop-draft-rename">转换名称</label>
            <input id="hop-draft-rename" autoFocus maxLength={180} value={draftRenameText}
              onChange={(event) => setDraftRenameText(event.target.value)} />
            <small>仅修改本地草稿，尚不会写回 .hpl</small>
            <div>
              <button type="button" onClick={() => setDraftRenameId(undefined)}>取消</button>
              <button type="submit" disabled={!draftRenameText.trim()}>应用</button>
            </div>
          </form>
        )}
        {configured && isRealGraph && serverConfig?.nodeId === configured.id && (
          <GenericConfigPanel descriptor={serverConfig.descriptor} config={serverConfig.config} disabled={configLoading}
            onCancel={() => { setConfigId(undefined); setServerConfig(undefined); setConfigError(undefined); }}
            onSave={(value) => applyTransformConfig(configured.id, value)} />
        )}
      </section>
    </main>
  );
}