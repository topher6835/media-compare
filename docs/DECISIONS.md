# Decisions

This file records decisions already made. It distinguishes the reviewed V1 implementation target from behavior and architecture that remain open.

## Project

- Media Compare is a fresh v2 project rather than a continuation of the old tutorial implementation.
- The repository name is `media-compare`, hosted as `topher6835/media-compare` on GitHub.
- Supporting both macOS and Windows is a hard requirement.

## Stack

- Use Java 21, Spring Boot 4.1.1, and Maven.
- Use React, TypeScript, Vite, React Router, and ESLint.
- Use SQLite with Spring JDBC instead of JPA.
- Use Flyway for schema migrations.
- Use Java NIO for filesystem work.
- Use FFmpeg and ffprobe for future media inspection and processing.
- Use REST APIs, with SSE as the direction for later live/progress updates.
- Allow future pluggable local and cloud AI providers.

## Architecture

- Begin as a modular monolith: one Spring Boot backend, one React frontend, and one SQLite catalog.
- Do not introduce microservices, message queues, a Docker requirement, or a separate worker process initially.
- Catalog files generally, not only media. Apply cheap catalog work broadly and expensive analysis only to selected/supported media.
- Keep internal responsibility boundaries meaningful without creating elaborate layered architecture.
- Give each registered scan Source a durable internal identity. Its root path and path key are location/configuration data, not identity; a path-key match alone cannot identify a returning Source. Platform-specific volume/filesystem IDs, file IDs, or inodes may be optional hints, never required cross-platform identity.
- Represent a FileEntry as a current or historical filesystem occurrence within a Source. Preserve missing history rather than deleting an entry automatically.
- Represent exact bytes with a stable internal ContentRecord identity independent of path and hashing algorithm. Multiple exact duplicate FileEntries may share a ContentRecord; transformed copies must not.
- Treat a trusted exact hash as confirmation of byte identity, with its algorithm recorded explicitly. Size and timestamp are optimization signals, not proof.
- Attach reusable expensive analysis primarily to ContentRecord so it survives moves, renames, duplicates, later scans, and missing/reappearing copies.
- Make resume database-driven and durable across full application shutdown. Prefer idempotent stages and small committed batches over serialized Java state or fragile iterator cursors.
- Represent a saved index as a persistent WorkingSet backed by the one catalog database. WorkingSets reference ContentRecords, reuse their analysis, and retain useful identity/history when physical copies disappear.
- Use a general AnalysisRecord for analysis provenance, version/configuration, and lifecycle, with specialized structures for queryable results rather than placing every result in generic JSON.
- Reuse analysis only when type, analyzer/model/provider, version, and configuration are compatible; preserve prior artifacts when those inputs change.
- Keep filesystem metadata on FileEntry and media-derived metadata in ContentRecord analysis, so moves and renames do not invalidate compatible analysis.
- Keep face analyzer output, detected faces, and embeddings separate from later human person or group classification.
- Keep AI optional and provider-independent; local and cloud providers may coexist under the same provenance/versioning model.
- Keep large derived files in a future application-managed cache rather than as large SQLite BLOBs; SQLite holds the catalog and compact/queryable artifacts.
- Generate plausible matching candidates cheaply before deeper comparison. Do not use full pairwise comparison for large libraries, and ensure pending candidate/deep-analysis work can eventually resume durably.

## Reviewed V1 Persistence Target

The first concrete schema is implemented by `V1__create_core_schema.sql` and contains exactly these eleven tables:

`source`, `file_entry`, `content_record`, `working_set`, `working_set_content`, `scan_run`, `scan_run_source`, `job`, `job_stage`, `analysis_record`, and `content_hash`.

The reviewed direction is:

- `Source` has a durable database identity. `root_path` and `root_path_key` are location/configuration data and neither is unique; a matching path or path key alone does not establish a returning Source. A location revision records configuration changes.
- `FileEntry` represents a filesystem occurrence within a Source. It uses a Source-relative path, a lossless application-generated `path_key`, and `UNIQUE(source_id, path_key)`. Observed case and Unicode spelling are preserved; V1 does not globally lowercase, Unicode-normalize, resolve symlinks, or use `toRealPath()` for occurrence identity.
- A non-null FileEntry last-seen ScanRunSource must belong to the same Source. V1 enforces this transactionally in `CatalogRepository` while retaining the simple foreign key and `ON DELETE SET NULL` behavior.
- `ContentRecord` represents one immutable byte-version with an internal ID independent of exact hashes. Temporary duplicate records are allowed. V1 has no canonical redirect or merge table.
- `WorkingSet` membership is ContentRecord-based and remains useful when physical copies disappear. Replacing bytes at a FileEntry does not silently replace historical membership.
- `ScanRun` records user intent and immutable-on-start options. `ScanRunSource` records per-Source processing, location revision, traversal generation, and completion.
- Each root discovery traversal receives a fresh generation. A non-null completed generation is positive and cannot exceed the current traversal generation, which may advance while newer work is in progress. Missing-file sweeps run only after a complete successful traversal of the intended scope, and the sweep plus completed-generation state commit together. Incomplete, cancelled, inaccessible, or offline scans do not mark entries missing.
- `Job` is the durable execution authority. `JobStage` is an aggregate checkpoint per stage type within a Job, with `UNIQUE(job_id, stage_type)`. Retries and resume update that row; stage-instance and attempt-history tables are deferred.
- `AnalysisRecord` stores non-null provenance and cache identity: ContentRecord, analysis type, analyzer identity/version, configuration version/hash, and effective configuration. Only completed artifacts with complete specialized results are reusable. Changed provenance creates a new artifact.
- `content_hash` is a specialized exact-hash result with canonical algorithm identifiers and lowercase hexadecimal digests. `(algorithm, digest_hex)` is indexed but not unique.
- Application lifecycle timestamps use epoch milliseconds. Filesystem modification time uses epoch seconds plus a nanosecond component when available.
- SQL enforces structural invariants, foreign keys, uniqueness, ranges, and numeric validity. Evolving lifecycle/type values are not constrained by rigid SQLite membership checks and will be validated in Java when their workflows are implemented.
- Foreign-key deletion behavior preserves historical catalog evidence and reusable analysis. Pure dependent rows may cascade when their owner is intentionally deleted.
- Initial indexes cover content occurrences, Source reconciliation, reverse WorkingSet membership, Source scan activity, Jobs by ScanRun, and exact-hash lookup. Speculative indexes are deferred.

## Java Package Direction

The preferred initial feature-oriented boundaries are:

- `catalog` — Source, FileEntry, ContentRecord, WorkingSet, and persistence.
- `scan` — ScanRun, ScanRunSource, and reconciliation coordination.
- `job` — generic execution and stage state.
- `analysis` — reusable analysis/provenance and specialized artifacts.
- `web` — thin REST controllers and later HTTP/SSE endpoints.

`catalog` must not depend on the job runner. `scan` may coordinate catalog and jobs. `job` remains generic. Automatic interface/implementation pairs and unnecessary enterprise layering are not decisions.

The initial persistence implementation uses immutable Java records for row-shaped domain data and one focused concrete Spring JDBC repository per feature package. SQLite foreign keys are enabled on every physical connection with the JDBC URL's `foreign_keys=on` property. No ORM, generic repository framework, or new dependency was added.

## Initial Source Registration API

- Expose Source registration and reads through `POST /api/sources`, `GET /api/sources`, and `GET /api/sources/{id}`.
- Keep the HTTP controller thin and place registration validation and initial state construction in a small catalog `SourceService` backed by `CatalogRepository`.
- Source database ID is identity. Duplicate names, root paths, and root-path keys are intentionally allowed; registration does not reuse a Source based on its configured path.
- Initially set `root_path_key` to the supplied `root_path` and exclude it from the public REST response.
- Validate only that the supplied root path is nonblank, syntactically valid, and absolute for the backend host. Preserve the supplied string and do not require filesystem availability, canonicalize it, resolve symlinks, or call `toRealPath()`.
- Source update, deletion, relocation, remount recognition, availability checks, and traversal remain deferred.

## Initial Scan Request API

- Expose Source-based scan-request creation through `POST /api/scan-runs` and get-by-ID through `GET /api/scan-runs/{id}`; a list endpoint remains deferred.
- The first implemented request type is `INDEX`. A new ScanRun uses status `PENDING`, options version `1`, effective options `{}`, no WorkingSet, and no execution timestamps or error.
- Each selected Source receives a `PENDING` ScanRunSource that snapshots its current `location_revision`, begins at traversal generation `0`, and has no completed generation, execution timestamps, or error.
- Validate all distinct positive Source IDs and confirm every Source exists before inserting any request row. Create the ScanRun and all ScanRunSource rows in one transaction.
- Return ScanRunSource rows in ascending Source-ID order rather than promising request-array order.
- ScanRun creation records durable user intent only. It does not create a Job, access the filesystem, or begin execution.

## Initial Scan Execution Handoff

- Expose a ScanRun's singleton execution subresource through `POST` and `GET /api/scan-runs/{id}/execution`; do not add a global Job API yet.
- Keep scan-specific orchestration in `ScanExecutionService` under `scan`. Generic `job` code remains independent of scan orchestration.
- The handoff creates a `SCAN` Job with status `PENDING`, current stage `DISCOVERY`, zero progress and attempts, and no execution timestamps/error.
- Atomically create exactly one `DISCOVERY` JobStage with status `PENDING`, zero progress and attempts, the Job's creation timestamp, and no execution timestamps/error.
- Creating execution does not mutate the ScanRun or ScanRunSource state and does not access the filesystem or begin work.
- Enforce at most one sequential `SCAN` handoff per ScanRun in the service/repository boundary and return HTTP 409 for duplicate POST. Do not add `UNIQUE(scan_run_id)`; concurrent duplicate hardening remains deferred.
- Read JobStages in stable ascending database-ID order until a deliberate multi-stage ordering model exists.

## Initial DISCOVERY Execution

- Expose explicit DISCOVERY execution through `POST /api/scan-runs/{id}/execution/discovery`; run it synchronously in the request for this increment.
- Before state mutation or filesystem access, require unchanged Source location revisions and a pending SCAN Job whose current pending stage is DISCOVERY. Missing ScanRuns/executions return 404 and ineligible or stale-snapshot requests return 409.
- Traverse registered roots recursively with Java NIO `Files.walkFileTree(...)`, without opting into link following. Observe regular files only and skip symbolic-link entries.
- Derive Source-relative paths with Java NIO and serialize segments with `/`. Preserve observed case and Unicode spelling; use that portable relative path unchanged as the initial `path_key`.
- Preserve filesystem modification time as epoch seconds plus nanoseconds rather than deriving it through milliseconds.
- Discovery creates and refreshes FileEntry occurrences only. It creates no ContentRecord, hash, or analysis artifact and never marks an unobserved entry missing.
- Commit FileEntry observations and Job/DISCOVERY progress in fixed batches of at most 250. Keep filesystem walking outside database write transactions.
- Allocate traversal generation one for the first traversal. Successful discovery ends at ScanRunSource `DISCOVERED` while `completed_generation` and `completed_at_ms` remain null until reconciliation/missing-file completion.
- On success, complete DISCOVERY and create one pending RECONCILIATION stage while the ScanRun and Job remain `RUNNING`. Do not execute reconciliation in this increment.
- On filesystem failure, fail every ScanRunSource participating in the started DISCOVERY attempt along with the DISCOVERY stage, Job, and ScanRun, so no child remains `DISCOVERING`. Preserve allocated generations and committed partial observations, leave every `completed_generation` null, do not create RECONCILIATION, and do not perform a missing sweep.
- Defer simultaneous-call hardening, background scheduling, retry/recovery, and final symlink/junction policy.

## Development

- Favor readable, conventional, learnable code over clever abstractions.
- Implement in small, understandable increments and avoid premature architecture.
- Require explicit authorization before installing or modifying system software.
- Use Codex deliberately for meaningful implementation work.
- High-level product decisions come from the separate planning/ideas ChatGPT conversation.
- The repository and its durable docs are authoritative for implemented technical state.

## Git

- Use the personal GitHub account `topher6835` for this repository.
- Use the repository-local identity `topher6835 <topher6835@users.noreply.github.com>`.
- Use the `github-personal` SSH alias with origin `git@github-personal:topher6835/media-compare.git`.
- Keep this configuration repository-local and do not disturb other GitHub identities.
- Do not commit or push unless explicitly requested.

## Still Open

- Additional status, request-type, classification, and stage values beyond the implemented ScanRun request and initial execution-handoff values.
- Source remount/relocation recognition and filesystem volume hints.
- Final symlink/junction traversal behavior and detailed path equivalence.
- ContentRecord merge/reconciliation behavior.
- Job scheduling, concurrency, cancellation, retry, and startup recovery.
- Detailed scan scope representation and source-specific progress.
- Specialized result schemas beyond `content_hash`.
- Matching, similarity, relationship, grouping, and manual override schemas.
- Face/person schema and AI-provider architecture.
- Application-managed cache locations and lifecycle.
- FFmpeg/ffprobe discovery and process management.
- Safeguards for eventual filesystem-modifying operations.
