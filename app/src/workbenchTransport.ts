/**
 * Workbench-only transport for the standalone Modern editor protocol.
 *
 * The browser talks ONLY to Workbench's authenticated product API.
 * Workbench owns file paths, permissions, CAS, backups and audit.
 * The existing Modern Java21 process is an internal in-memory Hop engine;
 * it does not have direct access to project storage or Hop Web sessions.
 */
let embeddedInWorkbench = false;

export function enableWorkbenchApi(): void {
  embeddedInWorkbench = true;
}

function parseFilePath(raw: unknown): { project: string; file: string } | null {
  if (typeof raw !== "string" || !raw.includes("/")) return null;
  const index = raw.indexOf("/");
  const project = raw.slice(0, index);
  const file = raw.slice(index + 1);
  if (project.length === 0 || project.length > 160 ||
      !/^[\p{L}\p{N}._-]+$/u.test(project) ||
      project === "." || project === ".." ||
      !file.toLowerCase().endsWith(".hpl") || file.startsWith("/") ||
      file.includes("\\") || file.split("/").some(p => !p || p === "." || p === "..")) return null;
  return { project, file };
}

function workbenchToken(): string | null {
  const cookie = typeof document === "undefined" ? "" : document.cookie;
  const match = cookie.split(/;\s*/).find(part => part.startsWith("Admin-Token="));
  if (!match) return null;
  try { return decodeURIComponent(match.substring("Admin-Token=".length)); }
  catch { return null; }
}

function jsonError(code: string, status: number): Response {
  return new Response(JSON.stringify({ code }), {
    status, headers: { "content-type": "application/json" },
  });
}

async function toWorkbench(route: string, payload: object, signal?: AbortSignal | null): Promise<Response> {
  const token = workbenchToken();
  if (!token) return jsonError("workbench_login_required", 401);
  const response = await window.fetch("/api/workbench/modern-editor/" + route, {
    method: "POST",
    credentials: "same-origin",
    headers: {
      Authorization: "Bearer " + token,
      "content-type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(payload),
    signal,
  });
  if (response.ok && !response.headers.get("content-type")?.includes("application/json")) {
    return jsonError("unexpected_backend_response", 502);
  }
  // RuoYi security middleware replies HTTP 200 with JSON {code:401}.
  // Never interpret this envelope as a loaded Hop graph.
  if (response.ok) {
    const decoded: unknown = await response.clone().json().catch(() => null);
    if (decoded && typeof decoded === "object" && "code" in decoded) {
      const code = (decoded as { code?: unknown }).code;
      if (code === 401) return jsonError("workbench_login_required", 401);
      if (code === 403) return jsonError("workbench_access_denied", 403);
      if (typeof code === "number" && code >= 400) return jsonError("workbench_request_rejected", 403);
      return jsonError("unexpected_wrapped_response", 502);
    }
  }
  return response;
}

export async function workbenchFetch(input: string, init: RequestInit = {}): Promise<Response> {
  if (!embeddedInWorkbench || !input.startsWith("/api/pipelines/")) {
    return window.fetch(input, init);
  }

  if (input === "/api/pipelines/open" && init.method?.toUpperCase() === "POST") {
    let payload: { path?: unknown };
    try { payload = JSON.parse(String(init.body ?? "{}")); }
    catch { return jsonError("invalid_request",400); }
    const selected = parseFilePath(payload.path);
    if (!selected) return jsonError("invalid_project_or_file",400);
    return toWorkbench("open",selected,init.signal);
  }

  if (/^\/api\/pipelines\/[^/]+\/save$/.test(input) && init.method?.toUpperCase() === "POST") {
    let payload: { path?: unknown; expectedRevision?: unknown; nodes?: unknown; edges?: unknown };
    try { payload = JSON.parse(String(init.body ?? "{}")); }
    catch { return jsonError("invalid_request",400); }
    const selected = parseFilePath(payload.path);
    if (!selected || typeof payload.expectedRevision !== "string" ||
        !/^[0-9a-f]{64}$/.test(payload.expectedRevision) ||
        !Array.isArray(payload.nodes) || !Array.isArray(payload.edges))
      return jsonError("invalid_save_request",400);
    return toWorkbench("save",{
      ...selected, expectedRevision:payload.expectedRevision,
      nodes:payload.nodes, edges:payload.edges,
    },init.signal);
  }

  return jsonError("editing_operation_not_available",501);
}