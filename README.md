# Media Compare

Media Compare is an early-stage application for media comparison workflows. The repository contains a working full-stack scaffold, a SQLite persistence foundation, REST APIs for Sources and scan requests, a durable ScanRun-to-Job handoff, synchronous DISCOVERY and RECONCILIATION execution, a database-only ContentRecord-assignment pass, exact SHA-256 hashing for assigned content, and read-only exact duplicate browsing with backend file-category and extension filtering. The frontend can register and browse Sources, launch that existing indexing pipeline, follow its stage and count updates, and continue to exact duplicate results. Broader comparison and cleanup workflows have not yet been implemented.

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
- Scan-request endpoints: `http://localhost:8080/api/scan-runs`
- Scan discovery endpoint: `POST http://localhost:8080/api/scan-runs/{id}/execution/discovery`
- Scan reconciliation endpoint: `POST http://localhost:8080/api/scan-runs/{id}/execution/reconciliation`
- Content assignment endpoint: `POST http://localhost:8080/api/scan-runs/{id}/content-assignment`
- Content hashing endpoint: `POST http://localhost:8080/api/scan-runs/{id}/content-hashing`
- Exact duplicate endpoints: `GET http://localhost:8080/api/exact-duplicate-groups`, `GET http://localhost:8080/api/exact-duplicate-groups/filter-options`, and `GET http://localhost:8080/api/exact-duplicate-groups/{digestHex}`. List and detail reads accept repeated `fileCategory` and `extension` query parameters.
- Source management and indexing frontend: `http://localhost:5173/sources`
- Exact duplicate frontend: `http://localhost:5173/duplicates`
- The Vite development server proxies `/api` requests to the backend.

The SQLite database is created locally at `backend/data/media-compare.db` when the backend is run from `backend/`. Local database files are ignored by Git and are not committed.

The configured `spring.datasource.url` also determines the catalog's `.lock` sidecar. A Java NIO OS lock prevents a second backend from using the same local catalog; do not delete the lock file to try to release ownership. Locking precedes Flyway and startup recovery. Keep the catalog on a local filesystem and use one canonical catalog location, not hard-link aliases.

## Background indexing API

`POST /api/indexing-runs` accepts `{"requestKey":"client-generated UUID","sourceIds":[1]}`. Keys use canonical UUID text (case-insensitive input, lowercase storage); Source IDs must be distinct positive integers, with 1–1,000 Sources per request. New acceptance returns `202` and `Location: /api/indexing-runs/{scanRunId}`. An exact replay, regardless of Source order or terminal state, returns `200` with the same execution and never resubmits it. Reusing a key for another Source set returns `409`. A later attempt after failure needs a new key.

ScanRun, Source membership, v2 Job, and DISCOVERY stage commit atomically before background submission. Another active v2 run returns `409` without leaving an orphan request. Scheduling rejection returns `503` but retains a failed attempt that can be read or replayed.

`GET /api/indexing-runs/{scanRunId}` returns durable stage/progress/timestamp state and typed final assignment/hash counts. `GET /api/indexing-runs/source-status` returns the active summary plus the latest v2 run for every registered Source in one response. Both GETs are read-only and send `Cache-Control: no-store`. `completedWithIssues` is derived from completed hashing counts, not stored as a Job status. React `/sources` now starts one v2 run and polls this durable state; SSE, cancellation, retry/resume, and multi-Source UI remain unimplemented.

## Status

The baseline frontend/backend connection, persistence foundation, Source API, scan-request API, and indexing stages are working. The backend has a public background version-2 lifecycle in which one durable SCAN Job owns DISCOVERY, RECONCILIATION, CONTENT_ASSIGNMENT, and CONTENT_HASHING, including typed final assignment/hash summaries and terminal failure state. React `/sources` creates a client UUID, starts one durable run, reconstructs active/latest state on load, and polls only while a run is active. A bounded single worker runs only after execution creation commits. Startup fails interrupted active v2 attempts without resuming them, preserving committed catalog work. Shutdown allows up to 30 seconds before interrupting the worker. Existing version-1 endpoints remain available but are no longer used by the active `/sources` flow. Successful hashing may report skipped or failed candidates and still complete with issues, leaving usable exact-hashed content available. Exact duplicate APIs derive groups from trusted artifacts without materializing or merging ContentRecords. SSE, similarity analysis, AI, review metadata, and cleanup remain deferred. See [`docs/STATUS.md`](docs/STATUS.md) for the current handoff state.
