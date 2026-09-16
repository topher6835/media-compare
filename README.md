# Media Compare

Media Compare is an early-stage application for media comparison workflows. The repository contains a working full-stack scaffold, the first SQLite persistence foundation, REST APIs for Sources and scan requests, a durable ScanRun-to-Job handoff, synchronous DISCOVERY and RECONCILIATION execution, a database-only ContentRecord-assignment pass, exact SHA-256 hashing for assigned content, and read-only exact duplicate reporting. Broader comparison and cleanup workflows have not yet been implemented.

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
- Exact duplicate endpoints: `GET http://localhost:8080/api/exact-duplicate-groups` and `GET http://localhost:8080/api/exact-duplicate-groups/{digestHex}`
- The Vite development server proxies `/api` requests to the backend.

The SQLite database is created locally at `backend/data/media-compare.db` when the backend is run from `backend/`. Local database files are ignored by Git and are not committed.

## Status

The baseline frontend/backend connection, V1 persistence foundation, Source API, scan-request API, durable execution-state handoff, and manually invoked DISCOVERY and RECONCILIATION stages are working. Discovery observes regular files; reconciliation consumes those durable observations, safely marks unseen occurrences missing, and completes the ScanRun. A repeatable database-only command then assigns one new ContentRecord to each still-unassigned occurrence observed by that completed scan. Exact hashing streams safe candidates with SHA-256 and publishes reusable `AnalysisRecord`/`ContentHash` artifacts. Exact duplicate APIs derive groups of two or more ContentRecords from those trusted artifacts without materializing groups or merging ContentRecords. See [`docs/STATUS.md`](docs/STATUS.md) for the current handoff state.
