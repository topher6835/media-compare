# Media Compare

Media Compare is an early-stage application for media comparison workflows. V6 moves Source/FileEntry relationships and presence to SourceMembership and routes new indexing through a four-stage v3 SCAN. Historical V5 FileEntries remain separate and unresolved after migration; trusted overlapping Sources can share one resolved physical FileEntry. Broader comparison and cleanup workflows remain deferred.

## Stack

- Backend: Java 21, Spring Boot 4.1.1, Maven, Spring JDBC, Flyway, and SQLite
- Frontend: React, TypeScript, Vite, React Router, and ESLint
- Integration direction: Java NIO, FFmpeg/ffprobe, REST APIs, Server-Sent Events (SSE), and pluggable local/cloud AI providers

## Repository Structure

- `backend/` — Spring Boot application, Maven wrapper, local data directory, and Flyway migration directory
- `frontend/` — React and TypeScript application built with Vite
- `docs/` — architecture, data model, decisions, and current project status
- `AGENTS.md` — repository operating guidance for AI agents

## Prerequisites

- Java 21
- Node.js and npm

Maven does not need to be installed separately because the backend includes Maven wrappers for macOS/Linux and Windows. The bounded ffprobe runner exists but is not yet connected to durable video analysis, so ffprobe is not required for the current indexing and image-metadata workflows. When the runner is exercised, it uses an absolute path supplied through the optional `media-compare.ffprobe.executable` property or, when the property is absent, the `ffprobe` command from PATH.

## Run Locally

Start the backend from `backend/`.

macOS/Linux:

```sh
./mvnw spring-boot:run
```

Windows:

```bat
mvnw.cmd spring-boot:run
```

Start the frontend in a separate terminal from `frontend/`:

```sh
npm install
npm run dev
```

During development:

- Frontend: `http://localhost:5173`
- Backend: `http://localhost:8080`
- Health endpoint: `http://localhost:8080/api/health`
- Source endpoints: `http://localhost:8080/api/sources`
- Source preparation: `POST http://localhost:8080/api/sources/{id}/prepare`
- Scan-request endpoints: `http://localhost:8080/api/scan-runs`
- New indexing endpoint: `POST http://localhost:8080/api/indexing-runs`
- Indexing detail: `GET http://localhost:8080/api/indexing-runs/{id}`
- Content assignment endpoint: `POST http://localhost:8080/api/scan-runs/{id}/content-assignment`
- Content hashing endpoint: `POST http://localhost:8080/api/scan-runs/{id}/content-hashing`
- Exact duplicate endpoints: `GET http://localhost:8080/api/exact-duplicate-groups`, `GET http://localhost:8080/api/exact-duplicate-groups/filter-options`, and `GET http://localhost:8080/api/exact-duplicate-groups/{digestHex}`. List and detail reads accept repeated `fileCategory` and `extension` query parameters.
- Source management and indexing frontend: `http://localhost:5173/sources`
- Exact duplicate frontend: `http://localhost:5173/duplicates`
- The Vite development server proxies `/api` requests to the backend.

The V6 schema and v3 indexing cutover are implemented. SourceMembership owns the Source/FileEntry relationship and presence; trusted overlapping Sources can share one source-independent FileEntry. Historical v1/v2 indexing executions remain readable, and new indexing uses v3. On supported local macOS/APFS storage, register a folder on `/sources`, select **Prepare Source**, then select **Analyze Source** once it shows Ready. Registration stores the path without checking that it exists; preparation checks the filesystem and binds the Source to an accepted logical LocationContext. See [`docs/STATUS.md`](docs/STATUS.md) for current validation results.

The SQLite database is created locally at `backend/data/media-compare.db` when the backend is run from `backend/`. Local database files are ignored by Git and are not committed.

The configured `spring.datasource.url` also determines the catalog's `.lock` sidecar. A Java NIO OS lock prevents a second backend from using the same local catalog; do not delete the lock file to try to release ownership. Locking precedes Flyway and startup recovery. Keep the catalog on a local filesystem and use one canonical catalog location, not hard-link aliases.

## Source preparation API

`POST /api/sources/{id}/prepare` has no request body. It returns the Source with `preparationState: READY` after successful first-time preparation, and returns the current Source unchanged if it was already ready. Source registration returns `PREPARATION_REQUIRED` without probing the path. A previously bound, currently unbound Source reports `REBIND_REQUIRED`; preparation returns `409` for that state because rebinding is a separate operation. Unknown Sources return `404`. Unavailable or unsupported local storage and uncertain APFS evidence return bounded `422` codes; probe infrastructure errors return `503`. The endpoint is currently for local macOS/APFS folders only.

## Background indexing API

`POST /api/indexing-runs` accepts `{"requestKey":"client-generated UUID","sourceIds":[1]}`. Keys use canonical UUID text (case-insensitive input, lowercase storage); Source IDs must be distinct positive integers, with 1–1,000 Sources per request. New acceptance returns `202` and `Location: /api/indexing-runs/{scanRunId}`. An exact replay, regardless of Source order or terminal state, returns `200` with the same execution and never resubmits it. Reusing a key for another Source set returns `409`. A later attempt after failure needs a new key.

ScanRun, ScanRunSource selections, v3 Job, and DISCOVERY stage commit atomically before background submission. Every requested Source must already have current supported local macOS/APFS binding and accepted LocationContext authority. Unbound/unsupported Sources and Windows hosts cannot start v3 scans. The frontend explicitly prepares first-time Sources; rebinding and automatic remount recognition remain deferred. Another active SCAN returns `409` without leaving an orphan request. Scheduling rejection returns `503` but retains a failed attempt that can be read or replayed.

`GET /api/indexing-runs/{scanRunId}` returns durable stage/progress/timestamp state and typed final assignment/hash counts. `GET /api/indexing-runs/source-status` returns the active summary plus the latest v2 or v3 run for every registered Source in one response. Both GETs are read-only and send `Cache-Control: no-store`. `completedWithIssues` is derived from completed hashing counts, not stored as a Job status. React `/sources` starts one v3 run and polls this durable state; SSE, cancellation, retry/resume, and multi-Source UI remain unimplemented.

## Status

V6 migration, membership publication, trusted v3 discovery/reconciliation, downstream candidate guards, duplicate physical-copy counting, and historical execution recovery are implemented. Focused V6 checks and the full validation suite pass, including a four-stage v3 execution with deterministic host observations. See [`docs/STATUS.md`](docs/STATUS.md) for current validation results and the next lifecycle step.
