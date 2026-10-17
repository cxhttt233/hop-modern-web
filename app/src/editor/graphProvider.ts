import type { HopGraphDocument } from "@hop-modern/contracts";
import type { ConfigDescriptor } from "./GenericConfigPanel";
import { workbenchFetch } from "../workbenchTransport";

export type GraphSource =
  | { kind: "sample"; reason?: string }
  | { kind: "loading"; path: string }
  | { kind: "server"; path: string };

async function graphResponse(response: Response, action: string): Promise<HopGraphDocument> {
  if (!response.ok) {
    if (response.status === 401) throw new Error("Workbench 登录会话已过期。请重新登录工作台后再打开流程。");
    if (response.status === 403) throw new Error("当前 Workbench 账号缺少流程访问权限，请检查用户角色。");
    if (response.status === 404) throw new Error("找不到该流程文件，请刷新项目文件列表。");
    if (response.status === 400) throw new Error("项目名称或文件路径无效。");
    const detail = await response.text().catch(() => "");
    throw new Error(action + " failed (" + response.status + ")" + (detail ? ": " + detail : ""));
  }
  return (await response.json()) as HopGraphDocument;
}

export async function openPipelineGraph(path: string, signal?: AbortSignal): Promise<HopGraphDocument> {
  const response = await workbenchFetch("/api/pipelines/open", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ path }),
    signal,
  });
  return graphResponse(response, "Pipeline open");
}

export async function movePipelineTransforms(documentId: string, nodeIds: string[], dx: number, dy: number): Promise<HopGraphDocument> {
  const response = await workbenchFetch(`/api/pipelines/${encodeURIComponent(documentId)}/move`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ nodeIds, dx: Math.round(dx), dy: Math.round(dy) }),
  });
  return graphResponse(response, "Transform move");
}

export interface AddTransformRequest {
  nodeId: string;
  pluginId: string;
  x: number;
  y: number;
}

async function editPipelineGraph(documentId: string, action: string, body?: unknown): Promise<HopGraphDocument> {
  const response = await workbenchFetch(`/api/pipelines/${encodeURIComponent(documentId)}/${action}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  return graphResponse(response, `Pipeline ${action}`);
}

export function addPipelineTransform(documentId: string, request: AddTransformRequest): Promise<HopGraphDocument> {
  return editPipelineGraph(documentId, "edit/add", request);
}

export function deletePipelineTransform(documentId: string, nodeId: string): Promise<HopGraphDocument> {
  return editPipelineGraph(documentId, "edit/delete", { nodeId });
}

export function connectPipelineTransforms(documentId: string, from: string, to: string): Promise<HopGraphDocument> {
  return editPipelineGraph(documentId, "edit/connect", { from, to });
}

export function undoPipelineEdit(documentId: string): Promise<HopGraphDocument> {
  return editPipelineGraph(documentId, "edit/undo");
}

export function redoPipelineEdit(documentId: string): Promise<HopGraphDocument> {
  return editPipelineGraph(documentId, "edit/redo");
}

export function savePipeline(documentId: string): Promise<HopGraphDocument> {
  return editPipelineGraph(documentId, "save");
}

/** Workbench save is owned by its authenticated product API, backed by the independent Modern Hop engine. */
export async function saveWorkbenchGraph(
  path: string, document: HopGraphDocument,
  configEdits: Record<string, Record<string, unknown>> = {},
): Promise<HopGraphDocument> {
  const response = await workbenchFetch(`/api/pipelines/${encodeURIComponent(document.id)}/save`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      path, expectedRevision: document.revision, nodes: document.nodes,
      edges: document.edges, configEdits,
    }),
  });
  if (response.status === 409) throw new Error("该流程已被其他窗口或 Hop Web 修改。请重新打开并核对，当前改动未覆盖原文件。");
  if (response.status === 403) throw new Error("当前 Workbench 账号缺少流程编辑权限，请检查工作台角色。");
  return graphResponse(response, "Pipeline save");
}

export interface TransformConfigDocument {
  nodeId: string;
  pluginId: string;
  config: Record<string, unknown>;
  descriptor: ConfigDescriptor;
}

async function configResponse(response: Response, action: string): Promise<TransformConfigDocument> {
  if (!response.ok) {
    const detail = await response.text().catch(() => "");
    throw new Error(`${action} failed (${response.status})${detail ? `: ${detail}` : ""}`);
  }
  return (await response.json()) as TransformConfigDocument;
}

export async function readWorkbenchTransformConfig(
  path: string, graph: HopGraphDocument, nodeId: string,
): Promise<TransformConfigDocument> {
  const response = await workbenchFetch(`/api/pipelines/${encodeURIComponent(graph.id)}/config/read`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      path, expectedRevision: graph.revision,
      nodes: graph.nodes, edges: graph.edges, nodeId,
    }),
  });
  return configResponse(response, "Transform config read");
}

export async function readPipelineTransformConfig(documentId: string, nodeId: string): Promise<TransformConfigDocument> {
  const response = await workbenchFetch(`/api/pipelines/${encodeURIComponent(documentId)}/config/read`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ nodeId }),
  });
  return configResponse(response, "Transform config read");
}

export async function writePipelineTransformConfig(
  documentId: string,
  nodeId: string,
  config: Record<string, unknown>,
): Promise<TransformConfigDocument> {
  const response = await workbenchFetch(`/api/pipelines/${encodeURIComponent(documentId)}/config/write`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ nodeId, config }),
  });
  return configResponse(response, "Transform config write");
}