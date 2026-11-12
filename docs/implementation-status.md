# Implementation Status

Updated: 2026-11-12

## Verified non-production integration — 2026-11-12

The separate Modern Web repository is the product authority; `cxhttt233/hop` remains the pinned Apache Hop engine source. The protected production execution path is NOT released.

- **Current pre-integration staging checkpoint:** `integration/product-mainline-20261112` @ `68abcbddadf219a3f20b9257991e2da199abbd18`; [GitHub Actions 43209608707](https://github.com/cxhttt233/hop-modern-web/actions/runs/43209608707) completed **3/3 SUCCESS** (frontend including native contracts tests, server including anonymous-execution HTTP negative test, and real-Hop Chromium editor E2E).
- **Native editor evidence:** real `.hpl` open -> Graph JSON -> React Flow interactions -> semantic edits -> config draft/save -> Hop XML disk reopen, with no RAP `/ui` navigation. This establishes the tested preview slices, not full metadata coverage or unified release readiness.
- **This Task 5 admission candidate (separate draft branch):** port the *specific* Task 3 LIST-container alias guard and its contract test from `379d176a`, plus Task 4's post-reload recent-file/no-implicit-open browser proof from `c405a114`. Source runs [43226952704](https://github.com/cxhttt233/hop-modern-web/actions/runs/43226952704) and [43227848564](https://github.com/cxhttt233/hop-modern-web/actions/runs/43227848564) are green. The candidate itself requires its own exact-head CI before integration; do not infer success from source runs.
- **Still incomplete:** Task 3/4 full typed native LIST/OBJECT editing and metadata picker consumption; Task 1 trusted principal, authorization, per-session ownership and Origin/CSRF for protected execution; Task 2 queued Workflow start-time correctness and terminal cleanup (server CI red). Fresh independent review plus complete negative security tests remain mandatory for main/production release.
- **Separation:** safe preview development may proceed without admitting privileged execution. No changes to `main`, deployments, the engine fork, or hourly schedules follow from this checkpoint.

### Historical bootstrap record (2026-09-24)

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
