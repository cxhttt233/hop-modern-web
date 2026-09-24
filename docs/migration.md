# Migration Inventory

## Migrated now

- `WebSessionScope<T>` and its isolation/lifecycle tests from Task 1, adapted into `server/`.
- Product architecture/context, rewritten for the standalone repository.
- A minimal React + React Flow shell and shared Graph/Config contracts.

## Kept in the Hop fork

- `PipelineEditor` and `PipelineUndoApplier` from Task 2.
- `ConfigJsonSerializer` and Hop-aware metadata/config semantics from Task 3.
- future Graph Projection helpers only when they genuinely belong in engine/core.

These are engine assets, not product-source migration candidates.

## Deferred, not copied yet

Task 4 execution assets:

- ExecutionRegistry
- ExecutionEventBuffer
- ExecutionEvent / log / metrics publishers
- startup/completion lifecycle tests

They were validated in the old PoC but are intentionally deferred until the editor vertical slice needs execution.

## Explicitly not migrated

- full Hop `core/`
- full Hop `engine/`
- `ui/`
- `rap/`
- `plugins/`
- old `web/` Maven parent layout
- root experimental `hop-web-api`

The standalone repository consumes/pins Hop instead of becoming another Hop source tree.
