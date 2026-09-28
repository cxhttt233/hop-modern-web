import type { HopGraphDocument } from "@hop-modern/contracts";
import type { ConfigDescriptor } from "./GenericConfigPanel";

export type GraphSource =
  | { kind: "sample"; reason?: string }
  | { kind: "loading"; path: string }
  | { kind: "server"; path: string };

async function graphResponse(response: Response, action: string): Promise<HopGraphDocument> {
  if (!response.ok) {
    const detail = await response.text().catch(() => "");
    throw new Error(`${action} failed (${response.status})${detail ? `: ${detail}` : ""}`);
  }
  return (await response.json()) as HopGraphDocument;
}

export async function openPipelineGraph(path: string, signal?: AbortSignal): Promise<HopGraphDocument> {
  const response = await fetch("/api/pipelines/open", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ path }),
    signal,
  });
  return graphResponse(response, "Pipeline open");
}

export async function movePipelineTransforms(documentId: string, nodeIds: string[], dx: number, dy: number): Promise<HopGraphDocument> {
  const response = await fetch(`/api/pipelines/${encodeURIComponent(documentId)}/move`, {
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
  const response = await fetch(`/api/pipelines/${encodeURIComponent(documentId)}/${action}`, {
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

export async function readPipelineTransformConfig(documentId: string, nodeId: string): Promise<TransformConfigDocument> {
  const response = await fetch(`/api/pipelines/${encodeURIComponent(documentId)}/config/read`, {
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
  const response = await fetch(`/api/pipelines/${encodeURIComponent(documentId)}/config/write`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ nodeId, config }),
  });
  return configResponse(response, "Transform config write");
}
