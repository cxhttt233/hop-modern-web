# Product Architecture

## Goal

Replace Apache Hop SWT/RAP presentation with a native React browser experience while preserving the Java engine, metadata semantics, plugins, and `.hpl/.hwf` compatibility.

## Primary vertical slice

`real .hpl -> Graph JSON -> React Flow -> semantic edit -> React config/metadata -> save/reload`

The core path must not instantiate or enter RAP `/ui`.

## Ownership boundary

### This repository

- React application and browser state
- React Flow canvas
- config and metadata components
- HTTP/JAX-RS adapter
- session/document registries specific to the product
- browser E2E and product CI

### `cxhttt233/hop`

- Pipeline/Workflow engine semantics
- graph projection helpers that truly belong in engine
- semantic command/undo primitives
- Config JSON / metadata semantics that belong in core
- `.hpl/.hwf` compatibility tests

## Canvas

Primary rendering is Graph JSON -> React Flow. Existing Hop SVG renderers remain compatibility/reference tools, not the product canvas.

## Current priority

Editor vertical slice first. Execution/SSE/Metrics work from the earlier PoC is preserved as deferred evidence, but does not drive current development.
