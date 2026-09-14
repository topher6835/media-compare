# Architecture

## Current Architecture

Media Compare currently consists of two independently developed parts:

- A React single-page frontend in `frontend/`.
- A Spring Boot REST backend in `backend/`.

Only a minimal health-check path is implemented. The current scaffold proves that the frontend, development proxy, backend, Flyway, and SQLite can start and communicate.

## Backend

The backend uses Java 21, Spring Boot 4.1.1, and Maven. The Maven wrapper is committed for macOS/Linux and Windows. Spring Web MVC provides the current REST endpoint, and Spring JDBC is selected for future database access.

`GET /api/health` is the only application endpoint. It returns the plain text response `ok`.

## Frontend

The frontend uses React, TypeScript, and Vite. React Router wraps the application and currently defines only the `/` route. That route requests `/api/health` and displays the returned backend status.

ESLint is configured as part of the frontend scaffold.

## Persistence

SQLite is the selected persistence layer. The configured JDBC URL is `jdbc:sqlite:data/media-compare.db`, which resolves to `backend/data/media-compare.db` when the backend is started from `backend/` as documented.

Flyway owns schema migrations and scans `classpath:db/migration`, backed by `backend/src/main/resources/db/migration/`. There are no versioned application migrations yet. The current local database contains only Flyway's schema-history table.

Local database files are ignored by Git. `backend/data/.gitkeep` keeps the data directory in the repository.

## Development Request Flow

The proven local development request flow is:

```text
React application on Vite :5173
    -> request to /api
Vite development proxy
    -> Spring Boot :8080
    -> SQLite at backend/data/media-compare.db
```

The frontend's current health request follows this route and has been successfully exercised through the proxy.

## Cross-Platform Requirement

The application must support both macOS and Windows. Filesystem work should use Java NIO when practical and must not hard-code macOS-only paths or assumptions.

FFmpeg may currently be available through Homebrew on the development machine, but future code must not assume `/opt/homebrew/bin/ffmpeg`, `/opt/homebrew/bin/ffprobe`, or any other fixed platform-specific location. Executable discovery or configuration must account for macOS and Windows. That strategy is not designed yet.

## Planned Technical Capabilities

The following technical directions are planned but not implemented:

- Filesystem scanning using Java NIO.
- Media inspection and processing using FFmpeg and ffprobe.
- Long-running work with progress reporting.
- Server-to-client live/progress updates using SSE.
- REST APIs beyond the health check.
- Pluggable local and cloud AI providers.

These are capability directions, not a finalized system design.

## Undecided Architecture Areas

The following areas have not been finalized and must be addressed in a deliberate architecture and data-flow design pass before implementation:

- Backend and frontend package/module boundaries for product features.
- Long-running job architecture.
- Filesystem scanning model.
- Comparison-engine structure.
- SSE endpoint and event design.
- AI-provider interfaces, selection, configuration, and lifecycle.
- FFmpeg/ffprobe discovery and configuration across macOS and Windows.

No architecture for these areas is implied by this document.
