import { useMemo } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  type Edge,
  type Node,
} from "@xyflow/react";
import type { HopGraphDocument } from "@hop-modern/contracts";

const sample: HopGraphDocument = {
  id: "sample",
  name: "Pipeline",
  revision: "0",
  nodes: [
    {
      id: "input",
      name: "Table input",
      kind: "transform",
      pluginId: "TableInput",
      x: 80,
      y: 120,
    },
    {
      id: "select",
      name: "Select values",
      kind: "transform",
      pluginId: "SelectValues",
      x: 360,
      y: 120,
    },
    {
      id: "sort",
      name: "Sort rows",
      kind: "transform",
      pluginId: "SortRows",
      x: 640,
      y: 120,
    },
  ],
  edges: [
    { id: "input-select", source: "input", target: "select", enabled: true },
    { id: "select-sort", source: "select", target: "sort", enabled: true },
  ],
};

export function App() {
  const nodes = useMemo<Node[]>(
    () =>
      sample.nodes.map((node) => ({
        id: node.id,
        position: { x: node.x, y: node.y },
        data: { label: node.name },
      })),
    [],
  );

  const edges = useMemo<Edge[]>(
    () =>
      sample.edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
      })),
    [],
  );

  return (
    <main className="shell">
      <header>
        <div>
          <strong>Hop Modern Web</strong>
          <span>{sample.name}</span>
        </div>
        <small>React Flow product shell · no RAP</small>
      </header>
      <section className="workspace">
        <ReactFlow nodes={nodes} edges={edges} fitView>
          <MiniMap />
          <Controls />
          <Background />
        </ReactFlow>
      </section>
    </main>
  );
}
