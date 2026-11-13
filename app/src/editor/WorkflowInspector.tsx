import { useState } from "react";

type WorkflowAction = { name: string; type: string; x: number; y: number };
type WorkflowSnapshot = { id: string; path: string; name: string; description: string; dirty: boolean; actions?: WorkflowAction[] };

async function command<T>(operation: "open" | "edit" | "save", body: object): Promise<T> {
  const response = await fetch(`${import.meta.env.BASE_URL}api/workflows/${operation}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(response.status === 404 && !detail.error
      ? "Workflow 接口尚未部署到当前测试后端（HTTP 404），请先部署 Java 服务。"
      : response.status === 409
        ? "文件已被其他进程修改，无法覆盖。请先备份当前描述，再重新打开文件。"
        : detail.error || `Workflow ${operation} failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export function WorkflowInspector({ onClose }: { onClose: () => void }) {
  const [path, setPath] = useState("/workspace/example.hwf");
  const [workflow, setWorkflow] = useState<WorkflowSnapshot>();
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const hasUnappliedChanges = Boolean(workflow && description !== (workflow.description ?? ""));
  const hasUnsavedChanges = hasUnappliedChanges || Boolean(workflow?.dirty);
  function closeSafely() {
    if (!hasUnsavedChanges || window.confirm("Workflow 存在未保存的修改，确定放弃并关闭吗？")) onClose();
  }

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
        <button type="button" onClick={closeSafely} disabled={busy}>关闭</button>
      </div>
      <p>使用 Apache Hop 原生格式打开、编辑描述并保存工作流。节点画布编辑尚未开放。</p>
      <form onSubmit={event => {
        event.preventDefault();
        if (!path.trim()) return;
        if (hasUnsavedChanges && !window.confirm("打开其他 Workflow 会放弃当前未保存的修改，确定继续吗？")) return;
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
        <section aria-label="工作流节点概览" style={{ border: "1px solid #d1d5db", borderRadius: 8, padding: 12 }}>
          <strong>工作流节点 · {workflow.actions?.length ?? 0}</strong>
          <p style={{ margin: "6px 0", fontSize: 13 }}>以下节点来自原生 .hwf 文件，当前为只读概览，保存描述不会改变节点。</p>
          {(workflow.actions?.length ?? 0) === 0
            ? <p style={{ margin: 0 }}>此工作流没有节点。</p>
            : <ol style={{ maxHeight: 160, overflowY: "auto", margin: "8px 0 0", paddingLeft: 22 }}>
                {workflow.actions?.map((action, index) =>
                  <li key={index}>{action.name || "未命名节点"} · {action.type || "未知类型"}
                    <small style={{ marginLeft: 8, opacity: .65 }}>({action.x}, {action.y})</small>
                  </li>)}
              </ol>}
        </section>
        <small title={workflow.path}>{workflow.path}</small>
        <label htmlFor="workflow-description">工作流描述</label>
        <textarea id="workflow-description" rows={5} value={description}
          onChange={event => setDescription(event.target.value)} />
        <div style={{ display: "flex", gap: 8 }}>
          <button type="button" disabled={busy || description === (workflow.description ?? "")}
            onClick={() => void execute(() => command("edit", { id: workflow.id, description }))}>
            应用修改
          </button>
          <button type="button" disabled={busy || !hasUnsavedChanges}
            onClick={() => void execute(async () => {
              // Save is a single user action: apply the current textarea draft first.
              // Never write the disk if applying the edit fails.
              const latest = hasUnappliedChanges
                ? await command<WorkflowSnapshot>("edit", { id: workflow.id, description })
                : workflow;
              return command<WorkflowSnapshot>("save", { id: latest.id });
            })}>
            保存 .hwf
          </button>
          <span role="status">{hasUnappliedChanges ? "有尚未应用的修改" : workflow.dirty ? "已应用，尚未保存到磁盘" : "已保存"}</span>
        </div>
      </div>}
      {error && <p role="alert" style={{ color: "#b91c1c" }}>{error}</p>}
    </section>
  );
}