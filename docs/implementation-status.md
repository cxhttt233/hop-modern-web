# Implementation Status

Updated: 2026-09-24

## Repository state

Standalone product repository initialized and validated.

Green bootstrap evidence:
- product HEAD: `a095b71e8367050f06a15f403e7a4681e307a9b4`
- GitHub Actions run: `35947142666`
- frontend: TypeScript typecheck + Vite build **green**
- server: pinned Hop baseline `f7c2694a0b66a151eb244a7e9c11beff46b18421`, `lib + core` install + standalone server tests **green**

## Product path

`real .hpl -> Graph JSON -> React Flow -> semantic edit -> React config/metadata -> save/reload`

The core path must not enter RAP `/ui`.

## Ready assets

- React + TypeScript + React Flow product shell.
- Shared Graph/Config TypeScript contracts.
- Migrated HTTP-session-backed Hop scope with tests.
- Apache-2.0 LICENSE/NOTICE and preserved Hop attribution.
- Product CI validates frontend and the thin Java adapter against a pinned Hop source baseline.
- Hop fork retains SWT-free Pipeline commands/undo.
- Hop fork retains Config JSON transport work.

## Repository boundary

Standalone product repository contains only:
- `app/`
- `contracts/`
- `server/`
- `e2e/`
- `docs/`
- product CI

It does not contain copied Hop `core/`, `engine/`, `ui/`, `rap/`, `plugins/`, the old Maven `web/` tree, or the temporary root `hop-web-api`.

## Worker branches

Product branches created from the green bootstrap:
- `work/task-1-server`
- `work/task-3-config`
- `work/task-4-react`

Engine-side Task 2/3 patches continue in the existing `cxhttt233/hop` worker branches.

## Deferred assets

Execution registry/event/log/metrics work remains in the Hop fork branches and is not on the current critical path.

## Next integration targets

1. Graph projection contract from real `PipelineMeta`.
2. document open/save adapter.
3. React Flow move/add/delete/connect command bridge.
4. Table Input React config with MetadataPicker + Monaco.
5. Database Connection metadata editor.
