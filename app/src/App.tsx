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
  const edges = useMemo<Edge[]>(
    () => sample.edges.map((edge) => ({ id: edge.id, source: edge.source, target: edge.target })),
    [],
  );

  const onNodesChange = useCallback((changes: NodeChange<Node>[]) => {
    setNodes((current) => applyNodeChanges(changes, current));
  }, []);

  const selected = nodes.find((node) => node.id === selectedId);

  return (
    <main className="shell">
      <header>
        <div>
          <strong>Hop Modern Web</strong>
          <span>{sample.name}</span>
        </div>
        <small>{selected ? String(selected.data.label) : "Select a transform"}</small>
      </header>
      <section className="workspace">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onNodeClick={(_, node) => setSelectedId(node.id)}
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
      </section>
    </main>
  );
}
