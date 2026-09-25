import type { HopGraphDocument } from "@hop-modern/contracts";

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


export interface TransformConfigDocument {
  nodeId: string;
  pluginId: string;
  config: Record<string, unknown>;
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
