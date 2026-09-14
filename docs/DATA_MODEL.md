# Data Model

## Status and Scope

The reviewed V1 persistence design is implemented by Flyway migration `V1__create_core_schema.sql`. The application now has the eleven V1 tables, their structural constraints and initial indexes, immutable Java record representations, and small Spring JDBC repositories.

The first migration contains exactly eleven application tables. The fields and constraints below describe the implemented schema.

## V1 Tables

### `source`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `name TEXT NOT NULL`
- `root_path TEXT NOT NULL`
- `root_path_key TEXT NOT NULL`
- `location_revision INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `created_at_ms INTEGER NOT NULL`
- `updated_at_ms INTEGER NOT NULL`

Source has a durable database identity. `root_path` and `root_path_key` are location/configuration data, not Source identity. `root_path_key` is an application lookup aid; neither path field is unique. Matching a path or path key must not automatically establish that a previously registered Source is the same Source that has returned. Relocation and remount recognition are deferred. Platform-specific volume or filesystem identifiers may later assist as optional hints only; they cannot be required cross-platform identity.

Initial Source registration sets `root_path_key` equal to the supplied `root_path`. The registration service preserves that supplied string and uses `Path.of(...)` only to require host-platform syntax and an absolute path. It does not require the path to exist or be a directory and does not perform filesystem canonicalization, case conversion, Unicode normalization, symlink resolution, or `toRealPath()`. Duplicate names, root paths, and root-path keys are intentionally allowed; each registration receives a distinct database identity.

### `content_record`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `size_bytes INTEGER NOT NULL CHECK >= 0`
- `created_at_ms INTEGER NOT NULL`

A ContentRecord permanently represents one byte-version. Its identity is an internal ID rather than an exact hash. An established record is not mutated to represent replacement bytes. Temporary duplicate records are allowed until explicit reconciliation/merge behavior is designed. V1 has no canonical redirect or merge table.

### `file_entry`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `source_id INTEGER NOT NULL`
- `relative_path TEXT NOT NULL`
- `path_key TEXT NOT NULL`
- `current_content_id INTEGER NULL`
- `presence_status TEXT NOT NULL`
- `size_bytes INTEGER NOT NULL CHECK >= 0`
- `modified_time_epoch_second INTEGER NULL`
- `modified_time_nano INTEGER NULL`, valid from `0` through `999999999` when present
- `observation_revision INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `first_seen_at_ms INTEGER NOT NULL`
- `last_seen_at_ms INTEGER NOT NULL`
- `last_seen_scan_run_source_id INTEGER NULL`
- `last_seen_traversal_generation INTEGER NULL`, positive when present

The uniqueness rule is `UNIQUE(source_id, path_key)`. `relative_path` preserves observed case and Unicode spelling through Java NIO. Persisted portable relative paths use `/` between segments. V1 does not globally lowercase paths, Unicode-normalize them, resolve symlinks, or call `toRealPath()` to construct occurrence identity. Where equivalence is uncertain, observations remain separate. Initially `path_key` may match the portable serialized path while remaining a separate field for future lookup policy.

A FileEntry represents a filesystem occurrence, not immutable content. Its occurrence remains historically useful when the file disappears. `PRESENT` and `MISSING` are the minimum conceptual presence states. `observation_revision` increments when an observation indicates that bytes or content association may have changed; seeing the same unchanged file does not increment it.

When `last_seen_scan_run_source_id` is non-null, the referenced ScanRunSource must have the same `source_id` as the FileEntry. In V1, `CatalogRepository` checks this invariant within the FileEntry insert transaction. The schema retains the direct foreign key and `ON DELETE SET NULL`; no composite foreign key or trigger is used.

### `working_set`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `name TEXT NOT NULL`
- `created_at_ms INTEGER NOT NULL`
- `updated_at_ms INTEGER NOT NULL`

WorkingSet names do not need to be unique.

### `working_set_content`

Implemented fields:

- `working_set_id INTEGER NOT NULL`
- `content_record_id INTEGER NOT NULL`
- `added_at_ms INTEGER NOT NULL`

The primary key is `(working_set_id, content_record_id)`. Membership remains ContentRecord-based. Replacing bytes at a FileEntry does not silently replace historical membership. Detailed path-to-content history is deferred.

### `scan_run`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `request_type TEXT NOT NULL`
- `status TEXT NOT NULL`
- `working_set_id INTEGER NULL`
- `options_version INTEGER NOT NULL CHECK > 0`
- `options_json TEXT NOT NULL`
- `created_at_ms INTEGER NOT NULL`
- `started_at_ms INTEGER NULL`
- `finished_at_ms INTEGER NULL`
- `error_message TEXT NULL`

ScanRun records user intent. The initial scan-request API creates Source-based requests with `request_type = INDEX`, `status = PENDING`, `working_set_id = NULL`, `options_version = 1`, `options_json = {}`, and null execution timestamps/error. Options become immutable once execution begins. Additional lifecycle and request-type values remain implementation details.

### `scan_run_source`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `scan_run_id INTEGER NOT NULL`
- `source_id INTEGER NOT NULL`
- `status TEXT NOT NULL`
- `source_location_revision INTEGER NOT NULL CHECK >= 0`
- `traversal_generation INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `completed_generation INTEGER NULL`, greater than zero and no greater than `traversal_generation` when present
- `started_at_ms INTEGER NULL`
- `completed_at_ms INTEGER NULL`
- `error_message TEXT NULL`

The uniqueness rule is `UNIQUE(scan_run_id, source_id)`.

Initial request creation writes one row for each selected Source with `status = PENDING`, the Source's current `location_revision` snapshot, `traversal_generation = 0`, and null completion, execution timestamps, and error. All requested Sources are validated before insertion, and the parent ScanRun plus all ScanRunSource rows are committed atomically. Reads order these rows by ascending `source_id`. Creating these records does not create a Job or begin traversal.

Each traversal from a Source root receives a fresh positive generation. A traversal restarted after interruption receives a new generation, so `traversal_generation` may be greater than the last `completed_generation` while newer work is in progress. A completed generation is positive and cannot exceed the current traversal generation. V1 allows only one active reconciliation traversal per Source initially. A missing-file sweep is authorized only after a complete successful traversal of the intended scope. Cancellation, inaccessible directories, offline Sources, and incomplete traversal must not mark previous entries missing. The missing update and completed-generation state are committed together. Discovery may restart after shutdown; directory-level traversal checkpoints are deferred.

### `job`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `scan_run_id INTEGER NULL`
- `job_type TEXT NOT NULL`
- `status TEXT NOT NULL`
- `current_stage_type TEXT NULL`
- `progress_completed INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `progress_total INTEGER NULL CHECK >= 0 when present`
- `attempt_count INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `created_at_ms INTEGER NOT NULL`
- `started_at_ms INTEGER NULL`
- `finished_at_ms INTEGER NULL`
- `error_message TEXT NULL`

Job is the durable execution authority. ScanRun remains the user-request and operation-summary record. The initial execution handoff creates a Job with `scan_run_id` set to the selected ScanRun, `job_type = SCAN`, `status = PENDING`, `current_stage_type = DISCOVERY`, zero completed progress, null total progress, zero attempts, a populated creation timestamp, and null execution timestamps/error. The handoff does not mutate the ScanRun or its ScanRunSource rows.

The execution API currently permits one sequentially created `SCAN` Job per ScanRun. `ScanExecutionService` enforces that API behavior; no `UNIQUE(scan_run_id)` constraint was added because Job remains generic. Simultaneous duplicate-request hardening, detailed scheduling, and recovery are deferred.

### `job_stage`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `job_id INTEGER NOT NULL`
- `stage_type TEXT NOT NULL`
- `status TEXT NOT NULL`
- `progress_completed INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `progress_total INTEGER NULL CHECK >= 0 when present`
- `attempt_count INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `created_at_ms INTEGER NOT NULL`
- `started_at_ms INTEGER NULL`
- `finished_at_ms INTEGER NULL`
- `error_message TEXT NULL`

The uniqueness rule is `UNIQUE(job_id, stage_type)`. A V1 stage row is an aggregate durable checkpoint for one stage type within a Job. Retry and resume update that row. Stage-instance and attempt-history tables are deferred.

The initial handoff creates exactly one `DISCOVERY` stage with `status = PENDING`, zero completed progress, null total progress, zero attempts, the same creation timestamp as its Job, and null execution timestamps/error. Job and stage creation occur in one service transaction. Stage reads use stable ascending database-ID order; a deliberate multi-stage ordering model remains deferred.

### `analysis_record`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `content_record_id INTEGER NOT NULL`
- `analysis_type TEXT NOT NULL`
- `analyzer_id TEXT NOT NULL`
- `analyzer_version TEXT NOT NULL`
- `configuration_version INTEGER NOT NULL CHECK > 0`
- `configuration_hash TEXT NOT NULL`
- `configuration_json TEXT NOT NULL`
- `status TEXT NOT NULL`
- `attempt_count INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `created_at_ms INTEGER NOT NULL`
- `started_at_ms INTEGER NULL`
- `finished_at_ms INTEGER NULL`
- `error_message TEXT NULL`

Cache/provenance identity includes ContentRecord, analysis type, analyzer identity, analyzer version, and configuration version/hash. All identity components are non-null. Even an analysis with no options uses a versioned empty configuration. The hash is computed from deterministic effective settings, while the effective configuration is retained as JSON.

The reviewed cache/artifact uniqueness rule is:

```text
UNIQUE(
    content_record_id,
    analysis_type,
    analyzer_id,
    analyzer_version,
    configuration_version,
    configuration_hash
)
```

`configuration_json` is retained for provenance and explainability, but is not part of this uniqueness constraint.

Only successfully completed records with complete specialized results may be reused. Failed or interrupted work may be retried under the same artifact identity. Separate attempt history and detailed startup recovery remain deferred.

### `content_hash`

Implemented fields:

- `analysis_record_id INTEGER PRIMARY KEY`
- `algorithm TEXT NOT NULL`
- `digest_hex TEXT NOT NULL`

This is the specialized exact-hash artifact. Algorithm identifiers are canonical and the planned V1 digest encoding is lowercase hexadecimal. Index `(algorithm, digest_hex)`, but do not make that pair unique: provisional ContentRecords may temporarily produce the same trusted digest before reconciliation exists. The hash is never the ContentRecord primary key.

## Filesystem Timestamps

Application lifecycle timestamps use epoch milliseconds stored as SQLite integers. Filesystem modification times preserve available Java `FileTime` precision with an epoch-second value and nanosecond component. The two values are both present or both absent; nanoseconds are constrained to `0..999999999`. Filesystems that provide less precision remain valid.

## Relationships, Foreign Keys, and Deletion

The conceptual relationship is:

```text
Source -> FileEntry -> ContentRecord -> AnalysisRecord -> specialized results
WorkingSet -> ContentRecord membership
ScanRun -> ScanRunSource -> Source
ScanRun -> Job -> JobStage
```

Historical catalog evidence must not disappear accidentally when a Source, ContentRecord, scan record, or related parent is removed. The migration uses restrictive deletion for durable catalog identities and reusable analysis relationships. WorkingSet membership cascades from WorkingSet deletion, JobStage cascades from Job deletion, and ContentHash cascades from AnalysisRecord deletion. `file_entry.last_seen_scan_run_source_id` uses `ON DELETE SET NULL`, allowing execution history to be removed later without deleting FileEntry history.

SQLite foreign-key enforcement is enabled for every physical datasource connection with the `foreign_keys=on` SQLite JDBC URL property.

## Initial Index Direction

Beyond primary keys and uniqueness constraints, the reviewed initial useful indexes are:

- `file_entry(current_content_id, presence_status)`
- `file_entry(source_id, presence_status, last_seen_traversal_generation)` for Source-led reconciliation
- `working_set_content(content_record_id)`
- `scan_run_source(source_id, status)`
- `job(scan_run_id)`
- `content_hash(algorithm, digest_hex)`

Do not add indexes for hypothetical queries before measuring actual access patterns.

## Lifecycle and Type Validation

Evolving status and type values are not locked into rigid SQLite `CHECK (... IN (...))` lists in V1. SQL constraints enforce structural invariants such as nullability, foreign keys, uniqueness, ranges, and numeric validity. Exact lifecycle values will be validated in Java when the corresponding workflows are implemented.

## Java Persistence Foundation

Immutable records representing all eleven table row shapes and concrete Spring JDBC repositories are organized under the persistence-related feature packages:

- `catalog`
- `scan`
- `job`
- `analysis`

`CatalogRepository`, `ScanRepository`, `JobRepository`, and `AnalysisRepository` provide focused insert and read operations. They use `JdbcTemplate` directly without a generic repository superclass or ORM. `CatalogRepository` includes Source lookup by ID and deterministic ID-ordered Source listing. `ScanRepository` includes ScanRun lookup and Source-ID-ordered child lookup. `JobRepository` supports explicit Job lookup by ScanRun/type and database-ID-ordered stage reads without owning scan orchestration. `catalog` does not depend on the job runner, `job` remains generic, and `analysis` owns reusable analysis and provenance. The `web` boundary contains thin Source, ScanRun, and scan-execution REST controllers; later HTTP/SSE endpoints remain deferred.

## Explicitly Deferred

V1 does not include:

- ContentRecord merge/redirect infrastructure.
- Historical FileEntry path/content-version history.
- Directory-level traversal checkpoints.
- Analysis attempt-history or Job stage-instance tables.
- Perceptual fingerprints, embeddings, vector infrastructure, face/person schemas, or video fingerprints.
- Matching candidates, similarity relationships, groups, or manual override schemas.
- AI-specific result schemas and provider infrastructure.
- Filesystem-action history.
- Thumbnail/cache metadata.
- Final FFmpeg/ffprobe discovery strategy.
- WAL-specific architecture.
- Final symlink/junction traversal behavior.
- Source remount/relocation detection algorithms.

Temporary duplicate ContentRecords are acceptable until the concrete merge/reconciliation operation is designed and tested.
