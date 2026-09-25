import type { HopGraphDocument } from "@hop-modern/contracts";

export type GraphSource =
  | { kind: "sample"; reason?: string }
  | { kind: "loading"; path: string }
  | { kind: "server"; path: string };

export async function openPipelineGraph(path: string, signal?: AbortSignal): Promise<HopGraphDocument> {
  const response = await fetch("/api/pipelines/open", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ path }),
    signal,
  });

  if (!response.ok) {
    const detail = await response.text().catch(() => "");
    throw new Error(`Pipeline open failed (${response.status})${detail ? `: ${detail}` : ""}`);
  }

  return (await response.json()) as HopGraphDocument;
}
