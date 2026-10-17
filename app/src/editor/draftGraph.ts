import type { HopGraphDocument, HopGraphNode, HopGraphEdge } from '@hop-modern/contracts'

/** In-memory Workbench drafting only. This module never writes to Hop Web. */
export function addDraftTransform(doc: HopGraphDocument, node: HopGraphNode): HopGraphDocument {
  if (node.kind !== 'transform' || !node.id || !node.pluginId) return doc
  if (!Number.isFinite(node.x) || !Number.isFinite(node.y)) return doc
  if (doc.nodes.some(item => item.id === node.id)) return doc
  return { ...doc, nodes: [...doc.nodes, node] }
}

export function moveDraftTransform(doc: HopGraphDocument, id: string, x: number, y: number): HopGraphDocument {
  if (!Number.isFinite(x) || !Number.isFinite(y)) return doc
  const existing = doc.nodes.find(item => item.id === id)
  if (!existing || (existing.x === x && existing.y === y)) return doc
  return { ...doc, nodes: doc.nodes.map(item => item.id === id ? { ...item, x, y } : item) }
}

export function renameDraftTransform(doc: HopGraphDocument, id: string, newName: string): HopGraphDocument {
  const name = newName.trim()
  if (!name || name.length > 180 || doc.nodes.some(item => item.name === name && item.id !== id)) return doc
  const existing = doc.nodes.find(item => item.id === id && item.kind === 'transform')
  if (!existing || existing.name === name) return doc
  return { ...doc, nodes: doc.nodes.map(item => item.id === id ? { ...item, name } : item) }
}

export function connectDraftTransforms(doc: HopGraphDocument, from: string, to: string): HopGraphDocument {
  if (from === to || !doc.nodes.some(item => item.id === from && item.kind === 'transform')
    || !doc.nodes.some(item => item.id === to && item.kind === 'transform')
    || doc.edges.some(item => item.source === from && item.target === to)) return doc
  let id = 'draft-hop-' + from + '-' + to
  let index = 1
  while (doc.edges.some(item => item.id === id)) id = 'draft-hop-' + from + '-' + to + '-' + index++
  const edge: HopGraphEdge = { id, source: from, target: to, enabled: true, hopType: 'normal' }
  return { ...doc, edges: [...doc.edges, edge] }
}

export function deleteDraftTransform(doc: HopGraphDocument, id: string): HopGraphDocument {
  if (!doc.nodes.some(item => item.id === id)) return doc
  return { ...doc, nodes: doc.nodes.filter(item => item.id !== id),
    edges: doc.edges.filter(item => item.source !== id && item.target !== id) }
}

export function validateDraftGraph(doc: HopGraphDocument): boolean {
  const ids = new Set(doc.nodes.map(node => node.id))
  if (ids.size !== doc.nodes.length) return false
  return doc.edges.every(edge => ids.has(edge.source) && ids.has(edge.target) && edge.source !== edge.target)
}