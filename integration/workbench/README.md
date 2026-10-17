# Workbench integration for the Apache Hop Modern editor

This branch tracks the canonical Modern Web upstream `main`. Merge upstream
changes into `integration/workbench`, then run frontend tests and isolated
Hop engine tests before releasing to Workbench.

## Responsibilities

* Workbench product UI: file picker, project management, SSO and save.
* Workbench Spring API: user/project authorization, path checks, SHA-256
  concurrency control, bounded backups and atomic `.hpl` file replacement.
* Existing independent Modern Java21 process: **memory-only** Hop native
  parse / edit / serialize engine protected by a server-only shared token.
* Apache Hop Core / Engine / Plugins: native metadata and ETL engine.
* Hop Web Classic (RAP/Tomcat) is a separate optional UI, not a dependency
  of the Workbench editor or save route.

`app/src/workbenchTransport.ts` is the sole product API adapter. It must
not invoke `/hop-ui` routes and must normalize RuoYi's HTTP 200 + JSON
code 401 error envelope. All unknown mutations fail closed.
The `WorkbenchElement` remains the Vue/React boundary and exposes
open/save commands without copying graph state into Vue.

## Deployment

Existing process only: do not launch a second Modern Java backend. The
already-running internal Java21 process provides in-memory graph operations
behind a private Docker gateway. Workbench's Java17 backend owns file I/O.
Never inject Workbench classes into Hop Web's `WEB-INF/classes`; upgrading
a Hop Web Docker image must not require rebuilding Workbench.

Real credentials and private environment variables are kept outside git.
The Workbench project volume requires minimal group-scoped write permission
and a dedicated low-privilege editor group; never grant access to Hop's
private config or credential files. Keep backups and test rollback.

## Remaining work

Native graph edit/save and re-open have been tested in isolation. Complete
component-specific configuration form read/write and authenticated browser
end-to-end tests before claiming full classic Hop Web parity.
