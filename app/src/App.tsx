import { useCallback, useEffect, useMemo, useState } from "react";
import { Background, Controls, MiniMap, ReactFlow, applyNodeChanges, type Connection, type Edge, type Node, type NodeChange, type ReactFlowInstance } from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";
import { DatabaseConnectionPanel, type DatabaseConnection } from "./editor/DatabaseConnectionPanel";
import { addPipelineTransform, connectPipelineTransforms, deletePipelineTransform, movePipelineTransforms, openPipelineGraph, readPipelineTransformConfig, redoPipelineEdit, savePipeline, undoPipelineEdit, writePipelineTransformConfig, type GraphSource, type TransformConfigDocument } from "./editor/graphProvider";
import { GenericConfigPanel } from "./editor/GenericConfigPanel";

const P0_TRANSFORMS = [
  "CheckSum", "ConcatFields", "DataGrid", "ExecSql", "FilterRows", "GroupBy", "Http",
  "InsertUpdate", "JsonInput", "MergeJoin", "ReplaceString", "Rest", "ScriptValueMod",
  "SelectValues", "SetVariable", "StreamLookup", "StringCut", "TableInput", "TableOutput",
  "UniqueRowsByHashSet",
] as const;

type TransformCategory = "input" | "output" | "field" | "flow";

const CATEGORY_ORDER: Array<{ id: TransformCategory; label: string }> = [
  { id: "input", label: "输入与接入" },
  { id: "output", label: "输出与写入" },
  { id: "field", label: "字段处理" },
  { id: "flow", label: "流程与关联" },
];

const TRANSFORM_CATALOG: Record<string, { name: string; category: TransformCategory; glyph: string }> = {
  CheckSum: { name: "校验和", category: "field", glyph: "校" },
  ConcatFields: { name: "字段拼接", category: "field", glyph: "拼" },
  DataGrid: { name: "数据网格", category: "input", glyph: "数" },
  ExecSql: { name: "执行 SQL", category: "output", glyph: "SQL" },
  FilterRows: { name: "过滤记录", category: "flow", glyph: "滤" },
  GroupBy: { name: "分组汇总", category: "flow", glyph: "组" },
  Http: { name: "HTTP 客户端", category: "input", glyph: "HTTP" },
  InsertUpdate: { name: "插入 / 更新", category: "output", glyph: "写" },
  JsonInput: { name: "JSON 输入", category: "input", glyph: "JSON" },
  MergeJoin: { name: "合并连接", category: "flow", glyph: "合" },
  ReplaceString: { name: "字符串替换", category: "field", glyph: "替" },
  Rest: { name: "REST 客户端", category: "input", glyph: "REST" },
  ScriptValueMod: { name: "脚本计算", category: "field", glyph: "脚" },
  SelectValues: { name: "选择字段", category: "field", glyph: "选" },
  SetVariable: { name: "设置变量", category: "output", glyph: "变" },
  StreamLookup: { name: "流查询", category: "flow", glyph: "查" },
  StringCut: { name: "字符串截取", category: "field", glyph: "截" },
  TableInput: { name: "表输入", category: "input", glyph: "表" },
  TableOutput: { name: "表输出", category: "output", glyph: "出" },
  UniqueRowsByHashSet: { name: "记录去重", category: "flow", glyph: "去" },
  Dummy: { name: "占位转换", category: "flow", glyph: "·" },
  SortRows: { name: "排序记录", category: "flow", glyph: "序" },
};

function transformMeta(pluginId: string) {
  return TRANSFORM_CATALOG[pluginId] ?? { name: pluginId, category: "flow" as TransformCategory, glyph: pluginId.slice(0, 2).toUpperCase() };
}

function transformDisplayName(node: HopGraphDocument["nodes"][number]) {
  if (node.pluginId === "Dummy" && node.id === "source") return "输入";
  if (node.pluginId === "Dummy" && node.id === "sink") return "输出";
  const technical = node.name === node.id || /-\d+$/.test(node.name);
  return technical ? transformMeta(node.pluginId).name : node.name;
}

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
    const meta = transformMeta(node.pluginId);
    return {
      id: node.id,
      position: { x: node.x, y: node.y },
      className: `hop-transform-node category-${meta.category}`,
      data: {
        pluginId: node.pluginId,
        label: (
          <div className="transform-node-content">
            <span className="transform-node-icon">{meta.glyph}</span>
            <span className="transform-node-copy">
              <strong>{transformDisplayName(node)}</strong>
              <small>{meta.name}</small>
            </span>
          </div>
        ),
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
  const [dirty, setDirty] = useState(false);
  const [showOpenPanel, setShowOpenPanel] = useState(!initialPipelinePath);

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
      setDirty(false);
      setSource({ kind: "server", path: pipelinePath });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setDocument(sample);
      setNodes(graphNodes(sample));
      setDirty(false);
      setSource({ kind: "sample", reason: error instanceof Error ? error.message : "Pipeline open failed" });
    });
    return (
    <main className="shell">
      <header className="topbar">
        <div className="product-brand">
          <span className="brand-mark">H</span>
          <span className="brand-copy">
            <strong>Hop 流程设计器</strong>
            <small>Pipeline Editor</small>
          </span>
        </div>

        <div className="file-identity">
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
                          <button
                            key={pluginId}
                            type="button"
                            aria-label={pluginId}
                            draggable
                            onDragStart={(event) => onPaletteDragStart(event, pluginId)}
                            title={`${meta.name} · ${pluginId}`}
                          >
                            <span className={`palette-icon category-${meta.category}`}>{meta.glyph}</span>
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

        {selected && (
          <div className="selection-toolbar" aria-label="Selected transform actions">
            <span className={`selection-icon category-${selectedMeta?.category ?? "flow"}`}>{selectedMeta?.glyph ?? "·"}</span>
            <span className="selection-copy">
              <strong>{String((selected.data.label as { props?: { children?: unknown } })?.props ? transformDisplayName(document.nodes.find((node) => node.id === selected.id) ?? { id: selected.id, name: selected.id, kind: "transform", pluginId: String(selected.data.pluginId ?? ""), pluginType: "transform", x: 0, y: 0 }) : selected.id)}</strong>
              <small>{selectedMeta?.name ?? String(selected.data.pluginId ?? "")}</small>
            </span>
            {String(selected.data.pluginId ?? "") === "TableInput" && (
              <button type="button" aria-label="Connections" onClick={() => {
                const connection = tableInputConfigs[selected.id]?.connection;
                setMetadataName((typeof connection === "string" ? connection : undefined) ?? connections[0]?.name);
              }}>连接</button>
            )}
            {canConfigure && <button type="button" aria-label="Configure" className="primary-context" disabled={configLoading} onClick={() => openTransformConfig(selected.id)}>配置</button>}
            {isRealGraph && <button type="button" aria-label="Delete" className="danger-context" onClick={deleteSelected}>删除</button>}
          </div>
        )}

        {source.kind !== "server" && (
          <div className="preview-note">
            {source.kind === "loading" ? "正在打开流程…" : source.reason ? "流程打开失败，当前显示示例画布" : "打开一个 .hpl 流程开始编辑"}
          </div>
        )}

        <ReactFlow
          nodes={nodes}
          edges={edges}
          onInit={setFlow}
          onDragOver={(event) => { if (isRealGraph) { event.preventDefault(); event.dataTransfer.dropEffect = "copy"; } }}
          onDrop={onCanvasDrop}
          onNodesChange={onNodesChange}
          onNodeClick={(_, node) => setSelectedId(node.id)}
          onNodeDoubleClick={(_, node) => openTransformConfig(node.id)}
          onNodeDragStop={onNodeDragStop}
          onConnect={connectNodes}
          onPaneClick={() => setSelectedId(undefined)}
          fitView
          nodesDraggable
          nodesConnectable={isRealGraph}
          panOnDrag
          zoomOnScroll
          zoomOnPinch
        >
          <MiniMap pannable zoomable />
          <Controls />
          <Background gap={24} size={1} />
        </ReactFlow>

        {configError && <div className="preview-note error-note" role="alert">操作失败：{configError}</div>}
        {configured && isRealGraph && serverConfig?.nodeId === configured.id && (
          <GenericConfigPanel
            descriptor={serverConfig.descriptor}
            config={serverConfig.config}
            disabled={configLoading}
            onCancel={() => { setConfigId(undefined); setServerConfig(undefined); setConfigError(undefined); }}
            onSave={(value) => applyTransformConfig(configured.id, value)}
          />
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
