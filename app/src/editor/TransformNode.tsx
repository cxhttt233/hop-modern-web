import { createContext, useContext } from "react";
import { Handle, NodeToolbar, Position, type NodeProps } from "@xyflow/react";

export interface TransformNodeActions {
  configure: (nodeId: string) => void;
  remove: (nodeId: string) => void;
  editable: boolean;
  loading: boolean;
}

export const TransformNodeActionsContext = createContext<TransformNodeActions | null>(null);

/** Native React Flow node: the handles remain real React Flow connection targets. */
export function TransformNode({ id, data, selected }: NodeProps) {
  const actions = useContext(TransformNodeActionsContext);
  const title = String(data.title ?? data.pluginId ?? "转换");
  const pluginId = String(data.pluginId ?? "");
  const secondary = String(data.secondary ?? pluginId);
  const icon = typeof data.icon === "string" ? data.icon : undefined;
  const glyph = String(data.glyph ?? "·");

  return (
    <>
      <Handle
        type="target"
        position={Position.Left}
        className="target"
        aria-label={"连接输入：" + title}
        title="连接输入"
        isConnectable={Boolean(actions?.editable)}
      />
      <div
        className="transform-node-content"
        data-testid={"transform-node-" + id}
        data-plugin-id={pluginId}
        title={title + " · " + pluginId}
      >
        <span className="transform-node-icon" aria-hidden="true">
          {icon ? <img src={icon} alt="" /> : glyph}
        </span>
        <span className="transform-node-copy">
          <strong>{title}</strong>
          <small>{secondary}</small>
        </span>
      </div>
      <Handle
        type="source"
        position={Position.Right}
        className="source"
        aria-label={"连接输出：" + title}
        title="拖动连接到下一个转换"
        isConnectable={Boolean(actions?.editable)}
      />
      <NodeToolbar
        isVisible={Boolean(selected && actions?.editable)}
        position={Position.Top}
        offset={12}
        className="node-context-actions"
      >
        <span className="node-action-label">{title}</span>
        <button
          type="button"
          className="nodrag nopan primary-context"
          aria-label="Configure"
          disabled={actions?.loading}
          onClick={(event) => { event.stopPropagation(); actions?.configure(id); }}
        >配置</button>
        <button
          type="button"
          className="nodrag nopan danger-context"
          aria-label="Delete"
          onClick={(event) => { event.stopPropagation(); actions?.remove(id); }}
        >删除</button>
      </NodeToolbar>
    </>
  );
}
