# Architecture

## Implementation Status

The repository currently contains a working full-stack scaffold:

- A React single-page frontend in `frontend/`.
- A Spring Boot REST backend in `backend/`.
- One local SQLite database configured through Spring JDBC and Flyway.
- `GET /api/health`, returning plain text `ok`.
- REST endpoints to register and read Sources under `/api/sources`.
- REST endpoints to create and read durable scan requests under `/api/scan-runs`.
- A frontend `/` route that requests and displays the health result.

The reviewed V1 persistence foundation is implemented. Flyway migration `V1__create_core_schema.sql` creates the eleven V1 application tables, structural constraints, foreign keys, and initial indexes. Simple immutable records and Spring JDBC repositories provide insert/read access under the `catalog`, `scan`, `job`, and `analysis` feature packages. Source registration/read and durable scan-request creation/read are implemented. Filesystem scanning, hashing, reconciliation execution, job execution, analysis execution, matching, AI, and broader product workflows do not exist yet.

## Architectural Style

Media Compare will begin as a modular monolith:

- One Spring Boot backend.
- One React frontend.
- One SQLite catalog.
- No microservices, message queues, Docker requirement, or separate worker process initially.

Responsibilities remain meaningfully separated inside the applications without creating elaborate layered architecture. The initial responsibility areas are catalog/indexing, scanning/reconciliation, analysis, matching, jobs/progress, organization/manual decisions, filesystem operations, media tooling, and AI integrations.

## Implemented V1 Persistence Boundaries

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
- `web` — thin REST controllers and later HTTP/SSE endpoints.

The persistence foundation uses one concrete Spring JDBC repository per feature package: `CatalogRepository`, `ScanRepository`, `JobRepository`, and `AnalysisRepository`. The boundaries remain simple. `catalog` does not depend on the job runner; `job` remains generic; and `analysis` owns analysis provenance. `SourceController` and `ScanRunController` are thin HTTP boundaries over small feature services, while `HealthController` remains unchanged. No generic repository framework, automatic interface/implementation pairs, or enterprise layering was introduced.

## Catalog and Identity

The catalog represents files generally, including images, video, audio, documents, archives, and miscellaneous or unknown files. Cheap filesystem/catalog processing can apply broadly; expensive analysis applies only to selected and supported media.

A `Source` is a persistent registered scan root such as a folder, external drive, whole drive, or other filesystem root. It has a durable database identity. `root_path` and `root_path_key` are location/configuration data, not identity; `root_path_key` is an application lookup aid, and neither field is unique. A matching path or path key must not automatically establish that a previously registered Source is the same Source that has returned. `location_revision` records changes to the configured location. Source relocation and remount recognition remain later concerns. Platform-specific volume, filesystem, file-ID, or inode information may later assist as optional hints only and can never be required cross-platform identity.

The initial Source registration behavior preserves the supplied root-path string and writes the same value to `root_path_key`. It uses Java NIO only to check that the path is syntactically valid and absolute for the backend host. Registration does not inspect filesystem availability, require the path to exist or be a directory, resolve symlinks, or canonicalize the path. Duplicate names and root paths are allowed because database ID, not path, establishes Source identity.

A `FileEntry` represents one filesystem occurrence within a Source. It stores a Source-relative path, filesystem metadata, current content association, presence, first/last-seen information, observation revision, and the scan traversal that last observed it. The uniqueness rule is `UNIQUE(source_id, path_key)`.

When a FileEntry records `last_seen_scan_run_source_id`, that ScanRunSource must belong to the FileEntry's own Source. V1 keeps the simple foreign key and its `ON DELETE SET NULL` behavior; `CatalogRepository` enforces the cross-table Source match transactionally before insertion rather than adding a composite foreign key or trigger.

The path policy is cross-platform and lossless: preserve observed case and Unicode spelling, use `/` between persisted relative path segments, do not globally lowercase or Unicode-normalize, and do not resolve symlinks or call `toRealPath()` to construct occurrence identity. Where filesystem equivalence is uncertain, preserve separate observations.

A `ContentRecord` represents one immutable byte-version independently of location. It has a stable internal ID rather than a hash primary key. Exact hashing may be deferred. Temporary duplicate ContentRecords are acceptable until a later explicit reconciliation operation; V1 has no canonical redirect or merge table. Transformed copies have separate ContentRecords.

## Reconciliation and Execution

Revisiting a Source performs lightweight discovery and reconciliation before expensive analysis. Each root traversal receives a fresh positive traversal generation. A missing-file sweep is permitted only after a complete successful traversal of the intended scope. Interrupted, cancelled, incomplete, inaccessible, or offline scans do not mark previously known files missing. The missing update and completed-generation state are committed together.

`observation_revision` allows future workers to reject stale publication after a FileEntry may have changed. Discovery may restart after application shutdown; fragile filesystem iterator cursors are not persisted. Directory-level traversal checkpoints remain deferred.

`ScanRun` records user intent, selected Sources or WorkingSet, request type, options, and lifecycle. Its options become immutable when execution begins. The first implemented request creation accepts one or more registered Source IDs and atomically writes one ScanRun plus its ScanRunSource rows. It uses request type `INDEX`, initial status `PENDING`, options version `1`, and effective options `{}`. Each child begins `PENDING`, snapshots the Source's current location revision, and has traversal generation `0` with no completion or execution state. Child responses are ordered by Source ID.

`Job` is the durable execution authority and may later execute scans, analysis, filesystem actions, exports, or other long-running work. Creating a ScanRun currently records intent only and creates no Job. `JobStage` is an aggregate checkpoint for one stage type within a Job, with `UNIQUE(job_id, stage_type)`; retries and resume update that row. Additional lifecycle values and execution behavior remain implementation details.

Pause/resume is database-driven and must survive full application shutdown. Work is processed in small batches, with completed analysis reused and incomplete work found from durable state. Detailed scheduling, cancellation, startup recovery, and stage-instance history remain open.

## Working Sets and Analysis

A `WorkingSet` is a persistent logical collection of `ContentRecord` membership for repeated comparison and organization workflows. Replacing the bytes at a FileEntry does not silently replace historical WorkingSet membership. Physical copies can become missing while content identity, membership, and reusable analysis remain useful. Saved indexes use the one catalog database rather than separate database files.

`AnalysisRecord` captures reusable analysis provenance, lifecycle, analyzer identity/version, configuration version/hash, and effective configuration. A compatible completed artifact can be reused; changes to analysis type, analyzer, model, version, preprocessing, provider, or configuration create a new artifact rather than silently overwriting the prior result. Specialized result structures hold hashes, media metadata, fingerprints, embeddings, face results, video fingerprints, and AI results as those features are designed.

`content_hash` is the specialized exact-hash result. It uses canonical algorithm identifiers and lowercase hexadecimal digests. `(algorithm, digest_hex)` is indexed but not unique because temporary duplicate ContentRecords may exist.

Filesystem metadata belongs to FileEntry. Media-derived metadata belongs to ContentRecord analysis so moves and renames do not invalidate compatible analysis. Large derived files such as thumbnails, previews, extracted frames, and intermediates belong in a future managed cache rather than the SQLite catalog.

Face analyzer output, detected face instances, and embeddings remain conceptually separate from later human person or group classification. AI analysis follows the same provenance and versioning rules: it is optional and provider-independent, and local and cloud providers may coexist without making the rest of the catalog depend on one provider. Face/person schemas, AI result schemas, provider interfaces, and runtime architecture remain undecided.

## Matching and Scale

The design targets thousands, tens of thousands, and potentially hundreds of thousands of files. Implementations should stream Java NIO traversal, use bounded database batches and indexed queries, avoid loading complete drive listings into memory, and avoid expensive work for files that do not need it.

Matching uses cheap candidate generation followed by deeper comparison for plausible candidates. Full pairwise comparison is not a V1 strategy. Candidate persistence and matching algorithms remain open, while pending work must eventually support durable resume.

## Broader Conceptual Relationship View

```text
Source -> FileEntry -> ContentRecord -> AnalysisRecord -> specialized results
                           |
                           -> future relationships/groups

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

## SQLite and Cross-Platform Requirements

SQLite remains the single local catalog database. Spring JDBC and Flyway provide persistence access and migration ownership. SQL should enforce structural invariants such as nullability, foreign keys, uniqueness, numeric ranges, and valid nanosecond values. Evolving status and type values should initially be validated in Java rather than rigid SQLite membership checks.

Foreign-key enforcement is enabled through the SQLite JDBC URL's `foreign_keys=on` connection property, so every physical connection receives the setting. Tests verify the PRAGMA on separate simultaneous pooled connections and verify rejection of an invalid reference. Database transactions should be short; filesystem traversal, hashing, FFmpeg calls, and analysis should occur outside write transactions. WAL remains deferred until sustained concurrent reads and analysis writes provide a measured reason to evaluate it.

macOS and Windows are both required. Filesystem handling uses Java NIO and must not assume one separator, drive-letter model, case behavior, Unicode normalization, symlink policy, or mount identity. Future FFmpeg/ffprobe integration must not assume `/opt/homebrew/bin/ffmpeg`, `/opt/homebrew/bin/ffprobe`, or any fixed executable path.

## Undecided Areas

The following remain open after the V1 review:

- Additional status, request-type, classification, and stage values beyond the initial ScanRun `INDEX`/`PENDING` creation state.
- Source remount/relocation recognition and filesystem volume hints.
- Final symlink and Windows junction traversal behavior.
- Detailed path equivalence beyond the V1 lossless key policy.
- ContentRecord reconciliation/merge behavior.
- Job scheduling, concurrency, cancellation, retries, and startup recovery.
- Detailed scan scope representation and source-specific progress.
- Specialized result schemas beyond `content_hash`.
- Candidate, similarity, relationship, grouping, and manual override schemas.
- Face/person schema and AI-provider architecture.
- Application-managed cache locations and lifecycle.
- FFmpeg/ffprobe discovery and process management.
- Safeguards and workflow for eventual filesystem-modifying operations.

## Planned Product Capabilities

The architecture is intended to support folders, multiple unrelated folders, whole drives, persistent indexing, incremental/reconciliation scans, exact duplicate and transformed-copy detection, similar/related media, resumable analysis, manual grouping/classification overrides, optional face analysis, optional local/cloud AI, and later explicit safeguarded filesystem modification.

None of these product capabilities is implemented by the current scaffold.
