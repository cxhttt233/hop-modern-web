import { useCallback, useMemo, useState } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  applyNodeChanges,
  type Edge,
  type Node,
  type NodeChange,
} from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";
import { TableInputConfigPanel, type TableInputConfig } from "./editor/TableInputConfigPanel";

const sample: HopGraphDocument = {
  id: "sample",
  name: "Pipeline",
  revision: "0",
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

const connections = [
  { id: "warehouse", name: "Warehouse" },
  { id: "reporting", name: "Reporting" },
];

function graphNodes(document: HopGraphDocument): Node[] {
  return document.nodes.map((node) => ({
    id: node.id,
    position: { x: node.x, y: node.y },
    data: { label: node.name, pluginId: node.pluginId },
  }));
}

export function App() {
  const [nodes, setNodes] = useState<Node[]>(() => graphNodes(sample));
  const [selectedId, setSelectedId] = useState<string>();
  const [configId, setConfigId] = useState<string>();
  const [tableInputConfigs, setTableInputConfigs] = useState<Record<string, TableInputConfig>>({
    input: { connectionId: "warehouse", sql: "SELECT *\nFROM orders" },
  });
  const edges = useMemo<Edge[]>(
    () => sample.edges.map((edge) => ({ id: edge.id, source: edge.source, target: edge.target })),
    [],
  );

  const onNodesChange = useCallback((changes: NodeChange<Node>[]) => {
    setNodes((current) => applyNodeChanges(changes, current));
  }, []);

  const selected = nodes.find((node) => node.id === selectedId);
  const configured = nodes.find((node) => node.id === configId);
  const canConfigure = selected?.data.pluginId === "TableInput";

  return (
    <main className="shell">
      <header>
        <div>
          <strong>Hop Modern Web</strong>
          <span>{sample.name}</span>
        </div>
        <div className="selection-actions">
          <small>{selected ? String(selected.data.label) : "Select a transform"}</small>
          {canConfigure && (
            <button type="button" onClick={() => setConfigId(selected.id)}>
              Configure
            </button>
          )}
        </div>
      </header>
      <section className="workspace">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onNodeClick={(_, node) => setSelectedId(node.id)}
          onNodeDoubleClick={(_, node) => node.data.pluginId === "TableInput" && setConfigId(node.id)}
          onPaneClick={() => setSelectedId(undefined)}
          fitView
          nodesDraggable
          nodesConnectable={false}
          panOnDrag
          zoomOnScroll
          zoomOnPinch
        >
          <MiniMap />
          <Controls />
          <Background />
        </ReactFlow>
        {configured?.data.pluginId === "TableInput" && (
          <TableInputConfigPanel
            transformName={String(configured.data.label)}
            value={tableInputConfigs[configured.id] ?? { connectionId: "", sql: "" }}
            connections={connections}
            onClose={() => setConfigId(undefined)}
            onApply={(value) => {
              setTableInputConfigs((current) => ({ ...current, [configured.id]: value }));
              setConfigId(undefined);
            }}
          />
        )}
      </section>
    </main>
  );
}
