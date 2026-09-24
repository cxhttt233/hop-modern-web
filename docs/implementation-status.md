# Implementation Status

Updated: 2026-09-24

## Repository state

Standalone product repository initialized.

## Product path

`real .hpl -> Graph JSON -> React Flow -> semantic edit -> React config/metadata -> save/reload`

## Ready assets

- React + TypeScript + React Flow product shell.
- Shared Graph/Config TypeScript contracts.
- Migrated HTTP-session-backed Hop scope with tests.
- Hop fork retains SWT-free Pipeline commands/undo.
- Hop fork retains Config JSON transport work.

## Deferred assets

Execution registry/event/log/metrics work remains in the Hop fork branches and is not on the current critical path.

## Next integration targets

1. Graph projection contract from real `PipelineMeta`.
2. document open/save adapter.
3. React Flow move/add/delete/connect command bridge.
4. Table Input React config with MetadataPicker + Monaco.
5. Database Connection metadata editor.
