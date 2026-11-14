import { useRef, useState } from "react";

type WorkflowAction = { name: string; type: string; x: number; y: number };
type WorkflowHop = { from: string; to: string; enabled: boolean };
type WorkflowSnapshot = { id: string; path: string; name: string; description: string; dirty: boolean; actions?: WorkflowAction[]; hops?: WorkflowHop[] };

async function command<T>(operation: "open" | "edit" | "move" | "hop-enabled" | "hop-delete" | "hop-create" | "action-rename" | "action-delete" | "action-add" | "save", body: object): Promise<T> {
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

/** Interactive spatial projection of the native Hop workflow graph. */
function WorkflowMap({ actions, hops, onMove, disabled }: { actions: WorkflowAction[]; hops: WorkflowHop[]; onMove: (name: string, x: number, y: number) => void; disabled: boolean }) {
  const drag = useRef<{ name: string; x: number; y: number; clientX: number; clientY: number } | null>(null);
  const [preview, setPreview] = useState<{ name: string; dx: number; dy: number } | null>(null);
  if (!actions.length) return null;
  const minX = Math.min(...actions.map(action => action.x));
  const minY = Math.min(...actions.map(action => action.y));
  const positions = actions.map(action => ({
    ...action, px: action.x - minX + 72, py: action.y - minY + 48,
  }));
  const width = Math.max(420, ...positions.map(action => action.px + 90));
  const height = Math.max(160, ...positions.map(action => action.py + 55));
  const byName = new Map(positions.map(action => [action.name, { ...action, px: action.px + (preview?.name === action.name ? preview.dx : 0), py: action.py + (preview?.name === action.name ? preview.dy : 0) }]));
  return <div style={{ overflow: "auto", maxHeight: 300, border: "1px solid #e2e8f0", borderRadius: 8 }}
    aria-label="Workflow 节点位置与连接示意图">
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img"
      aria-label={`${actions.length} 个节点，${hops.length} 条连接`}>
      <defs><marker id="workflow-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4"
        orient="auto"><path d="M0 0 L8 4 L0 8 Z" fill="#64748b" /></marker></defs>
      {hops.map((hop, index) => {
        const from = byName.get(hop.from);
        const to = byName.get(hop.to);
        if (!from || !to) return null;
        return <line key={index} x1={from.px} y1={from.py} x2={to.px} y2={to.py}
          stroke={hop.enabled ? "#64748b" : "#cbd5e1"} strokeWidth={2}
          strokeDasharray={hop.enabled ? undefined : "5 5"} markerEnd="url(#workflow-arrow)" />;
      })}
      {positions.map((action, index) => <g key={index} style={{ cursor: disabled ? "default" : "grab", touchAction: "none" }}
        transform={preview?.name === action.name ? `translate(${preview.dx} ${preview.dy})` : undefined}
        onPointerDown={event => {
          if (disabled || event.button !== 0) return;
          drag.current = { name: action.name, x: action.x, y: action.y, clientX: event.clientX, clientY: event.clientY };
          event.currentTarget.setPointerCapture(event.pointerId);
        }}
        onPointerMove={event => {
          if (drag.current?.name !== action.name) return;
          const bounds = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
          const scale = bounds?.width ? width / bounds.width : 1;
          setPreview({ name: action.name, dx: Math.round((event.clientX - drag.current.clientX) * scale), dy: Math.round((event.clientY - drag.current.clientY) * scale) });
        }}
        onPointerUp={event => {
          const current = drag.current;
          if (!current || current.name !== action.name) return;
          drag.current = null;
          const bounds = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
          const scale = bounds?.width ? width / bounds.width : 1;
          const dx = Math.round((event.clientX - current.clientX) * scale);
          const dy = Math.round((event.clientY - current.clientY) * scale);
          setPreview(null);
          if (dx || dy) onMove(action.name, Math.max(0, Math.min(100000, current.x + dx)), Math.max(0, Math.min(100000, current.y + dy)));
        }}
        onPointerCancel={() => { drag.current = null; setPreview(null); }}>
        <rect x={action.px - 56} y={action.py - 22} width={112} height={44} rx={8}
          fill="#eff6ff" stroke="#64748b" />
        <text x={action.px} y={action.py - 3} fontSize={12} textAnchor="middle"
          fill="#0f172a">{action.name.length > 14 ? action.name.slice(0, 13) + "…" : action.name}</text>
        <text x={action.px} y={action.py + 13} fontSize={10} textAnchor="middle"
          fill="#475569">{action.type}</text>
      </g>)}
    </svg>
  </div>;
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

  async function execute(action: () => Promise<WorkflowSnapshot>, preserveDescriptionDraft = false) {
    setBusy(true);
    setError("");
    try {
      const next = await action();
      setWorkflow(next);
      if (!preserveDescriptionDraft) setDescription(next.description ?? "");
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
      <p>使用 Apache Hop 原生格式打开、编辑描述并保存工作流。支持新增空操作、重命名和移动节点；可创建、启停或删除连接。</p>
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
          <p style={{ margin: "6px 0", fontSize: 13 }}>以下节点来自原生 .hwf 文件。拖动流程图节点或填写坐标可调整位置，保存后写入文件。</p>
          {(workflow.actions?.length ?? 0) === 0
            ? <p style={{ margin: 0 }}>此工作流没有节点。</p>
            : <ol style={{ maxHeight: 160, overflowY: "auto", margin: "8px 0 0", paddingLeft: 22 }}>
                {workflow.actions?.map((action, index) =>
                  <li key={index}>{action.name || "未命名节点"} · {action.type || "未知类型"}
                    <small style={{ marginLeft: 8, opacity: .65 }}>({action.x}, {action.y})</small>
                  </li>)}
              </ol>}
        </section>
        <section aria-label="新增原生工作流节点">
          <strong>新增节点 · Hop 原生 Action</strong>
          <p style={{ fontSize: 13 }}>当前支持 Hop 内置空操作（DUMMY），不伪造任意 JSON/XML 节点；新增后请创建连接并保存 .hwf。</p>
          <form aria-label="新增工作流节点" style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}
            onSubmit={event => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              const name = String(data.get("actionName") ?? "").trim();
              if (!name) return;
              const type = String(data.get("actionType") ?? "");
              void execute(() => command("action-add", {
                id: workflow.id, type, name, x: Number(data.get("actionX")), y: Number(data.get("actionY"))
              }), true);
            }}>
            <label>节点类型 <select name="actionType" disabled={busy}><option value="DUMMY">空操作（DUMMY）</option></select></label>
            <label>节点名称 <input name="actionName" aria-label="新增节点名称" required maxLength={200} disabled={busy}
              placeholder="例如：完成数据准备" /></label>
            <label>X <input name="actionX" type="number" min={0} max={100000} defaultValue={360} required disabled={busy}
              style={{ width: 85 }} /></label>
            <label>Y <input name="actionY" type="number" min={0} max={100000} defaultValue={200} required disabled={busy}
              style={{ width: 85 }} /></label>
            <button type="submit" disabled={busy}>新增原生节点</button>
          </form>
        </section>
        {(workflow.actions?.length ?? 0) > 0 && <section aria-label="重命名工作流节点">
          <strong>重命名节点</strong>
          <p style={{ fontSize: 13 }}>重命名会保留节点原有配置和连接关系；保存 .hwf 后生效于磁盘。</p>
          {(workflow.actions ?? []).map(action => <form key={action.name} style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 8 }}
            onSubmit={event => {
              event.preventDefault();
              const newName = String(new FormData(event.currentTarget).get("newName") ?? "").trim();
              if (!newName || newName === action.name) return;
              void execute(() => command("action-rename", { id: workflow.id, name: action.name, newName }), true);
            }}>
            <span style={{ minWidth: 110 }}>{action.name}</span>
            <label>新名称 <input name="newName" key={action.name} defaultValue={action.name} required maxLength={200} disabled={busy} /></label>
            <button type="submit" disabled={busy}>重命名</button>
          </form>)}
        </section>}
        {(workflow.actions?.length ?? 0) > 0 && <section aria-label="删除工作流节点">
          <strong>删除节点</strong>
          <p style={{ fontSize: 13 }}>删除节点时会同时删除与它相连的连接；其他节点及其原生参数保持不变。保存后才写入 .hwf。</p>
          {(workflow.actions ?? []).map(action => {
            const connectedHops = (workflow.hops ?? []).filter(h => h.from === action.name || h.to === action.name).length;
            return <div key={action.name} style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 8 }}>
              <span>{action.name} · {connectedHops} 条关联连接</span>
              <button type="button" disabled={busy} aria-label={`删除节点 ${action.name}`}
                onClick={() => {
                  if (!window.confirm(`删除节点「${action.name}」及 ${connectedHops} 条关联连接？保存后将写入 .hwf 文件。`)) return;
                  void execute(() => command("action-delete", { id: workflow.id, name: action.name, connectedHops }), true);
                }}>删除节点</button>
            </div>;
          })}
        </section>}
        <p style={{ fontSize: 13, margin: 0 }}>可直接拖动流程图中的节点调整位置；拖动后仍需点击“保存 .hwf”写入磁盘。</p>
        <WorkflowMap actions={workflow.actions ?? []} hops={workflow.hops ?? []} disabled={busy}
          onMove={(name, x, y) => void execute(() => command("move", { id: workflow.id, name, x, y }), true)} />
        {(workflow.actions?.length ?? 0) > 0 && <section aria-label="节点位置编辑">
          <strong>节点位置</strong>
          <p style={{ fontSize: 13 }}>修改节点坐标后点击“更新位置”，再点击“保存 .hwf”写入文件。</p>
          {(workflow.actions ?? []).map(action => <form key={action.name} style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}
            onSubmit={event => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              void execute(() => command("move", { id: workflow.id, name: action.name,
                x: Number(data.get("x")), y: Number(data.get("y")) }), true);
            }}>
            <span style={{ minWidth: 110 }}>{action.name}</span>
            <label>X <input name="x" type="number" min={0} max={100000} required defaultValue={action.x} key={action.name + "-x-" + action.x} style={{ width: 85 }} /></label>
            <label>Y <input name="y" type="number" min={0} max={100000} required defaultValue={action.y} key={action.name + "-y-" + action.y} style={{ width: 85 }} /></label>
            <button type="submit" disabled={busy}>更新位置</button>
          </form>)}
        </section>}
        <section aria-label="工作流执行链路" style={{ border: "1px solid #d1d5db", borderRadius: 8, padding: 12 }}>
          <strong>执行链路 · {workflow.hops?.length ?? 0} 条</strong>
          <p style={{ margin: "6px 0", fontSize: 13 }}>来自原生 .hwf 的连接关系。可切换连接启用状态，保存后写入文件。</p>
          {(workflow.actions?.length ?? 0) >= 2 && <form aria-label="创建工作流连接"
            style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", marginBottom: 12 }}
            onSubmit={event => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              const from = String(data.get("from") ?? "");
              const to = String(data.get("to") ?? "");
              if (!from || !to || from === to) return;
              void execute(() => command("hop-create", { id: workflow.id, from, to }), true);
            }}>
            <label>起点 <select name="from" required disabled={busy} defaultValue="">
              <option value="" disabled>选择节点</option>
              {workflow.actions?.map(action => <option key={action.name} value={action.name}>{action.name}</option>)}
            </select></label>
            <label>终点 <select name="to" required disabled={busy} defaultValue="">
              <option value="" disabled>选择节点</option>
              {workflow.actions?.map(action => <option key={action.name} value={action.name}>{action.name}</option>)}
            </select></label>
            <button type="submit" disabled={busy}>创建连接</button>
          </form>}
          {(workflow.hops?.length ?? 0) === 0 ? <p style={{ margin: 0 }}>当前没有节点连接。</p> :
            <ol style={{ maxHeight: 160, overflowY: "auto", margin: "8px 0 0", paddingLeft: 22 }}>
              {workflow.hops?.map((hop, index) => <li key={`${hop.from}-${hop.to}-${index}`}
                style={{ opacity: hop.enabled ? 1 : .5 }}>
                {hop.from} → {hop.to}
                <label style={{ marginLeft: 12 }}><input type="checkbox" checked={hop.enabled} disabled={busy}
                  onChange={() => void execute(() => command("hop-enabled", {
                    id: workflow.id, index, from: hop.from, to: hop.to, enabled: !hop.enabled
                  }), true)} />启用</label>
                <button type="button" disabled={busy} style={{ marginLeft: 8 }}
                  aria-label={`删除连接 ${hop.from} 到 ${hop.to}`}
                  onClick={() => {
                    if (!window.confirm(`删除连接 ${hop.from} → ${hop.to}？保存后将写入 .hwf 文件。`)) return;
                    void execute(() => command("hop-delete", {
                      id: workflow.id, index, from: hop.from, to: hop.to
                    }), true);
                  }}>删除连接</button>
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
              // Preserve the applied server draft if the disk write fails.
              setWorkflow(latest);
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