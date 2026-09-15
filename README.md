# Media Compare

Media Compare is an early-stage application for media comparison workflows. The repository contains a working full-stack scaffold, the first SQLite persistence foundation, REST APIs for Sources and scan requests, a durable ScanRun-to-Job handoff, and the first synchronous filesystem discovery execution. Reconciliation and comparison workflows have not yet been implemented.

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
- The Vite development server proxies `/api` requests to the backend.

The SQLite database is created locally at `backend/data/media-compare.db` when the backend is run from `backend/`. Local database files are ignored by Git and are not committed.

## Status

The baseline frontend/backend connection, V1 persistence foundation, Source API, scan-request API, durable execution-state handoff, and manually invoked DISCOVERY stage are working. Discovery recursively observes regular files in registered Source roots and prepares a pending RECONCILIATION stage; it does not hash content or mark missing files. See [`docs/STATUS.md`](docs/STATUS.md) for the current handoff state.
