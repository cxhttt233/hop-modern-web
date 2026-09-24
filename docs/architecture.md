# Hop Modern Web Architecture

This document is the technical authority for the standalone product repository.

## 1. Product goal

Preserve Apache Hop's Java engine, metadata model, plugin model and `.hpl/.hwf` compatibility while replacing SWT/RAP presentation with a native browser UI.

The critical product path is:

`real .hpl -> Graph JSON -> React Flow -> semantic edit -> React config/metadata -> save/reload`

The normal editor path must not enter RAP `/ui`.

## 2. Repository boundary

### `cxhttt233/hop-modern-web`

Owns:
- React + TypeScript application;
- React Flow canvas and browser UI state;
- shared browser/server contracts;
- thin HTTP/JAX-RS adapter;
- product-specific session/document registries;
- config/metadata browser components;
- browser E2E and product CI.

### `cxhttt233/hop`

Owns only changes that genuinely belong in Apache Hop engine/core:
- Pipeline/Workflow model semantics;
- semantic edit/undo primitives;
- Graph Projection helpers that require engine internals;
- Config JSON / metadata semantics that belong in core;
- `.hpl/.hwf` compatibility tests.

Never copy the full Hop `core/`, `engine/`, `ui/`, `rap/`, or `plugins/` trees into this repository.

## 3. Frontend

Fixed stack for Phase 0:
- React;
- TypeScript;
- React Flow;
- Vite.

Browser owns:
- selection;
- pan/zoom;
- panel layout;
- local form drafts;
- optimistic node movement;
- editor-only interaction state.

Java remains authoritative for Hop model semantics.

## 4. Canvas

Primary path:

`PipelineMeta / WorkflowMeta -> Graph Projection -> JSON contract -> React Flow`

Graph data must include enough information for browser rendering and editing:
- stable node id;
- name;
- node kind;
- plugin id/type;
- coordinates;
- enabled state;
- hops/edges;
- notes where applicable.

React Flow is the product canvas.

Existing Hop SVG renderers are retained only as:
- compatibility/reference output;
- regression comparison;
- fallback for semantics not yet represented in Graph JSON.

They are not the primary Phase 0 canvas.

## 5. Semantic editing

Do not reimplement Hop engine logic in TypeScript.

Browser actions map to semantic commands such as:
- add transform;
- move transform(s);
- delete transform;
- add/delete hop;
- enable/disable hop;
- undo/redo;
- save.

Server/engine owns `PipelineMeta`, `WorkflowMeta`, undo history and save semantics.

A command is not accepted as complete until save/reload compatibility is verified for the affected behavior.

## 6. Config and Metadata UI

The browser must replace SWT Dialog/MetadataEditor progressively.

Shared React controls should include:
- `VariableInput`;
- `EditableTable`;
- `MetadataPicker`;
- `MonacoEditor`;
- `VfsPicker`.

Configuration remains layered:

- **T0 generic schema form** — broad functional coverage from Hop metadata/GUI annotations.
- **T1 declarative layout** — tabs, groups, order, labels and conditional visibility.
- **T2 custom React UI** — complex transforms/actions requiring purpose-built interaction.

First Transform targets:
1. Table Input;
2. Select Values;
3. Sort Rows.

First Metadata target:
- Database Connection.

Transform dialogs and standalone Metadata editors should reuse the same React controls and server metadata contracts.

## 7. Server adapter

The standalone server is intentionally thin.

Use Java/Jakarta/JAX-RS style integration compatible with Hop; do not introduce a second business model or duplicate Hop execution logic.

Only add server/session/project/VFS/document capabilities when the active browser vertical slice consumes them.

Session-scoped Hop state must remain SWT/RWT-free.

## 8. Plugins

Hop plugin engine classes remain loaded by Hop.

The browser does not load SWT Dialog classes.

Long-term Web UI extension may use declarative schema/layout or plugin-provided browser assets, but plugin ecosystem work is not on the current critical path until the first editor slice works.

## 9. Execution

Previously validated ExecutionRegistry/EventBuffer/log/metrics work is a deferred asset.

Do not spend Phase 0 effort on:
- SSE reconnect polish;
- execution event retention refinements;
- metrics UI;
- execution history UX;

unless one of them directly blocks the editor vertical slice.

Execution returns to the critical path after real Pipeline editing/config/save works without RAP.

## 10. Phase 0 acceptance

The first product milestone requires all of the following:

1. open a real `.hpl`;
2. convert its real Hop model to Graph JSON;
3. render it in React Flow;
4. move/add/delete/connect using semantic commands;
5. undo/redo;
6. open at least one real Transform in native React config UI;
7. edit at least one real Metadata type in native React UI;
8. save;
9. reload and preserve Hop semantics;
10. complete the core workflow without entering RAP `/ui`.

A backend capability that does not shorten this path is normally deferred.

## 11. Validation

GitHub repository operations are the code source of truth.

GitHub Actions are the default build/test/E2E environment.

Required validation evolves toward:
- TypeScript typecheck/build;
- Java adapter unit tests;
- Hop engine compatibility tests;
- browser E2E;
- explicit no-RAP core-path assertion.

Line count and commit count are not success metrics.
