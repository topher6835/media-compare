# Status

## Current State

Media Compare is a fresh v2 repository with a working full-stack scaffold. The backend supports the existing public version-1 indexing workflow and an internal synchronous version-2 lifecycle in which one durable SCAN Job owns DISCOVERY, RECONCILIATION, CONTENT_ASSIGNMENT, and CONTENT_HASHING. It also derives and catalog-correctly filters exact duplicate groups through REST. The frontend can register and browse Sources, launch the existing version-1 pipeline through exact hashing, follow its stage and supplied counts, then browse/filter exact duplicate groups and inspect complete group/member/occurrence context. Broader library/review workflows, media analysis, similarity matching, materialized decisions, and cleanup are not implemented.

The backend is a Java 21 and Spring Boot 4.1.1 Maven application. It connects to a local SQLite database, starts Flyway, exposes `GET /api/health`, provides Source endpoints under `/api/sources`, scan-request endpoints under `/api/scan-runs`, execution-handoff endpoints under `/api/scan-runs/{id}/execution`, synchronous DISCOVERY and RECONCILIATION POST endpoints, `POST /api/scan-runs/{id}/content-assignment`, `POST /api/scan-runs/{id}/content-hashing`, and read-only exact duplicate endpoints under `/api/exact-duplicate-groups`. The frontend is a React and TypeScript Vite application with React Router. Its `/` route retains the health check and directs users to Sources or Exact Duplicates; `/sources` registers/lists Sources and orchestrates indexing; `/duplicates` browses and filters aggregate summaries; and `/duplicates/:digestHex` shows complete group, member, occurrence, and filter-match context.

The reviewed persistence foundation is implemented. Flyway migration `V1__create_core_schema.sql` creates the eleven application tables with their structural constraints, foreign keys, and initial indexes. Java Flyway migration `V2__add_file_entry_extension_key` adds nullable normalized FileEntry extension metadata, bounded backfill, and its lookup index. SQL migration `V3__add_durable_indexing_foundation.sql` retains eleven tables and adds future start-idempotency plus execution-version, bounded stage-result, and version-2 SCAN admission primitives now used by the internal lifecycle. Immutable Java records and focused Spring JDBC repositories map the fields. Background execution, polling/read recovery, startup interruption handling, public v2 endpoints, and frontend cutover remain unimplemented.

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
- Flyway applies V1, Java migration V2, and SQL migration `V3__add_durable_indexing_foundation.sql`, retaining eleven application tables.
- SQLite foreign-key enforcement is enabled on every physical datasource connection through the JDBC URL's `foreign_keys=on` property.
- The migrations enforce the reviewed uniqueness, numeric/range, timestamp-pair, foreign-key, and deletion rules, plus unique non-null request keys, one version-2 SCAN Job per ScanRun, and one globally active version-2 SCAN Job.
- Immutable Java records represent the rows in the `catalog`, `scan`, `job`, and `analysis` packages, including `requestKey`, `executionVersion`, and `resultJson`.
- `CatalogRepository`, `ScanRepository`, `JobRepository`, and `AnalysisRepository` provide focused Spring JDBC insert/read operations.
- Lifecycle Job reads and mutations select an explicit execution version. Existing public services select version 1; internal v2 services select version 2, so historical rows may coexist without oldest-row ambiguity.
- Internal version-2 creation atomically inserts one pending SCAN Job and DISCOVERY stage for a pending INDEX ScanRun. Database constraints remain authoritative for duplicate-per-ScanRun and global-active admission races, and constraint failures become clear service conflicts. Version-1 Jobs do not occupy the active v2 slot; terminal v2 Jobs release it.
- The v2 sequence is `DISCOVERY → RECONCILIATION → CONTENT_ASSIGNMENT → CONTENT_HASHING → COMPLETED` on one Job. ScanRun starts with discovery, remains `RUNNING` while each later stage advances, and completes only with hashing. ScanRunSource rows become `COMPLETED` after reconciliation because they describe traversal/reconciliation, not analysis.
- Every v2 stage uses conditional affected-row claims. Repeated, completed, wrong-stage, and terminal invocations cannot reopen the stage or perform its work again. Stage claim and finalization methods are short transactions; directory traversal and hash streaming remain outside database transactions.
- V2 assignment persists the exact typed result shape `{"version":1,"assignedCount":n,"skippedCount":n}`. V2 hashing persists `{"version":1,"hashedCount":n,"cachedCount":n,"skippedCount":n,"failedCount":n}`. Exact-shape codecs reject malformed or incompatible stored state. Only final assignment/hash summaries are durable; their live intermediate progress is deferred.
- Hash candidate skips or failures produce a completed hashing stage, Job, and ScanRun with issue counts. A whole-operation/integrity failure instead atomically fails the current stage, Job, and ScanRun with a safe message, clears the Job current stage, and does not create or run a later stage.
- `Version2ScanExecutionService.run(...)` synchronously invokes the four existing stage algorithms for service-level use and returns final durable state without a transaction spanning the pipeline. It is not exposed as a public controller.
- Persistence and migration tests verify the exact table set, V2-to-V3 upgrade defaults and relationships, V3 partial-index admission behavior, foreign keys, structural constraints, and repository round trips for the new fields.
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
- Persisted paths are Source-relative, preserve observed case and Unicode spelling, use `/` between NIO path segments, and initially use the same string for `relative_path` and `path_key`. Absolute paths are not stored as FileEntry relative paths. FileEntries also store a nullable lowercase technical extension derived from the basename without changing path identity; re-observation keeps it synchronized.
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
- Becoming `MISSING` preserves extension metadata, content association, observation revision, path, filesystem metadata, seen timestamps, and last-seen traversal identity. Already-missing entries remain unchanged.
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
- Repeated case-insensitive `fileCategory` and `extension` parameters filter eligible groups through retained occurrences before digest pagination. Values use OR within a dimension and AND between dimensions; both `PRESENT` and `MISSING` occurrences can select a group.
- Filtered summaries preserve complete-group counts and savings. Their nullable `filterMatch` reports only matching retained-occurrence count and extensions. Filtered detail always returns the full group and marks every occurrence with nullable extension/category metadata plus `matchesFilter`; a zero-match detail remains HTTP 200.
- `GET /api/exact-duplicate-groups/filter-options` returns deterministic non-null extension options with derived nullable technical category, distinct exact-group count, and retained-occurrence count. Technical `PHOTO`, `VIDEO`, and `DOCUMENT` classification is backend-derived and distinct from future user Tags/Categories.
- Exact-duplicate integration tests exercise real SQLite grouping, occurrence aggregation, provenance/integrity filtering, category/extension selection, whole-group match context, filtered pagination, detail flags, filter options, missing history, unavailable roots, and repeated read-only behavior.
- The frontend production build succeeds.
- React Router is wired through `BrowserRouter` and a `/` route.
- The Vite development server starts on port `5173`.
- Vite proxies `/api` to `http://localhost:8080`.
- A request to `/api/health` through Vite reaches the backend and returns `ok`.
- The frontend renders the health request result.
- `/sources` loads registered Sources in backend order, represents the empty and unavailable states, and adds a newly registered Source to the list without a page reload.
- Source registration sends exactly `name` and `rootPath`, requires both fields client-side, leaves absolute-path/platform validation authoritative to the backend, explains that paths belong to the local backend host, and prevents repeated submission while pending.
- Each Source exposes one `Analyze Source` action. The page permits one active Source analysis at a time and synchronously calls ScanRun creation, execution creation, DISCOVERY, RECONCILIATION, ContentRecord assignment, and content hashing in that order.
- Frontend orchestration validates the returned ScanRun/Job IDs and expected durable status/stage boundary before advancing. A failure stops later requests and retains known identifiers for diagnosis.
- The progress panel uses friendly stage labels without inventing percentages and displays only backend-supplied discovery, reconciliation, assignment, and hashing counts. Long calls are described as synchronous, and controls do not imply background work.
- Discovery failure explains Source-path availability without deleting or changing the registered Source. Other failures identify the stopped stage; no call is automatically retried, while a user may start a new ScanRun attempt.
- Successful hashing with zero skipped/failed candidates shows `Analysis complete`; nonzero hashing `skippedCount` or `failedCount` shows `Analysis finished with issues` as a completed warning. Both states link to `/duplicates` without claiming that duplicate groups exist, and hashing counts remain visible.
- The home page and primary navigation expose both Sources/Index media and Exact Duplicates. The frontend performs no filesystem mutation.
- `/duplicates` renders scan-friendly group summaries, estimated logical savings, present/missing counts, and appends backend keyset pages without duplicate digest rows. It provides accessible multi-select All/Photos/Videos/Documents controls and a dynamic checkbox extension selector whose failure does not block the group list.
- Active filters use canonical repeated URL parameters. Categories and extensions each use OR semantics and combine with AND. Unsupported categories are removed; duplicate values collapse; selected stale extensions remain visible and requested; clearing returns to clean `/duplicates`.
- Filtered cards use backend `filterMatch` to report matching retained occurrences, matching extensions, and any additional retained occurrences without changing member, Source, presence, or savings values. Filtered and catalog-wide empty states are distinct.
- `/duplicates/:digestHex` preserves filter parameters, uses the full digest as route identity, and renders the complete group summary, distinct ContentRecord members, and all retained ordered occurrences. Backend extension/category values and `matchesFilter` visually distinguish matches while nonmatches remain visible; mixed-extension information uses the entire detail response.
- Friendly `DUP-XXXXXXXX` labels are display-only uppercase digest prefixes. The frontend does not materialize group identity or select a keeper.
- Loaded list pages and scroll position survive normal list/detail navigation only for the same order-insensitive canonical filter key. Filter changes start from the first page at the top. A bounded browser-memory trail records meaningful group visits without consecutive duplicates and preserves current filters on its links.
- Loading, empty, malformed-request, unknown-group, and backend-failure states have user-facing messages without backend details.
- The exact-duplicate frontend has no mutation controls; it performs no deletion, move, cleanup, merge, keeper selection, or persistent review-state write.
- Local `HEAD`, `main`, and `origin/main` started this lifecycle increment at `ef941b2` (`Add durable indexing persistence foundation`) with a clean worktree.
- Git origin uses `git@github-personal:topher6835/media-compare.git`, with repository-local identity configured for `topher6835`.

## Known Limitations / Not Yet Implemented

- No Source update, deletion, or relocation/remount recognition workflow exists.
- No ScanRun list, cancellation, retry/recovery, WorkingSet request, custom options, scheduling, or background execution exists.
- Simultaneous duplicate version-1 execution-request hardening remains deferred; database admission indexes deliberately apply only to version-2 SCAN Jobs.
- No materialized equality-group identity, ContentRecord reconciliation/merge, media metadata, perceptual fingerprints, embeddings, face analysis, or AI integration exists.
- No general durable worker, pause/resume, startup recovery, or live progress delivery exists.
- No SSE endpoint or event design exists.
- Active frontend orchestration is browser-local. Refreshing or leaving `/sources` during an in-flight synchronous request cannot reconstruct or resume the remaining sequence; backend retry/recovery semantics remain unimplemented.
- No matching candidates, similarity relationships, materialized groups, manual overrides, or filesystem-action history exists.
- No broader Library/Review UI, review states/flags, persistent categories/tags, AI suggestions, similarity-group UI, or actual filesystem actions exist.
- Advanced sorting is deferred; the frontend preserves deterministic server digest ordering rather than sorting only loaded pages.
- Retry/resume semantics, request-key use, public version-2 read/start APIs, scheduling concurrency beyond the one-active constraint, merge behavior, cache locations, and FFmpeg/ffprobe discovery remain open as documented.
- The frontend still has no automated test framework. This increment was validated with lint, a production TypeScript/Vite build, and focused server-render/API fixture checks; durable component tests remain a future testing-infrastructure decision.

## Next Recommended Step

After reviewing this synchronous internal lifecycle, add the approved background executor and startup interruption finalization as a separate milestone. Public start/read polling and frontend cutover should remain separately reviewable; no current UI should use v2 until those contracts exist.
