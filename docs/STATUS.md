# Status

## Current State

Media Compare is a fresh v2 repository with a working full-stack baseline. The repository contains separate `backend/` and `frontend/` projects, but no real search, scanning, media-analysis, or comparison behavior has been implemented.

The backend is a Java 21 and Spring Boot 4.1.1 Maven application. It connects to a local SQLite database, starts Flyway, and exposes one health endpoint. The frontend is a React and TypeScript Vite application with React Router; its single route displays the result of the backend health request.

As of September 14, 2026, the backend test/build path, backend startup, database connection, Flyway startup, direct health endpoint, frontend production build, Vite development server, and proxied frontend-to-backend request have been exercised successfully.

## Documentation

The durable documentation baseline now consists of:

- `AGENTS.md` — operating rules for future AI-agent work and documentation maintenance.
- `README.md` — developer-facing overview and local startup instructions.
- `docs/ARCHITECTURE.md` — confirmed current boundaries and planned-but-undesigned technical capabilities.
- `docs/DATA_MODEL.md` — confirmed persistence choices and currently empty application schema.
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
- The initial full-stack scaffold is committed on `main`; the local `main` and `origin/main` references point to commit `eef9981` (`Initial full-stack scaffold`).
- `origin` uses `git@github-personal:topher6835/media-compare.git`, and repository-local Git identity is configured for `topher6835`.

## Known Limitations / Not Yet Implemented

- No product-level search or comparison behavior exists.
- No filesystem scanning exists.
- No Java NIO filesystem workflow exists.
- No FFmpeg or ffprobe invocation exists, and cross-platform executable discovery/configuration is undecided.
- No application data model, versioned Flyway migration, or application table exists.
- No long-running job or progress model exists.
- No SSE endpoint or event design exists.
- No AI-provider integration or provider abstraction exists.
- Package boundaries, scanning model, comparison-engine structure, and job architecture are not finalized.
- Automated coverage is limited to the generated Spring context-load test; the health endpoint and frontend currently have no automated behavior tests.

## Next Recommended Step

Conduct a deliberate Media Compare architecture and data-flow design pass before implementing real search or comparison functionality. The pass should clarify the product flow and resolve only the architecture and persistence decisions needed for the first small implementation increment. Do not treat the open areas in the current documentation as already designed.
