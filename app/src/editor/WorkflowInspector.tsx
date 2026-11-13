import { useState } from "react";

type WorkflowSnapshot = { id: string; path: string; name: string; description: string; dirty: boolean };

async function command<T>(operation: "open" | "edit" | "save", body: object): Promise<T> {
  const response = await fetch(`/api/workflows/${operation}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(detail.error || `Workflow ${operation} failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export function WorkflowInspector({ onClose }: { onClose: () => void }) {
  const [path, setPath] = useState("/workspace/example.hwf");
  const [workflow, setWorkflow] = useState<WorkflowSnapshot>();
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function execute(action: () => Promise<WorkflowSnapshot>) {
    setBusy(true);
    setError("");
    try {
      const next = await action();
      setWorkflow(next);
      setDescription(next.description ?? "");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "操作失败");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section role="dialog" aria-modal="true" aria-label="Workflow 文件编辑" style={{
      position: "fixed", inset: "10% 18%", zIndex: 100, background: "var(--surface, #fff)",
      color: "#1f2937", border: "1px solid #cbd5e1", borderRadius: 12,
      padding: 24, boxShadow: "0 12px 45px #0003", overflow: "auto",
    }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h2 style={{ margin: 0 }}>Workflow · .hwf</h2>
        <button type="button" onClick={onClose}>关闭</button>
      </div>
      <p>使用 Apache Hop 原生格式打开、编辑描述并保存工作流。节点画布编辑尚未开放。</p>
      <form onSubmit={event => {
        event.preventDefault();
        if (!path.trim()) return;
        void execute(() => command("open", { path: path.trim() }));
      }}>
        <label htmlFor="workflow-path">服务器上的 .hwf 路径</label>
        <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
          <input id="workflow-path" style={{ flex: 1 }} value={path}
            onChange={event => setPath(event.target.value)} placeholder="/workspace/example.hwf" />
          <button type="submit" disabled={busy || !path.trim()}>打开</button>
        </div>
      </form>
      {workflow && <div style={{ display: "grid", gap: 12, marginTop: 20 }}>
        <strong>{workflow.name || "未命名工作流"}</strong>
        <small title={workflow.path}>{workflow.path}</small>
        <label htmlFor="workflow-description">工作流描述</label>
        <textarea id="workflow-description" rows={5} value={description}
          onChange={event => setDescription(event.target.value)} />
        <div style={{ display: "flex", gap: 8 }}>
          <button type="button" disabled={busy || description === (workflow.description ?? "")}
            onClick={() => void execute(() => command("edit", { id: workflow.id, description }))}>
            应用修改
          </button>
          <button type="button" disabled={busy || !workflow.dirty || description !== (workflow.description ?? "")}
            onClick={() => void execute(() => command("save", { id: workflow.id }))}>
            保存 .hwf
          </button>
          <span role="status">{workflow.dirty ? "未保存" : "已保存"}</span>
        </div>
      </div>}
      {error && <p role="alert" style={{ color: "#b91c1c" }}>{error}</p>}
    </section>
  );
}