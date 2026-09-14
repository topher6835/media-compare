# Status

## Current State

Media Compare is a fresh v2 repository with a working full-stack baseline. The repository contains separate `backend/` and `frontend/` projects, but no real search, scanning, media-analysis, or comparison behavior has been implemented.

The backend is a Java 21 and Spring Boot 4.1.1 Maven application. It connects to a local SQLite database, starts Flyway, and exposes one health endpoint. The frontend is a React and TypeScript Vite application with React Router; its single route displays the result of the backend health request.

As of September 14, 2026, the backend test/build path, backend startup, database connection, Flyway startup, direct health endpoint, frontend production build, Vite development server, and proxied frontend-to-backend request have been exercised successfully.

The first architecture pass is documented. It establishes a modular-monolith direction and the conceptual catalog, identity, WorkingSet, ScanRun/Job, resumability, and versioned-analysis foundation. These are design decisions only: no application schema, product-feature Java boundaries, or product features have been implemented from them.

## Documentation

The durable documentation baseline now consists of:

- `AGENTS.md` — operating rules for future AI-agent work and documentation maintenance.
- `README.md` — developer-facing overview and local startup instructions.
- `docs/ARCHITECTURE.md` — implemented scaffold plus confirmed conceptual architecture and unresolved details.
- `docs/DATA_MODEL.md` — pre-schema conceptual model, persistence rules, and currently empty application schema.
- `docs/DECISIONS.md` — decisions already made and source-of-truth boundaries.
- `docs/STATUS.md` — current handoff state, working baseline, limitations, and next step.

## What Currently Works

- The backend Maven wrapper is available for macOS/Linux (`mvnw`) and Windows (`mvnw.cmd`).
- The backend compiles and its Spring context test passes on Java 21.
- Spring Boot starts successfully on the default port `8080`.
- SQLite connectivity succeeds using `jdbc:sqlite:data/media-compare.db`.
- Flyway starts, validates zero migrations, and reports the application schema as empty and current.
- `GET /api/health` returns plain text `ok` with HTTP 200.
- The frontend production build succeeds.
- React Router is wired through `BrowserRouter` and a `/` route.
- The Vite development server starts on its default port `5173`.
- Vite proxies `/api` to `http://localhost:8080`.
- A request to `/api/health` through the Vite server reaches the backend and returns `ok`.
- The frontend performs that health request and renders its status.
- The initial full-stack scaffold and documentation baseline are committed on `main`; the local `main` and `origin/main` references point to commit `aa0c1c6` (`Add project documentation and handoff docs`).
- `origin` uses `git@github-personal:topher6835/media-compare.git`, and repository-local Git identity is configured for `topher6835`.

## Known Limitations / Not Yet Implemented

- No product-level search or comparison behavior exists.
- No filesystem scanning exists.
- No Java NIO filesystem workflow exists.
- No FFmpeg or ffprobe invocation exists, and cross-platform executable discovery/configuration is undecided.
- No concrete application schema, versioned Flyway migration, or application table exists; the documented data model is conceptual only.
- No Source, FileEntry, ContentRecord, WorkingSet, ScanRun, Job, AnalysisRecord, or specialized-result persistence has been implemented.
- No long-running job execution, checkpointing, pause/resume, or progress model has been implemented.
- No SSE endpoint or event design exists.
- No AI-provider integration or provider abstraction exists.
- Concrete package boundaries, schema details, scanning mechanics, matching algorithms, job scheduling/concurrency, and analysis-result structures are not finalized.
- Automated coverage is limited to the generated Spring context-load test; the health endpoint and frontend currently have no automated behavior tests.

## Next Recommended Step

Define and review the first concrete SQLite schema and Java boundaries from the documented conceptual model, followed by an architecture review before substantial implementation. Resolve only the details required for the first small implementation increment, and do not treat conceptual names or open questions as finalized schema or code design.
