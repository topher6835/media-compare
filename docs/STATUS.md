# Status

## Current State

Media Compare is a fresh v2 repository with a working full-stack scaffold. The repository contains separate `backend/` and `frontend/` projects. The backend can register/read Sources, create/read durable Source-based scan requests, create/read the initial durable ScanRun-to-Job execution handoff, and synchronously execute the pending DISCOVERY stage through REST. Discovery is the first actual filesystem operation; reconciliation, media analysis, comparison, and cleanup are not implemented.

The backend is a Java 21 and Spring Boot 4.1.1 Maven application. It connects to a local SQLite database, starts Flyway, exposes `GET /api/health`, provides Source endpoints under `/api/sources`, scan-request endpoints under `/api/scan-runs`, execution-handoff endpoints under `/api/scan-runs/{id}/execution`, and `POST /api/scan-runs/{id}/execution/discovery`. The frontend is a React and TypeScript Vite application with React Router; its `/` route requests the backend health endpoint through the Vite proxy. No Source, ScanRun, or execution frontend exists.

The reviewed V1 persistence foundation is implemented. Flyway migration `V1__create_core_schema.sql` creates the eleven application tables with their structural constraints, foreign keys, and initial indexes; no new migration was needed for discovery. Immutable Java records and focused Spring JDBC repositories provide persistence under the reviewed feature packages. `SourceService` owns Source registration/read behavior. `ScanRunService` atomically records scan intent. `ScanExecutionService` keeps scan-specific Job orchestration in `scan`, creates the pending execution handoff, and coordinates discovery without holding a write transaction across filesystem traversal. Reconciliation, scheduling/background execution, hashing, analysis execution, matching, AI, and filesystem modification remain unimplemented.

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
- `POST /api/scan-runs/{id}/execution` atomically creates a pending `SCAN` Job and exactly one pending `DISCOVERY` stage, returning HTTP 201 with the singleton execution `Location`.
- `GET /api/scan-runs/{id}/execution` returns the durable Job/stage representation, or HTTP 404 when the ScanRun or execution does not exist.
- The execution handoff preserves the pending ScanRun and ScanRunSource state, succeeds without Source-path availability, and performs no filesystem work.
- A sequential duplicate execution POST returns HTTP 409 without creating another Job or JobStage. The service enforces this without a schema-wide one-Job-per-ScanRun restriction.
- Scan-execution integration tests cover initial Job/stage state, shared timestamps, transactional structure, reads, missing resources, duplicate prevention, unchanged scan state, unavailable paths, and independent executions for different ScanRuns.
- `POST /api/scan-runs/{id}/execution/discovery` synchronously executes an eligible pending DISCOVERY stage and returns the updated execution with HTTP 200.
- Discovery preflight verifies that every selected Source still exists, each location revision matches its ScanRun snapshot, and the SCAN Job/current DISCOVERY stage are pending. Missing ScanRuns/executions return 404; stale Source snapshots and ineligible/already-completed execution return 409 without mutation or traversal.
- Starting discovery moves the ScanRun and Job to `RUNNING`, starts DISCOVERY, increments Job/stage attempt counts to one, and moves each ScanRunSource to `DISCOVERING` with its first traversal generation set to one.
- Source roots are walked recursively with Java NIO `Files.walkFileTree(...)` without enabling link following. Only regular files are observed; directories and symbolic-link entries are skipped.
- Persisted paths are Source-relative, preserve observed case and Unicode spelling, use `/` between NIO path segments, and initially use the same string for `relative_path` and `path_key`. Absolute paths are not stored as FileEntry relative paths.
- Discovery records file size and modification epoch seconds/nanoseconds. It does not use millisecond-truncated filesystem time.
- New FileEntries are `PRESENT`, have revision zero and no ContentRecord. Re-observation refreshes current metadata and traversal identity; size/mtime/presence changes increment the observation revision once and clear stale content association, while unchanged observations preserve revision, first-seen time, and content association.
- FileEntry observations and Job/stage progress commit in transactions of at most 250 files. Start, completion, and failure state use separate short transaction-proxied bean methods; traversal itself is outside a write transaction.
- Progress counts successfully persisted regular-file observations. Total remains null during traversal and equals completed progress after successful discovery.
- Successful traversal moves ScanRunSources to `DISCOVERED` while deliberately leaving `completed_generation` and `completed_at_ms` null, completes DISCOVERY, creates exactly one pending RECONCILIATION stage, and leaves the ScanRun and Job `RUNNING` with the Job current stage set to `RECONCILIATION`.
- Discovery never marks an unobserved FileEntry missing and creates no ContentRecord, hash, or analysis row.
- Missing, non-directory, inaccessible, or traversal-failing roots fail every ScanRunSource participating in that started DISCOVERY attempt, plus the DISCOVERY stage, Job, and ScanRun, so no child remains `DISCOVERING`; no RECONCILIATION is created. Allocated generations and previously committed observations remain, while every `completed_generation` stays null and no missing sweep is authorized.
- Discovery integration tests use temporary paths and cover recursive/portable paths, metadata, lifecycle/progress, insert/update invalidation, Source-revision preflight, missing-root and deterministic mid-traversal failure, symbolic-link skipping, transaction boundaries, and a 251-file multi-batch traversal.
- The frontend production build succeeds.
- React Router is wired through `BrowserRouter` and a `/` route.
- The Vite development server starts on port `5173`.
- Vite proxies `/api` to `http://localhost:8080`.
- A request to `/api/health` through Vite reaches the backend and returns `ok`.
- The frontend renders the health request result.
- The durable execution handoff is committed on `main`; local `main` and `origin/main` point to `003b934` (`Add durable scan execution handoff`). The DISCOVERY increment is currently uncommitted.
- Git origin uses `git@github-personal:topher6835/media-compare.git`, with repository-local identity configured for `topher6835`.

## Known Limitations / Not Yet Implemented

- No Source update, deletion, relocation/remount recognition, or reconciliation workflow exists.
- No ScanRun list, cancellation, retry/recovery, WorkingSet request, custom options, scheduling, background execution, or RECONCILIATION execution exists.
- Simultaneous duplicate execution-request hardening remains deferred with the broader scheduling/concurrency design.
- No exact hashing, content reconciliation, media metadata, fingerprints, embeddings, face analysis, or AI integration exists.
- No general durable worker, pause/resume, startup recovery, or live progress delivery exists.
- No SSE endpoint or event design exists.
- No matching candidates, similarity relationships, groups, manual overrides, or filesystem-action history exists.
- Lifecycle/type values, workflow-level repository operations, merge behavior, scheduling, concurrency, cache locations, and FFmpeg/ffprobe discovery remain open as documented.
- The health endpoint and frontend have no automated behavior tests.

## Next Recommended Step

Implement RECONCILIATION/missing-file completion as the next deliberate increment. It should consume a successfully discovered traversal generation, apply the missing-file sweep only at the safe completion boundary, and set `completed_generation`/completion state atomically. Hashing, analysis, background scheduling, retry/recovery, SSE, and final symlink/junction semantics should remain deferred unless explicitly brought into scope.
