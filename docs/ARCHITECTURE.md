# Architecture

## Implementation Status

The repository currently contains a working full-stack scaffold:

- A React single-page frontend in `frontend/`.
- A Spring Boot REST backend in `backend/`.
- One local SQLite database configured through Spring JDBC and Flyway.
- `GET /api/health`, returning plain text `ok`.
- REST endpoints to register and read Sources under `/api/sources`.
- REST endpoints to create and read durable scan requests under `/api/scan-runs`.
- A singleton execution subresource that creates and reads the initial durable Job handoff for a ScanRun.
- A synchronous command that executes the pending DISCOVERY stage for a ScanRun.
- A synchronous command that executes the pending RECONCILIATION stage and completes a ScanRun.
- A synchronous database-only command that assigns ContentRecords to eligible completed-scan observations.
- A synchronous command that publishes exact SHA-256 analysis for safe assigned-content candidates.
- An internal background version-2 handoff using the synchronous coordinator whose one SCAN Job owns DISCOVERY, RECONCILIATION, CONTENT_ASSIGNMENT, and CONTENT_HASHING.
- Read-only APIs that derive exact duplicate groups and retained occurrences from trusted SHA-256 artifacts, with catalog-correct file-category and extension filtering.
- Frontend `/sources`, `/duplicates`, and `/duplicates/:digestHex` routes for Source registration/indexing and derived exact-group browsing, in addition to the `/` health route.

The reviewed persistence foundation is implemented. Flyway migration `V1__create_core_schema.sql` creates the eleven application tables, structural constraints, foreign keys, and initial indexes. Java migration `V2__add_file_entry_extension_key` adds and backfills normalized FileEntry extension metadata plus its lookup index without adding a table. SQL migration `V3__add_durable_indexing_foundation.sql` adds durable indexing fields and constraints, and V4 adds nullable `analysis_record.result_json`; the schema still has eleven tables. Simple immutable records and Spring JDBC repositories provide focused persistence under the `catalog`, `scan`, `job`, `analysis`, and `matching` feature packages. Source registration/read, both execution versions, the four-stage version-2 lifecycle, background execution/recovery, public v2 start/polling APIs, frontend polling, exact analysis, derived duplicate reporting/filtering, reusable media-metadata persistence/safety mechanics, pure ffprobe video-output interpretation, and bounded ffprobe process execution are implemented. Legacy v1 scan APIs remain available but are no longer used by `/sources`.

## Architectural Style

Media Compare will begin as a modular monolith:

- One Spring Boot backend.
- One React frontend.
- One SQLite catalog.
- No microservices, message queues, Docker requirement, or separate worker process initially.

Responsibilities remain meaningfully separated inside the applications without creating elaborate layered architecture. The initial responsibility areas are catalog/indexing, scanning/reconciliation, analysis, matching, jobs/progress, organization/manual decisions, filesystem operations, media tooling, and AI integrations.

## Implemented Persistence Boundaries

The first concrete persistence schema contains exactly these eleven tables:

```text
source
file_entry
content_record
working_set
working_set_content
scan_run
scan_run_source
job
job_stage
analysis_record
content_hash
```

The implemented fields, constraints, indexes, foreign-key direction, and remaining design boundaries are recorded in [`DATA_MODEL.md`](DATA_MODEL.md).

The initial Java package structure is:

- `catalog` — Source, FileEntry, ContentRecord, WorkingSet, and related persistence.
- `scan` — ScanRun, ScanRunSource, and reconciliation coordination.
- `job` — generic durable execution and stage state.
- `analysis` — AnalysisRecord and reusable specialized analysis artifacts.
- `matching` — read-only exact byte-equality grouping and reporting projections.
- `web` — thin REST controllers and later HTTP/SSE endpoints.

The persistence foundation uses concrete Spring JDBC repositories per feature package: `CatalogRepository`, `ScanRepository`, `JobRepository`, `AnalysisRepository`, and `ExactDuplicateRepository`. The boundaries remain simple. `catalog` does not depend on the job runner; `job` remains generic; and `analysis` owns analysis provenance. Scan-specific orchestration that depends on both ScanRun and Job concepts stays in `scan`, not `job`. The web controllers are thin HTTP boundaries over small feature services, while `HealthController` remains unchanged. No generic repository framework, automatic interface/implementation pairs, or enterprise layering was introduced.

## Catalog and Identity

The catalog represents files generally, including images, video, audio, documents, archives, and miscellaneous or unknown files. Cheap filesystem/catalog processing can apply broadly; expensive analysis applies only to selected and supported media.

A `Source` is a persistent registered scan root such as a folder, external drive, whole drive, or other filesystem root. It has a durable database identity. `root_path` and `root_path_key` are location/configuration data, not identity; `root_path_key` is an application lookup aid, and neither field is unique. A matching path or path key must not automatically establish that a previously registered Source is the same Source that has returned. `location_revision` records changes to the configured location. Source relocation and remount recognition remain later concerns. Platform-specific volume, filesystem, file-ID, or inode information may later assist as optional hints only and can never be required cross-platform identity.

The initial Source registration behavior preserves the supplied root-path string and writes the same value to `root_path_key`. It uses Java NIO only to check that the path is syntactically valid and absolute for the backend host. Registration does not inspect filesystem availability, require the path to exist or be a directory, resolve symlinks, or canonicalize the path. Duplicate names and root paths are allowed because database ID, not path, establishes Source identity.

A `FileEntry` represents one filesystem occurrence within a Source. It stores a Source-relative path, normalized nullable technical `extension_key`, filesystem metadata, current content association, presence, first/last-seen information, observation revision, and the scan traversal that last observed it. Extension extraction uses the basename suffix after the last dot, lowercases with locale-independent rules, and does not alter portable path identity. The uniqueness rule is `UNIQUE(source_id, path_key)`.

When a FileEntry records `last_seen_scan_run_source_id`, that ScanRunSource must belong to the FileEntry's own Source. V1 keeps the simple foreign key and its `ON DELETE SET NULL` behavior; `CatalogRepository` enforces the cross-table Source match transactionally before insertion rather than adding a composite foreign key or trigger.

The path policy is cross-platform and lossless: preserve observed case and Unicode spelling, use `/` between persisted relative path segments, do not globally lowercase or Unicode-normalize, and do not resolve symlinks or call `toRealPath()` to construct occurrence identity. Where filesystem equivalence is uncertain, preserve separate observations.

A `ContentRecord` represents one immutable byte-version independently of location. It has a stable internal ID rather than a hash primary key. Initial assignment creates one distinct record for each eligible unassigned FileEntry occurrence/version; equal size, modification metadata, or bytes do not cause records to be shared. Exact hashes attach to those identities as analysis artifacts. Equal digests do not merge records; later equality grouping and merge/deduplication behavior remain deferred. V1 has no canonical redirect or merge table, and transformed copies have separate ContentRecords.

## Reconciliation and Execution

Revisiting a Source performs lightweight discovery and reconciliation before expensive analysis. Each root traversal receives a fresh positive traversal generation. A missing-file sweep is permitted only after a complete successful traversal of the intended scope. Interrupted, cancelled, incomplete, inaccessible, or offline scans do not mark previously known files missing. The missing update and completed-generation state are committed together.

`observation_revision` allows future workers to reject stale publication after a FileEntry may have changed. A later new ScanRun may rediscover after application shutdown; fragile filesystem iterator cursors are not persisted. Directory-level traversal checkpoints remain deferred.

The implemented DISCOVERY command is manually and synchronously invoked through `POST /api/scan-runs/{id}/execution/discovery`. Before filesystem work or state mutation, it verifies the ScanRun Source snapshots against every current Source location revision and verifies that the SCAN Job and DISCOVERY stage are pending and eligible. It then starts the ScanRun, Job, stage, and Source traversal state in a short transaction.

Each Source root is traversed recursively with Java NIO `Files.walkFileTree(...)` without opting into link following. Only regular-file entries are observed. Persisted relative paths are derived with NIO relativization, serialize path segments with `/`, preserve observed spelling, and initially serve unchanged as `path_key`. Size and modification epoch-second/nanosecond metadata are captured without millisecond truncation. Discovery derives `extension_key` from the persisted relative path on insert and re-observation; a path-spelling update therefore keeps the extension synchronized. Discovery creates or refreshes FileEntry occurrences only; it creates no ContentRecord, hash, or analysis row.

File observations and progress are committed in transactions of at most 250 files. No database write transaction spans filesystem traversal. A first traversal advances generation from zero to one. Successful discovery leaves each ScanRunSource `DISCOVERED` with `completed_generation` and `completed_at_ms` still null, completes DISCOVERY, and creates one pending RECONCILIATION stage while the ScanRun and Job remain `RUNNING`.

The implemented RECONCILIATION command is manually and synchronously invoked through `POST /api/scan-runs/{id}/execution/reconciliation`. It performs no filesystem access and does not revalidate Source paths or location revisions. Preflight instead requires the durable ScanRun, SCAN Job, DISCOVERY and RECONCILIATION stages, and every ScanRunSource to be at the expected successful phase boundary.

For each Source, the exact pair `(last_seen_scan_run_source_id, last_seen_traversal_generation)` identifies FileEntries observed by its completed discovery traversal. Reconciliation marks other currently `PRESENT` entries for that Source `MISSING`, including entries with null last-seen traversal fields. It changes only presence: last-known metadata, traversal identity, content association, and observation revision are preserved. Already-`MISSING` entries and entries belonging to other Sources are not changed.

Each Source's missing sweep, transition to `COMPLETED`, `completed_generation` publication, completion timestamp, and Source-based Job/stage progress update commit in one transaction. Separate short transactions start and finalize RECONCILIATION. Starting the stage resets Job progress to the current stage's Source units without incrementing the Job attempt count. Successful finalization completes RECONCILIATION, the Job, and the ScanRun; clears the Job's current stage; and creates no later stage. Generic persistence-failure recovery remains deferred.

The implemented ContentRecord-assignment command is synchronously invoked through `POST /api/scan-runs/{id}/content-assignment`. Preflight requires a fully completed ScanRun, SCAN Job, DISCOVERY stage, RECONCILIATION stage, and completed generation for every ScanRunSource. The command then uses only durable database state: it does not inspect Source configuration, access Source roots, or revalidate location revisions, and it neither reopens the completed SCAN Job nor creates a Job or JobStage.

For each completed ScanRunSource, eligible `PRESENT`, content-null FileEntries must carry that Source row's exact completed traversal identity. Candidates contain only FileEntry ID, observation revision, and size and are read in ascending-ID keyset pages of at most 250. Each candidate receives its own ContentRecord; metadata is not treated as evidence of byte equality. Publication uses a separate transaction-proxied writer that inserts the ContentRecord and conditionally attaches it only while the FileEntry remains `PRESENT`, content-null, at the expected observation revision and size. A stale conditional update rolls back its insert, is counted as skipped, and does not stop later candidates. The publication guard deliberately does not require an unchanged last-seen traversal pair, so a later unchanged observation can retain the same occurrence version. Existing content associations make the operation resumable and repeatable without duplicate records.

The implemented exact-hashing command is synchronously invoked through `POST /api/scan-runs/{id}/content-hashing` and requires the same fully completed scan lifecycle. It selects `PRESENT`, assigned FileEntries from each ScanRunSource's exact completed traversal in ascending-ID keyset pages of at most 250. Candidate snapshots retain ContentRecord identity, path, observation revision, exact size/mtime, and the Source-location revision captured by the scan.

The exact built-in analysis key is `CONTENT_HASH` / `builtin.sha256` / analyzer version `1` / configuration version `1` with `{}` and its lowercase SHA-256 configuration hash. A valid completed exact-key artifact is reused before filesystem access. New work reconstructs persisted `/`-separated paths component by component, rejects unsafe components, rejects symbolic links in the Source root, parent path, or candidate, requires a regular file, checks exact size and nanosecond mtime before and after streaming SHA-256, and never loads the whole file into memory. Source relocation and other stale evidence are skipped; ordinary candidate filesystem failures are counted and do not stop later candidates.

Digest publication uses a separate transaction-proxied writer. It rechecks FileEntry ID, Source, presence, current ContentRecord, observation revision, size, exact mtime, and Source location revision, then atomically inserts the completed AnalysisRecord and its ContentHash. Last-seen traversal identity is deliberately excluded from this final guard because a later unchanged scan does not invalidate the same occurrence version. The public version-1 hashing command does not modify execution state; the internal version-2 stage records its final summary after hashing returns. Equal digests remain separate artifacts on separate ContentRecords.

The internal version-2 path atomically creates a pending version-2 SCAN Job and DISCOVERY stage for an eligible pending INDEX ScanRun. Repository and lifecycle lookups always include `execution_version`, so historical version-1 and version-2 Jobs can coexist without ambiguous selection. Each stage is claimed with conditional affected-row checks. Discovery and reconciliation reuse their existing algorithms; reconciliation completes ScanRunSource traversal evidence but advances the Job to CONTENT_ASSIGNMENT while the ScanRun remains `RUNNING`. Assignment and hashing reuse their existing paged algorithms, with filesystem traversal and byte streaming outside transaction boundaries. Short transactions claim stages and atomically publish each final result with its transition.

Assignment persists `{"version":1,"assignedCount":n,"skippedCount":n}` and hashing persists `{"version":1,"hashedCount":n,"cachedCount":n,"skippedCount":n,"failedCount":n}` in `job_stage.result_json`. Narrow typed codecs reject missing, extra, malformed, incompatible-version, or negative-count data. Hash candidate skips/failures remain a completed stage and lifecycle with issue counts. A whole-stage failure transactionally marks the current stage, Job, and ScanRun `FAILED`, clears the Job current stage, stores a safe message, and prevents later-stage creation. The synchronous coordinator invokes the four services without a transaction spanning the pipeline.

Exact duplicate groups are derived synchronously through `GET /api/exact-duplicate-groups` and `GET /api/exact-duplicate-groups/{digestHex}`. No group or membership table is materialized. A group exists only when at least two distinct ContentRecords have the same completed, provenance-compatible, structurally valid built-in SHA-256 artifact. Singleton hashes and other analyzer definitions are excluded. Completed exact artifacts with missing or malformed specialized results, inconsistent configuration JSON, or same-digest ContentRecords with conflicting sizes are reported as integrity failures rather than silently hidden or repaired.

List queries use ascending digest keyset pagination and digest-led grouping through the existing `(algorithm, digest_hex)` index. Optional repeated category/extension filters select eligible groups through matching retained FileEntry occurrences before cursor comparison and limiting; both `PRESENT` and `MISSING` evidence can match. OR applies within a dimension and AND between dimensions. Once selected, summaries retain complete-group membership, occurrence, Source, size, and savings values; nullable `filterMatch` describes only the matching occurrence count and extensions. ContentRecord cardinality is computed before FileEntry occurrence joins so multiple occurrences cannot inflate membership.

Detail reads always return distinct ContentRecord members and all retained current FileEntry associations, including `MISSING` entries, even when an active filter matches none. Occurrences expose their normalized extension, derived technical `FileCategory`, and match flag. The catalog-wide filter-options endpoint counts extensions across retained occurrences in valid exact groups. `FileCategory` is a small backend classifier (`PHOTO`, `VIDEO`, `DOCUMENT`) derived from extension metadata, not persisted, and is separate from future user Tags/Categories. The schema cannot reconstruct superseded associations that are no longer retained. The potential-storage-savings value is an estimate of logical present-occurrence bytes, not actual recoverable filesystem allocation. Grouping and filtering perform no filesystem access, hashing, or durable mutation.

The React frontend consumes these endpoints through small typed API modules and a shared JSON/HTTP error boundary. `/sources` lists and registers Sources using the backend's public fields and explicit absolute-path contract. One Analyze action starts a durable version-2 indexing run through `/api/indexing-runs`; the backend owns stage sequencing and the page reconstructs state through the source-status and detail polling APIs. Persisted stage labels, progress, typed summaries, and safe failures remain authoritative. Source registration and the indexing frontend perform no filesystem mutation.

`/duplicates` exposes multi-select Photos, Videos, and Documents controls plus extensions loaded independently from the filter-options endpoint. Active filters are canonical repeated URL parameters; unsupported categories are removed while selected extensions absent from current options remain visible and requested. Category choices use OR, extension choices use OR, and the two dimensions combine with AND according to the backend contract.

The list appends keyset pages with duplicate-digest protection and retains loaded rows plus scroll position in browser memory only when the current URL has the same order-insensitive canonical filter key. Filter changes mount a fresh result state, reset cursor/scroll/errors, and start at the first page. Filtered cards use `filterMatch` to describe matching and additional retained occurrences while leaving whole-group values unchanged. Filter-option failure does not block list or category use.

The detail route preserves URL filters, uses the full digest as identity while displaying a non-authoritative `DUP-` label from its first eight hexadecimal characters, and requests the complete group with match context. It highlights matching occurrences, keeps every nonmatching occurrence visible, and derives whole-group extension display from backend occurrence extensions. A bounded, session-memory-only trail records duplicate-group visits and preserves current filters. None of this frontend state is persisted.

Filesystem failure marks every ScanRunSource participating in that started DISCOVERY attempt, the DISCOVERY stage, Job, and ScanRun failed without creating RECONCILIATION or marking any FileEntry missing. This ensures no child of the terminally failed attempt remains `DISCOVERING`. Earlier committed observation batches remain tagged with their incomplete generations; `completed_generation` remains null, so those partial observations do not authorize a missing sweep. Public v1 retry/recovery is not implemented; internal v2 interruption recovery is described below.

`ScanRun` records user intent, selected Sources or WorkingSet, request type, options, and lifecycle. Its options become immutable when execution begins. The first implemented request creation accepts one or more registered Source IDs and atomically writes one ScanRun plus its ScanRunSource rows. It uses request type `INDEX`, initial status `PENDING`, options version `1`, and effective options `{}`. Each child begins `PENDING`, snapshots the Source's current location revision, and has traversal generation `0` with no completion or execution state. Child responses are ordered by Source ID.

`Job` is the durable execution authority and may later execute scans, analysis, filesystem actions, exports, or other long-running work. Creating a ScanRun records intent only and creates no Job. A separate execution handoff for an existing ScanRun atomically creates one `SCAN` Job and one `DISCOVERY` JobStage, both `PENDING`, with the Job's current stage set to `DISCOVERY`. Their shared creation timestamp is populated while progress, attempts, and execution timestamps remain at initial values. The ScanRun and its ScanRunSource rows are not mutated by the handoff.

The current v1 API permits at most one service-created `SCAN` execution handoff per ScanRun and returns HTTP 409 for a duplicate POST. This rule is enforced by `ScanExecutionService`; the generic Job schema does not impose `UNIQUE(scan_run_id)`. Both creation services now reserve the SQLite writer before ownership checks, preventing normal concurrent v1/v2 ownership or duplicate v1 creation. `JobStage` remains an aggregate checkpoint for one stage type within a Job, with `UNIQUE(job_id, stage_type)`; retries and resume update that row. Additional lifecycle values and execution behavior remain implementation details.

V3 provides `scan_run.request_key` for durable public start-request idempotency, distinguishes public/historical version-1 Jobs from internal full-pipeline version-2 Jobs with `job.execution_version`, and provides nullable `job_stage.result_json` for bounded, typed, versioned assignment and hashing summaries. Partial unique indexes allow at most one version-2 SCAN Job per ScanRun and at most one globally active (`PENDING` or `RUNNING`) version-2 SCAN Job. They do not constrain version-1 Jobs or unrelated Job types. The internal v2 creation service translates database constraint races to a domain conflict; none of the new internal fields or services changes existing public response shapes.

### Internal Background Execution and Startup Safety

`CatalogConfiguration` acquires `CatalogOwnership` before constructing the datasource, so even Flyway writes require ownership. The lock derives from `spring.datasource.url`: resolve the local catalog path (including existing symlinks), append `.lock`, and hold a Java NIO `FileChannel.tryLock()` OS lock. A second backend fails startup clearly. The file is not deleted on release. Plain SQLite paths and local `file:` URIs are supported; in-memory SQLite catalogs have no cross-process persistence and need no file lock. Local filesystem use is required; hard-link aliases are not a supported way to configure the same catalog.

After Flyway, `Version2IndexingStartup` calls a transaction-proxied recovery method for each active v2 SCAN Job. The executor depends on successful startup recovery, making ownership → schema → recovery → submission deterministic. Impossible lifecycle state aborts startup and logs Job/ScanRun/stage IDs rather than guessing a repair.

Recovery fails each `PENDING`/`RUNNING` Job, clears its current stage, and fails its nonterminal ScanRun with `Execution interrupted by application restart` and a shared finish timestamp. Only the actual `RUNNING` stage is failed. Never-started stages stay `PENDING`, completed stages and their final results remain unchanged. During discovery, actively `DISCOVERING` Source rows become `FAILED` without reconciling or removing observations. At/between reconciliation boundaries, `DISCOVERED` rows remain as successful discovery evidence (not active traversal), and already `COMPLETED` Source rows retain their committed missing sweeps and generations. Assignment associations and published hash artifacts are untouched. Terminal Jobs and version-1 Jobs are not recovered. No stage is automatically resumed or retried; a later attempt needs a new ScanRun.

`Version2BackgroundIndexingService.start(scanRunId)` rejects ambient transactions, calls the existing transactional v2 creation service, then submits only after that call commits. It returns durable ScanRun/Job identity immediately. Public acceptance reuses its already-accepted submission path, described below. `Version2IndexingExecutor` owns a dedicated `ThreadPoolExecutor`: core/max 1, queue 1, named thread, abort rejection, never caller-runs. The worker invokes the existing `Version2ScanExecutionService.run(...)` without a pipeline-wide transaction. Escaping failures inspect durable state and finalize still-active execution; already-finalized stage failures remain unchanged. Rejected submission fails the accepted Job/ScanRun with `Execution could not be scheduled`, retaining the pending stage and releasing admission.

Shutdown closes submission, waits up to 30 seconds, then interrupts active work and discards queued tasks; unfinished durable attempts are recovered on next startup. Checks between stages, discovery batches/directories, assignment pages/candidates, and hash candidates/stream chunks propagate interruption rather than counting it as a failed candidate. If a worker still has not terminated at the deadline, the OS lock is conservatively retained until process exit, even if the Spring context closes. Filesystem calls are not guaranteed interruptible. If the failure-finalization transaction itself cannot persist, an error is logged and startup recovery is required; it does not retry work.

Public v2 start/read and idempotency contracts are described below; cancellation, SSE, and stage-instance history remain deferred.

### Internal Image-Metadata Job

`MediaMetadataBackgroundService.start()` is the internal handoff for a catalog-global `MEDIA_METADATA` Job at execution version 1. The Job has no ScanRun and owns one `IMAGE_METADATA` stage. `MediaMetadataRunController` exposes the narrow manual start/read API at `/api/media-metadata-runs`; no automatic post-SCAN trigger calls this boundary. Exclusive catalog ownership permits process-local serialization around the short, SQLite write-reserved creation transaction; together they admit at most one active metadata Job without consuming or changing the independent version-2 SCAN admission slot.

A dedicated core/max-one executor with one queue slot and abort rejection runs bounded ContentRecord keyset pages of 100 through `ImageIoMediaMetadataAnalyzer`; occurrence paging, filesystem pre/post validation, and extraction outside transactions remain unchanged. The stage stores only versioned aggregate counts for attempted, available, unsupported, failed, and stale/unavailable candidates. Current corrupt/unreadable content becomes a safe per-content `FAILED` AnalysisRecord and processing continues; stale evidence creates no artifact. Unexpected enumeration, persistence, or worker failure instead fails the stage and Job.

The existing unique analysis cache identity is reused for retries. `FAILED` rows are candidates for later Jobs and transition in place with an incremented attempt count; success clears the error and stores typed result JSON. `COMPLETED` remains reusable, while `PENDING`/`RUNNING` remains owned. Startup changes only abandoned nonterminal rows for the exact ImageIO definition to retryable `FAILED`, fails abandoned active metadata Jobs, and preserves completed artifacts. Rejected scheduling fails both the accepted Job and stage. A narrow manual REST API exposes metadata Job start/read; automatic scheduling, ffprobe execution/video publication, and frontend display remain deferred.

## Public Version-2 Start and Polling

`POST /api/indexing-runs` accepts a client UUID request key and 1–1,000 distinct positive Source IDs. UUID input must have the full 8-4-4-4-12 hexadecimal shape; uppercase is normalized to lowercase and whitespace/shortened forms are rejected. The canonical payload is `INDEX` plus the sorted unique Source ID set. Unknown Sources retain the existing 404 convention; malformed requests return 400.

`IndexingRunService` is nontransactional (`NEVER` rejects an ambient transaction). It returns existing same-key/same-payload state without scheduling. For a new key, transaction-proxied `IndexingRunAcceptance` reuses ScanRun validation/creation and v2 Job creation in one transaction. A zero-row SQLite UPDATE reserves the writer before validation/ownership reads, avoiding a deferred read-to-write lock upgrade race; it changes no row. Both v1 and v2 execution creation use that boundary to exclude competing service-created ownership. Historical repository fixtures may still contain both versions for version-aware reads.

The V3 unique request-key index is final authority: a losing insert rolls back before the winner is reloaded and compared. Only the exact SQLite UNIQUE failure for `scan_run.request_key` means replay. Exact v2 Job uniqueness failures mean admission conflict; other integrity failures remain safe 500 errors. A different new key while v2 is active returns 409 and rolls back the entire request, including Source membership and request key. No new migration/index is required.

After acceptance commits, the service submits the already-created execution through the existing background service/executor. New requests return 202 with a resource Location; replays return 200, including completed and failed runs. Same-key payload mismatch returns 409. Submission rejection returns 503 after durable failure finalization; replay returns that failed attempt. A crash before submission is covered by existing startup interruption recovery. There is no automatic retry/resume, and a new attempt requires a new key.

`GET /api/indexing-runs/{scanRunId}` uses a short read transaction to return a consistent ScanRun/Job/Source/stage snapshot. It returns 404 for unknown or non-v2 indexing resources. Public DTOs omit raw result JSON and internal result versions; existing codecs parse final assignment/hash summaries. Malformed summaries or incompatible lifecycle boundaries yield safe 500 responses with durable IDs logged. Counts and totals come from persisted state, not invented percentages. `completedWithIssues` is true only for a completed Job whose final hashing has nonzero skipped/failed counts; durable status remains `COMPLETED`.

`GET /api/indexing-runs/source-status` uses two queries in one read transaction: the globally active v2 summary and a window-ranked latest v2 execution per registered Source. Latest orders by ScanRun creation time then ScanRun ID descending. Source rows are ascending ID, include null latest values, and may share a multi-Source execution. Existing relationship/admission indexes are retained; there is no per-Source query loop. Summaries omit full stage arrays. Both GETs return `Cache-Control: no-store` and perform no mutation or long polling.

The current React `/sources` flow uses these v2 routes; existing v1 routes/DTOs remain compatible. SSE, cancellation, automatic retry/resume, and multi-Source UI remain deferred.

## Working Sets and Analysis

A `WorkingSet` is a persistent logical collection of `ContentRecord` membership for repeated comparison and organization workflows. Replacing the bytes at a FileEntry does not silently replace historical WorkingSet membership. Physical copies can become missing while content identity, membership, and reusable analysis remain useful. Saved indexes use the one catalog database rather than separate database files.

`AnalysisRecord` captures reusable analysis provenance, lifecycle, analyzer identity/version, configuration version/hash, and effective configuration. A compatible completed artifact can be reused; changes to analysis type, analyzer, model, version, preprocessing, provider, or configuration create a new artifact rather than silently overwriting the prior result. Specialized result structures hold hashes, media metadata, fingerprints, embeddings, face results, video fingerprints, and AI results as those features are designed.

Media-metadata analysis has a strict version-1 result codec and reusable pre-extraction mechanics. The implemented `builtin.imageio` analyzer uses supplied identity/version/configuration provenance and pages distinct ContentRecords by ascending ID and separately pages their current `PRESENT` FileEntries by ascending ID. This keeps both reads bounded while allowing a stale or missing occurrence to fall through to another occurrence of the same ContentRecord. Compatible `COMPLETED` results are reusable without a current occurrence; compatible `PENDING`/`RUNNING` rows remain owned, and compatible `FAILED` rows are retryable in place. Equal exact hashes on different ContentRecords do not share metadata artifacts.

An occurrence snapshot carries the Source root/location revision, portable path, FileEntry observation revision, FileEntry and ContentRecord sizes, and exact mtime. A nontransactional filesystem boundary applies the exact-hash path, symlink, regular-file, size, and nanosecond-precision mtime checks both before and after extraction. The ImageIO extractor creates an `ImageInputStream`, selects a reader from bytes rather than extension, reads encoded width/height without full-image decoding, canonicalizes only JPEG/PNG/GIF/BMP/TIFF, and closes/disposes resources. No reader or unsupported canonical format becomes typed `UNSUPPORTED`; reader/decoder failures remain explicit extraction failures. Publication then uses a short transaction to revalidate the Source, FileEntry, and ContentRecord evidence before inserting a typed `COMPLETED` result. Stale evidence creates neither a successful nor failed artifact.

The pure `FfprobeMediaMetadataInterpreter` establishes the independent `builtin.ffprobe` version-1 analysis definition and accepts only structured JSON text, never a filename or path. Strict parsing projects only format identity/duration/ISO BMFF brands plus required stream fields and returns an explicit completed or failed interpretation. Successful broad container detection is accepted without an application-level positive video allowlist, while image demuxers, image-oriented ISO BMFF brands, attached pictures, thumbnails, still-image streams, and external presentations are excluded. Primary video/audio selection prefers the lowest-index default stream and otherwise the lowest index.

`FfprobeProcessRunner` is the separate non-persistent subprocess boundary. The optional `media-compare.ffprobe.executable` property must select one exact executable by absolute path; when the property is absent, the command name is `ffprobe` and normal PATH lookup applies. The runner lazily qualifies that exact command with bounded version, `fd` input-protocol, and required MOV-control checks and never falls back after an explicit selection fails. Executable location, installed version, and runtime limits remain operational rather than analyzer compatibility inputs.

The probe invocation uses `ProcessBuilder` arguments without a shell, redirects the already-validated regular file to stdin, and gives ffprobe only the input URL `fd:` with `-protocol_whitelist fd`. MOV data references and absolute external paths are explicitly disabled, and only the interpreter's required format, tag, stream, and disposition fields are requested as JSON. Stdout and stderr drain concurrently under independent 4 MiB and 256 KiB caps, with a 30-second process timeout and bounded graceful then forced cleanup. Reader overflow/failure wakes the owning execution path promptly so it performs the same bounded cleanup rather than waiting for the probe timeout. Stdout is strictly decoded as UTF-8. An individual probe's nonzero exit, invalid UTF-8, timeout, or output-limit breach is a typed content/probe failure with an exit code only when one exists; process start/setup, reader, and cleanup failures are infrastructure failures. Caller interruption instead cleans up and propagates with interrupt status preserved. This restriction is not an OS sandbox, and Java redirection cannot close the pre-validation/open race, so future analyzer composition still requires existing pre/post filesystem checks and transactional publication revalidation. No AnalysisRecord publication, VIDEO_METADATA Job stage, or automatic metadata scheduling is implemented yet.

`content_hash` is the specialized exact-hash result. It uses canonical algorithm identifiers and lowercase hexadecimal digests. `(algorithm, digest_hex)` is indexed but not unique because temporary duplicate ContentRecords may exist. The exact duplicate view groups valid compatible artifacts dynamically by digest rather than assigning a durable group identity.

Filesystem metadata belongs to FileEntry. Media-derived metadata belongs to ContentRecord analysis so moves and renames do not invalidate compatible analysis. Large derived files such as thumbnails, previews, extracted frames, and intermediates belong in a future managed cache rather than the SQLite catalog.

Face analyzer output, detected face instances, and embeddings remain conceptually separate from later human person or group classification. AI analysis follows the same provenance and versioning rules: it is optional and provider-independent, and local and cloud providers may coexist without making the rest of the catalog depend on one provider. Face/person schemas, AI result schemas, provider interfaces, and runtime architecture remain undecided.

## Matching and Scale

The design targets thousands, tens of thousands, and potentially hundreds of thousands of files. Implementations should stream Java NIO traversal, use bounded database batches and indexed queries, avoid loading complete drive listings into memory, and avoid expensive work for files that do not need it.

Exact byte-equality grouping is the first implemented matching behavior and uses indexed digest aggregation rather than pairwise comparison. Broader matching uses cheap candidate generation followed by deeper comparison for plausible candidates. Full pairwise comparison is not a V1 strategy. Candidate persistence and later matching algorithms remain open, while pending work must eventually support durable resume.

## Broader Conceptual Relationship View

```text
Source -> FileEntry -> ContentRecord -> AnalysisRecord -> specialized results
                           |
                           -> derived exact duplicate groups
                           -> future relationships/materialized decisions

WorkingSet -> ContentRecord membership

ScanRun -> Job -> stages/checkpoints
```

This shows long-term conceptual ownership and relationships, not only the V1 tables, foreign keys, packages, or final cardinalities. Of specialized analysis-result structures, only `content_hash` is part of the reviewed V1 schema.

## Current Development Request Flow

The implemented local development flow is:

```text
React application on Vite :5173
    -> request to /api
Vite development proxy
    -> Spring Boot :8080
    -> SQLite at backend/data/media-compare.db
```

REST remains the API direction. SSE is planned for later server-to-client live/progress updates; endpoint, event, and recovery design remain open.

The implemented Source vertical slice is:

```text
POST/GET /api/sources
    -> SourceController
    -> SourceService
    -> CatalogRepository
    -> SQLite
```

The API can register a Source, list Sources in database-ID order, and retrieve one Source by ID. It does not yet update, delete, relocate, check availability, scan, or reconcile Sources.

The implemented scan-request vertical slice is:

```text
POST/GET /api/scan-runs
    -> ScanRunController
    -> ScanRunService
    -> CatalogRepository + ScanRepository
    -> SQLite scan_run + scan_run_source
```

Creation validates every selected Source before persistence, then atomically records the ScanRun and Source-location-revision snapshots. It does not access the filesystem, create a Job, or begin execution. Only get-by-ID is implemented; there is no ScanRun list endpoint yet.

The implemented execution-handoff vertical slice is:

```text
POST/GET /api/scan-runs/{id}/execution
    -> ScanExecutionController
    -> ScanExecutionService
    -> ScanRepository + JobRepository
    -> SQLite job + job_stage
```

The POST atomically creates a pending `SCAN` Job and its one pending `DISCOVERY` stage. The GET reads that durable state with stages ordered by database ID. Neither endpoint starts work, checks Source availability, or accesses the filesystem.

The implemented discovery-execution slice is:

```text
POST /api/scan-runs/{id}/execution/discovery
    -> ScanExecutionController
    -> ScanExecutionService
    -> preflight Source snapshots and pending execution state
    -> Java NIO Source traversal outside write transactions
    -> bounded FileEntry/progress transactions
    -> DISCOVERY completion and pending RECONCILIATION stage
```

The implemented reconciliation-execution slice is:

```text
POST /api/scan-runs/{id}/execution/reconciliation
    -> ScanExecutionController
    -> ReconciliationService
    -> preflight durable DISCOVERY results and execution state
    -> per-Source missing sweep + completed generation transaction
    -> RECONCILIATION, Job, and ScanRun completion
```

The implemented content-assignment slice is:

```text
POST /api/scan-runs/{id}/content-assignment
    -> ContentAssignmentController
    -> ContentAssignmentService
    -> completed-lifecycle preflight from durable state
    -> bounded candidate reads
    -> per-candidate atomic ContentRecord insert + guarded FileEntry publication
```

The implemented exact-hashing slice is:

```text
POST /api/scan-runs/{id}/content-hashing
    -> ContentHashingController
    -> ContentHashingService
    -> completed-lifecycle preflight and bounded candidate reads
    -> cache reuse or Java NIO SHA-256 streaming outside transactions
    -> per-candidate atomic guarded AnalysisRecord + ContentHash publication
```

The implemented exact-duplicate slice is:

```text
GET /api/exact-duplicate-groups[/{digestHex}]
GET /api/exact-duplicate-groups/filter-options
    -> ExactDuplicateController
    -> ExactDuplicateService
    -> completed-artifact integrity checks
    -> indexed digest grouping + retained-occurrence filtering + member/occurrence reads
    -> no filesystem access or durable mutation
```

The frontend consumes that slice as:

```text
/sources
    -> typed Source API registration/list
    -> client UUID + one POST to start a single-Source v2 indexing run
    -> Source-status reconstruction plus one active detail polling loop
    -> persisted stage progress, typed summaries, safe failure state, and durable IDs
    -> completion link to /duplicates
/duplicates
    -> typed exact-duplicate API client
    -> URL-backed File Type and Extension controls
    -> filter-keyed digest-keyset Load more list with retained-occurrence match context
/duplicates/:digestHex
    -> complete group summary, ContentRecord members, and retained FileEntry occurrences
    -> per-occurrence filter match context
    -> filter-keyed browser-memory list context and recent-visit trail
```

Source registration and duplicate reads complete within their HTTP requests. Indexing runs in the backend and `/sources` polls durable state every 1.5 seconds only while active, without overlapping requests. Collection refresh uses `/api/indexing-runs/source-status` as indexing authority and one `/api/sources` request for names/paths; there is no per-Source status loop. Retry/resume, materialized equality decisions, and SSE remain deferred.

## SQLite and Cross-Platform Requirements

SQLite remains the single local catalog database. Spring JDBC and Flyway provide persistence access and migration ownership. SQL should enforce structural invariants such as nullability, foreign keys, uniqueness, numeric ranges, and valid nanosecond values. Evolving status and type values should initially be validated in Java rather than rigid SQLite membership checks.

Foreign-key enforcement is enabled through the SQLite JDBC URL's `foreign_keys=on` connection property, so every physical connection receives the setting. Tests verify the PRAGMA on separate simultaneous pooled connections and verify rejection of an invalid reference. Database transactions should be short; filesystem traversal, hashing, FFmpeg calls, and analysis should occur outside write transactions. WAL remains deferred until sustained concurrent reads and analysis writes provide a measured reason to evaluate it.

macOS and Windows are both required. Filesystem handling uses Java NIO and must not assume one separator, drive-letter model, case behavior, Unicode normalization, symlink policy, or mount identity. The ffprobe runner uses an optional explicitly configured absolute executable path or the PATH command `ffprobe`, never a fixed machine path. Its Java helper-process tests are cross-platform, but real Windows acceptance of redirected regular-file stdin, seek-dependent `fd:` probing, handle cleanup, and special paths remains required before runner support is claimed complete there.

## Undecided Areas

The following remain open after the V1 review:

- Additional status, request-type, classification, and stage values beyond the initial ScanRun `INDEX`/`PENDING` creation state.
- Source remount/relocation recognition and filesystem volume hints.
- Final symlink and Windows junction traversal behavior.
- Detailed path equivalence beyond the V1 lossless key policy.
- ContentRecord reconciliation/merge behavior.
- Scheduling beyond the bounded v2 worker, public cancellation/retries, and future resume.
- Detailed scan scope representation and source-specific progress.
- Specialized result schemas beyond `content_hash`.
- Candidate, similarity, materialized relationship/grouping, and manual override schemas.
- Face/person schema and AI-provider architecture.
- Application-managed cache locations and lifecycle.
- Real-Windows ffprobe redirected-file/`fd:` qualification and acceptance.
- Safeguards and workflow for eventual filesystem-modifying operations.

## Planned Product Capabilities

The architecture is intended to support folders, multiple unrelated folders, whole drives, persistent indexing, incremental/reconciliation scans, exact duplicate and transformed-copy detection, similar/related media, resumable analysis, manual grouping/classification overrides, optional face analysis, optional local/cloud AI, and later explicit safeguarded filesystem modification.

Source registration, scan-request/handoff, regular-file discovery, safe missing-file reconciliation, provisional ContentRecord assignment, exact SHA-256 analysis, derived exact duplicate reporting, frontend Source-to-duplicates orchestration, reusable media-metadata persistence/safety mechanics, ImageIO image metadata extraction, the manual metadata Job API, pure ffprobe video-output interpretation, and bounded ffprobe process execution are implemented. Durable video extraction/publication, automatic metadata scheduling, similarity analysis, AI, review metadata, and filesystem cleanup remain deferred.
