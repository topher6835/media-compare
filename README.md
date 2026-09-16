# Media Compare

Media Compare is an early-stage application for media comparison workflows. The repository contains a working full-stack scaffold, a SQLite persistence foundation, REST APIs for Sources and scan requests, a durable ScanRun-to-Job handoff, synchronous DISCOVERY and RECONCILIATION execution, a database-only ContentRecord-assignment pass, exact SHA-256 hashing for assigned content, and read-only exact duplicate browsing with backend file-category and extension filtering. The frontend can register and browse Sources, launch that existing indexing pipeline, follow its stage and count updates, and continue to exact duplicate results. Broader comparison and cleanup workflows have not yet been implemented.

## Stack

- Backend: Java 21, Spring Boot 4.1.1, Maven, Spring JDBC, Flyway, and SQLite
- Frontend: React, TypeScript, Vite, React Router, and ESLint
- Planned integration direction: Java NIO, FFmpeg/ffprobe, REST APIs, Server-Sent Events (SSE), and pluggable local/cloud AI providers

## Repository Structure

- `backend/` — Spring Boot application, Maven wrapper, local data directory, and Flyway migration directory
- `frontend/` — React and TypeScript application built with Vite
- `docs/` — architecture, data model, decisions, and current project status
- `AGENTS.md` — repository operating guidance for AI agents

## Prerequisites

- Java 21
- Node.js and npm

Maven does not need to be installed separately because the backend includes Maven wrappers for macOS/Linux and Windows. FFmpeg and ffprobe are planned for future media features but are not used by the current scaffold.

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

## Status

The baseline frontend/backend connection, persistence foundation, Source API, scan-request API, durable execution-state handoff, and synchronous DISCOVERY and RECONCILIATION stages are working. The `/sources` frontend registers local backend paths and orchestrates the existing ScanRun, execution handoff, discovery, reconciliation, ContentRecord assignment, and exact-hashing endpoints in sequence. Current orchestration is browser-local: keep the page open while synchronous requests run, because refresh cannot reconstruct or resume that sequence. A successful hashing request can still report skipped or failed candidates; the UI presents that as completed with issues rather than a failed run, while usable exact-hashed content remains available. The frontend performs no filesystem mutation. Exact duplicate APIs derive groups of two or more ContentRecords from trusted artifacts without materializing groups or merging ContentRecords, and the read-only `/duplicates` frontend provides URL-backed File Type and Extension filtering with whole-group context. Background workers/SSE, similarity analysis, AI, review metadata, and cleanup remain deferred. See [`docs/STATUS.md`](docs/STATUS.md) for the current handoff state.
