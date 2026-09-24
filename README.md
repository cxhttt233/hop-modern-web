# Hop Modern Web

A standalone modern browser UI for Apache Hop.

## Product goal

Keep Apache Hop's Java engine, metadata model, plugin model, and `.hpl/.hwf` compatibility while replacing SWT/RAP presentation with a native browser UI.

The first vertical slice is:

`real .hpl -> Graph JSON -> React Flow -> semantic edit -> React config/metadata -> save/reload`

The core path must not enter RAP `/ui`.

## Repository boundary

This repository owns product code:

- `app/` — React + TypeScript + React Flow
- `contracts/` — browser/server contracts
- `server/` — thin Hop adapter and session/document lifecycle
- `e2e/` — browser end-to-end tests
- `docs/` — product architecture and migration state
- `.github/workflows/` — build and validation

The engine fork remains at `cxhttt233/hop`. Do not copy the full Hop `core/`, `engine/`, `ui/`, `rap/`, or `plugins/` trees here.

## Development

```bash
npm install
npm run typecheck
npm run build
```

Server validation is performed by GitHub Actions against a pinned Hop commit.

## Status

Standalone repository initialized on 2026-09-24 after the product-first architecture reset.
