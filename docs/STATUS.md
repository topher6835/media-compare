# Status

## Current State

Media Compare is a fresh v2 repository with a working full-stack scaffold. The repository contains separate `backend/` and `frontend/` projects. The backend can register/read Sources, create/read durable Source-based scan requests, create/read the durable ScanRun-to-Job execution handoff, synchronously execute DISCOVERY followed by safe missing-file RECONCILIATION, assign ContentRecords to eligible completed-scan observations, publish exact SHA-256 analysis, and derive exact duplicate groups through REST. Broader media analysis, similarity matching, materialized decisions, and cleanup are not implemented.

The backend is a Java 21 and Spring Boot 4.1.1 Maven application. It connects to a local SQLite database, starts Flyway, exposes `GET /api/health`, provides Source endpoints under `/api/sources`, scan-request endpoints under `/api/scan-runs`, execution-handoff endpoints under `/api/scan-runs/{id}/execution`, synchronous DISCOVERY and RECONCILIATION POST endpoints, `POST /api/scan-runs/{id}/content-assignment`, `POST /api/scan-runs/{id}/content-hashing`, and read-only exact duplicate endpoints under `/api/exact-duplicate-groups`. The frontend is a React and TypeScript Vite application with React Router; its `/` route requests the backend health endpoint through the Vite proxy. No Source, ScanRun, execution, or duplicate-reporting frontend exists.

The reviewed V1 persistence foundation is implemented. Flyway migration `V1__create_core_schema.sql` creates the eleven application tables with their structural constraints, foreign keys, and initial indexes; no new migration was needed for DISCOVERY, RECONCILIATION, ContentRecord assignment, exact hashing, or derived exact grouping. Immutable Java records and focused Spring JDBC repositories provide persistence under the reviewed feature packages. Exact hashing streams filesystem bytes outside transactions and atomically publishes reusable analysis only after stale-evidence guards pass. The matching slice derives duplicate summaries, members, and retained occurrences from trusted artifacts without materializing groups or mutating state. Scheduling/background execution, broader analysis/matching, AI, and filesystem modification remain unimplemented.

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
- `POST /api/scan-runs/{id}/execution/reconciliation` synchronously executes an eligible pending RECONCILIATION stage and returns the completed execution with HTTP 200.
- Reconciliation preflight requires a `RUNNING` ScanRun, a `RUNNING` SCAN Job whose current stage is RECONCILIATION, completed DISCOVERY, pending RECONCILIATION, and only `DISCOVERED` ScanRunSources with positive traversal generations and null completion state. Missing ScanRuns/executions return 404; ineligible state returns 409 before mutation.
- Reconciliation uses no filesystem or current Source configuration data. It succeeds after Source roots are removed or location revisions change because it consumes durable FileEntry traversal identity.
- An exact current `(scan_run_source_id, traversal_generation)` pair keeps an entry `PRESENT`. Other currently-present entries for that Source, including rows with null last-seen fields, become `MISSING`; entries belonging to unselected Sources are untouched.
- Becoming `MISSING` preserves content association, observation revision, path, filesystem metadata, seen timestamps, and last-seen traversal identity. Already-missing entries remain unchanged.
- Each Source's missing sweep, transition to `COMPLETED`, completed-generation/timestamp publication, and progress update share one transaction. A failure within that boundary rolls all of those changes back together; generic recovery after separately committed Sources remains deferred.
- RECONCILIATION progress counts completed Sources. Job progress mirrors the current stage and resets from DISCOVERY file units to zero out of the Source count when reconciliation starts.
- Successful reconciliation completes all ScanRunSources, the RECONCILIATION stage, the Job, and the ScanRun; clears the Job's current stage; preserves the Job attempt count and ScanRun start timestamp; and creates no subsequent stage.
- Reconciliation creates no ContentRecord, hash, or analysis row. Integration tests cover lifecycle/final state, multi-Source isolation, empty Sources, exact and null traversal identities, field preservation, unavailable roots, stale Source revisions, conflicts, repeat prevention, and transactional rollback.
- `POST /api/scan-runs/{id}/content-assignment` requires the ScanRun, SCAN Job, DISCOVERY and RECONCILIATION stages, and every ScanRunSource to be at their safe completed boundary. Missing ScanRuns or execution handoffs return 404; incomplete or inconsistent lifecycle state returns 409 before mutation.
- Assignment is filesystem-independent and ignores current Source configuration/location revisions. It selects only `PRESENT`, content-null FileEntries from each ScanRunSource's exact completed traversal and reads them in ascending-ID keyset pages of at most 250.
- Each eligible occurrence/version receives its own new ContentRecord with the observed size and a creation timestamp. Separate files receive distinct records even when bytes and metadata match; assignment itself performs no hashing, equality inference, deduplication, or merge.
- Each ContentRecord insert and guarded FileEntry attachment share one transaction. Publication requires the entry to remain `PRESENT`, content-null, and at the expected observation revision and size. Stale publication rolls back the new record, increments `skippedCount`, and continues without leaving an orphan; the guard permits a later unchanged traversal identity.
- Existing content associations survive unchanged rescans. Durable content-null state makes the command resumable and repeatable, normally returning zero assigned and skipped after completion.
- Assignment leaves the completed ScanRun, SCAN Job, both JobStages, and ScanRunSource rows unchanged and creates no Job, JobStage, AnalysisRecord, or ContentHash.
- Content-assignment integration tests cover field preservation; identity separation; all eligibility exclusions; deleted roots and changed Source revisions; lifecycle preflight; 251-row paging; all optimistic stale guards and orphan rollback; skipped counting and continuation; repeat behavior; and unchanged cross-run content preservation.
- `POST /api/scan-runs/{id}/content-hashing` uses the same completed lifecycle preflight and hashes only `PRESENT`, assigned entries from each ScanRunSource's exact completed traversal. Missing ScanRuns/execution handoffs return 404 and unsafe lifecycle state returns 409 without mutation.
- Hash candidates are read in ascending FileEntry-ID keyset pages of at most 250. Exact-key completed artifacts are reused before Source or filesystem access; cache reuse therefore still works after the backing root is unavailable.
- Uncached candidates require the ScanRunSource Source-location snapshot to remain current, safe component-by-component path reconstruction without symbolic links in the root, parents, or candidate, a regular file, and exact size/mtime before and after streaming SHA-256. Stale evidence is skipped, ordinary per-file access failures are counted, and later candidates continue.
- New results use `CONTENT_HASH` / `builtin.sha256` / analyzer version `1` / configuration version `1` / `{}` provenance. The lowercase 64-character digest is stored with algorithm `SHA-256` in a ContentHash row.
- A dedicated transactional writer revalidates FileEntry identity, Source, presence, ContentRecord, observation revision, size, exact mtime, and Source location revision, then atomically publishes the completed AnalysisRecord and ContentHash. Changed traversal identity alone remains safe. Hashing leaves FileEntry, ContentRecord, ScanRun, ScanRunSource, Job, and JobStage state unchanged and creates no new execution rows.
- Separate ContentRecords with identical bytes retain separate AnalysisRecord/ContentHash rows with equal digests. Hashing itself performs no ContentRecord merge or durable equality grouping.
- Exact-hashing integration tests cover known digests/provenance, atomic rollback, cache reuse without a root, candidate classification and continuation, symlink rejection, every database publication guard, traversal-identity tolerance, lifecycle errors, real transaction boundaries, and paging across 252 candidates.
- `GET /api/exact-duplicate-groups` returns only digest groups with at least two distinct ContentRecords using bounded ascending-digest keyset pagination. `GET /api/exact-duplicate-groups/{digestHex}` returns summary, distinct members, and retained FileEntry occurrences; singleton and unknown digests return 404.
- Group queries require the exact completed built-in SHA-256 provenance and validate configuration JSON, algorithm, and lowercase digest structure. Malformed completed exact artifacts and same-digest ContentRecord size disagreement raise integrity failures without repair.
- ContentRecord cardinality is calculated separately from occurrence joins. `PRESENT` and `MISSING` counts, distinct retained Sources, zero-occurrence members, and deterministic member/occurrence ordering are supported across Sources without filesystem access.
- Potential storage savings is the estimated logical value `max(presentOccurrenceCount - 1, 0) * sizeBytes`; missing occurrences do not contribute, and the value is not actual recoverable disk allocation.
- Exact groups are live derived views, not durable group rows. Reads create no Job/JobStage, do not mutate catalog or analysis state, and do not merge or canonicalize ContentRecords.
- Exact-duplicate integration tests exercise real SQLite grouping, occurrence aggregation, provenance filtering, corruption handling, pagination/input validation, missing history, unavailable roots, and repeated read-only behavior.
- The frontend production build succeeds.
- React Router is wired through `BrowserRouter` and a `/` route.
- The Vite development server starts on port `5173`.
- Vite proxies `/api` to `http://localhost:8080`.
- A request to `/api/health` through Vite reaches the backend and returns `ok`.
- The frontend renders the health request result.
- Exact SHA-256 hashing is committed; local `main` and `origin/main` point to `6734a74` (`Add exact SHA-256 content hashing`). The derived exact-duplicate increment is currently uncommitted.
- Git origin uses `git@github-personal:topher6835/media-compare.git`, with repository-local identity configured for `topher6835`.

## Known Limitations / Not Yet Implemented

- No Source update, deletion, or relocation/remount recognition workflow exists.
- No ScanRun list, cancellation, retry/recovery, WorkingSet request, custom options, scheduling, or background execution exists.
- Simultaneous duplicate execution-request hardening remains deferred with the broader scheduling/concurrency design.
- No materialized equality-group identity, ContentRecord reconciliation/merge, media metadata, perceptual fingerprints, embeddings, face analysis, or AI integration exists.
- No general durable worker, pause/resume, startup recovery, or live progress delivery exists.
- No SSE endpoint or event design exists.
- No matching candidates, similarity relationships, materialized groups, manual overrides, or filesystem-action history exists.
- Lifecycle/type values, workflow-level repository operations, merge behavior, scheduling, concurrency, cache locations, and FFmpeg/ffprobe discovery remain open as documented.
- The health endpoint and frontend have no automated behavior tests.

## Next Recommended Step

After reviewing this derived exact-duplicate API increment, define the next user-facing duplicate browsing/review boundary without introducing automatic merge, cleanup, or filesystem modification. Background scheduling, retry/recovery, SSE, and broader symlink/junction traversal policy remain deferred.
