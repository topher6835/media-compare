# Status

## Current State

Media Compare is a fresh v2 repository with a working full-stack scaffold. The repository contains separate `backend/` and `frontend/` projects. The backend can register/read Sources and create/read durable Source-based scan requests through REST. Scan requests currently record intent only; no search, filesystem scanning, media analysis, comparison, cleanup, or execution workflow has been implemented.

The backend is a Java 21 and Spring Boot 4.1.1 Maven application. It connects to a local SQLite database, starts Flyway, exposes `GET /api/health`, provides Source registration/read endpoints under `/api/sources`, and provides scan-request creation/read endpoints under `/api/scan-runs`. The frontend is a React and TypeScript Vite application with React Router; its `/` route requests the backend health endpoint through the Vite proxy. No Source or ScanRun frontend exists.

The reviewed V1 persistence foundation is implemented. Flyway migration `V1__create_core_schema.sql` creates the eleven application tables with their structural constraints, foreign keys, and initial indexes. Immutable Java records and focused Spring JDBC repositories provide basic insert/read persistence under the reviewed feature packages. `SourceService` owns Source registration/read behavior. `ScanRunService` validates registered Source IDs, snapshots their location revisions, and atomically creates a pending ScanRun with its pending ScanRunSource rows. Scanning, hashing, reconciliation execution, Job creation/execution, analysis execution, matching, AI, and filesystem operations remain unimplemented.

## Documentation

The durable documentation baseline is:

- `AGENTS.md` — operating rules for future AI-agent work and documentation maintenance.
- `README.md` — developer-facing overview and local startup instructions.
- `docs/ARCHITECTURE.md` — implemented scaffold and persistence foundation, confirmed long-term boundaries, and open architecture areas.
- `docs/DATA_MODEL.md` — implemented V1 schema, constraints, indexes, and Java persistence structure.
- `docs/DECISIONS.md` — project, stack, architecture, implemented V1 persistence, package, development, and Git decisions.
- `docs/STATUS.md` — current handoff state and next step.

## What Currently Works

- The backend Maven wrapper is available for macOS/Linux (`mvnw`) and Windows (`mvnw.cmd`).
- The backend compiles and its Spring context test passes on Java 21.
- Spring Boot starts successfully on port `8080`.
- SQLite connectivity uses `jdbc:sqlite:data/media-compare.db?foreign_keys=on`.
- Flyway applies `V1__create_core_schema.sql` from `classpath:db/migration` and creates all eleven V1 application tables.
- SQLite foreign-key enforcement is enabled on every physical datasource connection through the JDBC URL's `foreign_keys=on` property.
- The migration enforces the reviewed uniqueness, numeric/range, timestamp-pair, foreign-key, and deletion rules and creates the six reviewed secondary indexes.
- Immutable Java records represent the V1 rows in the `catalog`, `scan`, `job`, and `analysis` packages.
- `CatalogRepository`, `ScanRepository`, `JobRepository`, and `AnalysisRepository` provide focused Spring JDBC insert/read operations.
- Persistence tests verify the exact table set, foreign-key behavior on multiple connections, FileEntry/ScanRunSource Source consistency, traversal/completed-generation bounds, key uniqueness and intentional non-uniqueness, numeric/timestamp constraints, deletion behavior, and repository round trips.
- `GET /api/health` returns plain text `ok` with HTTP 200.
- `POST /api/sources` validates and registers a Source, returns HTTP 201 with a resource `Location`, and exposes public Source fields without `rootPathKey`.
- Source registration preserves the configured root-path string, initially mirrors it to internal `root_path_key`, assigns database identity and revision zero, and initializes equal creation/update timestamps.
- Registration accepts duplicate names and paths and a valid absolute path that is not currently available; it rejects blank names, blank paths, syntactically invalid paths, and relative paths.
- `GET /api/sources` returns Sources in ascending database-ID order, including an empty JSON array when none exist.
- `GET /api/sources/{id}` returns one Source or HTTP 404.
- Source API integration tests cover persistence, public representations, validation, duplicate registration semantics, ordering, missing IDs, and non-existent absolute paths using platform-safe temporary paths.
- `POST /api/scan-runs` creates an `INDEX` request for one or more registered Sources and returns HTTP 201 with a resource `Location`.
- New ScanRuns are `PENDING` with options version 1 and `{}` effective options; each selected Source is `PENDING`, snapshots its current location revision, and begins with traversal generation zero and no completion/execution state.
- ScanRun creation validates every Source before insertion and commits the parent and all child rows atomically. Missing Sources return HTTP 404 without partial persistence; malformed lists return HTTP 400.
- `GET /api/scan-runs/{id}` returns the durable request with Source rows ordered by ascending Source ID, or HTTP 404.
- ScanRun creation records intent only and creates no Job.
- ScanRun API integration tests cover initial internal/public state, one and multiple Sources, revision snapshots, deterministic ordering, validation and missing IDs, atomic no-write failure, no Job creation, and repeated requests.
- The frontend production build succeeds.
- React Router is wired through `BrowserRouter` and a `/` route.
- The Vite development server starts on port `5173`.
- Vite proxies `/api` to `http://localhost:8080`.
- A request to `/api/health` through Vite reaches the backend and returns `ok`.
- The frontend renders the health request result.
- The Source registration/read API is committed on `main`; local `main` and `origin/main` point to `981b204` (`Add Source registration and read API`). The ScanRun API increment is currently uncommitted.
- Git origin uses `git@github-personal:topher6835/media-compare.git`, with repository-local identity configured for `topher6835`.

## Known Limitations / Not Yet Implemented

- No Source update, deletion, relocation/remount recognition, availability checking, reconciliation, or cross-platform filesystem traversal workflow exists.
- No ScanRun list, status mutation, cancellation, retry, WorkingSet request, custom options, Job handoff, scheduling, or execution behavior exists.
- No exact hashing, content reconciliation, media metadata, fingerprints, embeddings, face analysis, or AI integration exists.
- No durable Job execution, stage checkpointing, pause/resume, or progress delivery exists.
- No SSE endpoint or event design exists.
- No matching candidates, similarity relationships, groups, manual overrides, or filesystem-action history exists.
- Lifecycle/type values, workflow-level repository operations, merge behavior, scheduling, concurrency, cache locations, and FFmpeg/ffprobe discovery remain open as documented.
- The health endpoint and frontend have no automated behavior tests.

## Next Recommended Step

Review the durable ScanRun creation/read API as a completed intent-recording slice. The next deliberate increment should define the initial durable Job/stage execution handoff before implementing filesystem traversal or reconciliation execution.
