# Data Model

## Status and Scope

Flyway V1–V5 retain their historical schema meaning. Java migration `V6__source_membership_authority` is the current schema: thirteen application tables, source-independent FileEntries, and SourceMembership as the sole writable Source/FileEntry relationship and presence authority. Every V5 FileEntry keeps its ID, content association, byte evidence, extension, revision, and timestamps; it becomes `UNRESOLVED` with null absolute identity and exactly one backfilled membership. V6 neither probes the filesystem nor merges apparently equal historical entries. The old v1/v2 execution rows remain readable, while new SCAN work uses execution version 3.

## Implemented Tables

### `source`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `name TEXT NOT NULL`
- `root_path TEXT NOT NULL`
- `root_path_key TEXT NOT NULL`
- `location_revision INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `root_path_dialect TEXT NULL` (added by V5)
- `bound_location_context_id TEXT NULL` (added by V5)
- `binding_evidence_json TEXT NULL` (added by V5)
- `created_at_ms INTEGER NOT NULL`
- `updated_at_ms INTEGER NOT NULL`

Source has a durable database identity. `root_path` and `root_path_key` are location/configuration data, not Source identity. `root_path_key` is an application lookup aid; neither path field is unique. Matching a path or path key must not automatically establish that a previously registered Source is the same Source that has returned. Relocation and remount recognition are deferred. Platform-specific volume or filesystem identifiers may later assist as optional hints only; they cannot be required cross-platform identity.

Initial Source registration sets `root_path_key` equal to the supplied `root_path`. The registration service preserves that supplied string and uses `Path.of(...)` only to require host-platform syntax and an absolute path. It does not require the path to exist or be a directory and does not perform filesystem canonicalization, case conversion, Unicode normalization, symlink resolution, or `toRealPath()`. Duplicate names, root paths, and root-path keys are intentionally allowed; each registration receives a distinct database identity.

V5 did not reinterpret or rewrite existing `root_path_key` values. `root_path_dialect = NULL` means the Source root/key has not been established under the resolver contract. `bound_location_context_id` restrictively references `location_context(id)`, and `idx_source_bound_location_context` supports reference lookup. The fields remain null for migrated and newly registered Sources until explicit first-time binding; v3 admission requires a current supported binding.

### `location_context` (added by V5)

Implemented fields:

- `id TEXT COLLATE BINARY PRIMARY KEY` (application-issued UUID stored as canonical text)
- `anchor_location_path TEXT NOT NULL`
- `anchor_location_key TEXT COLLATE BINARY NOT NULL`
- `lifecycle_status TEXT NOT NULL`
- `continuity_status TEXT NOT NULL`
- `revision INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `continuity_evidence_json TEXT NULL`
- `created_at_ms INTEGER NOT NULL`
- `updated_at_ms INTEGER NOT NULL`

The table requires non-null continuity evidence when `continuity_status = ACCEPTED`. Java domain values are limited to lifecycle `ACTIVE`/`RETIRED` and continuity `ACCEPTED`/`REVIEW_REQUIRED`; the record also rejects blank identity/path/key, negative revision, invalid timestamp order, and accepted state without evidence. V5 constrains evidence presence but does not validate its contents; the explicit acceptance service and current-authority helper validate the versioned APFS envelope in Java. The partial unique index `uq_location_context_active_anchor` enforces one exact binary `anchor_location_key` among `ACTIVE` rows while allowing a retired row with the same key. It does not detect path-prefix or structural-domain overlap.

`LocationContextRepository` inserts a caller-supplied context, finds by exact canonical UUID text ID or exact binary active anchor key, lists ACTIVE rows by ascending ID, checks for bound Sources, and performs guarded acceptance and retirement updates. `LocationContextActivationService.createActive(...)` requires REVIEW_REQUIRED with null evidence and validates a requested ACTIVE anchor, reserves the SQLite writer before reading ACTIVE rows, validates every persisted ACTIVE anchor fail-closed, rejects structural overlap, and inserts in one transaction. RETIRED rows do not block creation. `LocationContextRetirementService.retireActive(...)` uses the same writer reservation before reading the target and bound-Source state; it validates the target anchor and conditionally changes only lifecycle, revision, and update time. Retirement requires an ACTIVE row, matching expected revision, no bound Source, and a caller timestamp at least as recent as the stored update time. Revision advances by exactly one. `LocationContextReplacementService.replaceActive(...)` applies those same old-row retirement rules and atomically inserts a different-UUID, same-decoded-anchor ACTIVE context. The new context must be `REVIEW_REQUIRED` with null evidence, and its caller-supplied creation timestamp must equal the replacement timestamp; its valid revision and update timestamp are preserved. Every other ACTIVE anchor is validated and checked for structural overlap before the old row is retired. An insert failure rolls back retirement. No lifecycle service probes a filesystem, creates context IDs, or binds a Source. `LocationContextAcceptanceService.acceptReviewRequired(...)` receives an already captured accepted APFS result, reserves the SQLite writer, revalidates the current unbound row and evidence, and atomically sets ACCEPTED, a strict envelope, revision N+1, and the caller-owned non-regressing update timestamp. It changes no Source or other context fields.

### Implemented Structured Location Formats

The host-independent `location` package implements three initial dialects: `unix`, `win-drive`, and restricted `win-unc`. A `LocationPath` consists of a dialect, exact root-field list, and exact component list. It preserves case and Unicode spelling without normalization. Containment requires the same dialect and exact root fields plus an element-wise component prefix; raw text prefixes are never hierarchy evidence. The parser accepts ordinary absolute Unix paths, drive-rooted Windows paths, and UNC server/share roots. It rejects relative and drive-relative paths, dot components, redundant/trailing separators, NUL or malformed Unicode, device namespaces, and restricted Windows device names/characters/trailing dot or space. Unix backslash is literal; both slash forms are structural for Windows dialects.

Canonical `location_path` v1 is a four-element JSON array:

```text
["lp1","unix",[],["Users","chris","Photos","a.jpg"]]
["lp1","win-drive",["D"],["Photos","a.jpg"]]
["lp1","win-unc",["server","share"],["Photos","a.jpg"]]
```

The strict decoder requires exactly those typed fields and a known dialect/version, rejects trailing JSON/content, and applies the same path invariants. Deterministic encoding and decoding are bounded to 64 KiB of UTF-8 JSON.

Canonical `location_key` v1 is `lk1:` plus lowercase hexadecimal for: one stable dialect byte (`01`, `02`, or `03`); a big-endian unsigned 32-bit root-field count followed by each big-endian unsigned 32-bit UTF-8 byte length and bytes; then the same count/length/bytes structure for components. Decoding uses strict UTF-8, rejects malformed/non-lowercase hex, truncation, trailing bytes, unknown IDs/versions, impossible lengths, and noncanonical structure, then re-encodes for canonical verification. Payloads are limited to 16 KiB, component count to 1,024, and each field to 4 KiB of UTF-8. Keys compare exactly under SQLite binary semantics. Mapped-drive and UNC values remain different dialect identities.

ACTIVE LocationContext creation validates caller-supplied anchor path/key pairs and existing ACTIVE rows through these codecs before insertion. The codecs do not reinterpret the legacy `source.root_path_key`, probe a filesystem, or establish a Source binding. Source registration, indexing, and continuity probing do not use this persisted anchor workflow.

### Implemented macOS/APFS Evidence and Probe Contracts

`MacOsApfsLocationContextEvidence` defines the typed version-1 `macos-local-apfs` context-anchor contract. Its authoritative fields are the exact Unix `LocationPath`/`LocationKey`, filesystem type `apfs`, canonical Volume UUID, positive unsigned-64 anchor inode in canonical decimal text, and directory/non-symbolic-link classification. It also retains a nonnegative acceptance timestamp and optional bounded Unix-device, FileStore-name, and provider-class diagnostics; those fields do not participate in continuity comparison.

`MacOsApfsSourceRootEvidence` separately defines version-1 `macos-local-apfs-source-root` evidence: canonical LocationContext UUID, nonnegative context and Source revisions, exact Unix root path/key, canonical Volume UUID, positive unsigned-64 root inode, birth time as signed epoch seconds plus nanoseconds `0..999999999`, classification flags, and nonnegative acceptance timestamp. It does not duplicate the context anchor inode or diagnostics.

Both JSON codecs require exact field sets and types; reject duplicate keys, trailing content, unknown schema/profile versions, malformed/noncanonical UUIDs and unsigned values, malformed nested `lp1`/`lk1`, and inconsistent path/key pairs; and enforce a 128 KiB UTF-8 document limit. Writers use deterministic field order. Context diagnostics are a required object whose three known fields are individually optional; unknown diagnostic fields are rejected.

`LocationContextAcceptanceEvidence` is a separate version-1 envelope in `continuity_evidence_json`: `{version, contextId, contextRevision, macOsApfsEvidence}`. Its strict deterministic codec enforces the exact schema, canonical context UUID, nonnegative revision, 128 KiB UTF-8 limit, and nested validation through the existing APFS codec. For a current ACTIVE + ACCEPTED authority, the envelope ID and revision must equal the row's ID and revision, and its structured APFS anchor must match the validated persisted anchor. Acceptance records the post-transition revision N+1. A later RETIRED row may retain this earlier envelope after its row revision advances; current-authority validation rejects it as ineligible before requiring revision equality. Legacy raw APFS JSON is still a valid persisted ACCEPTED value under V5 but is unverifiable as current authority; there is no automatic rewrite or reacceptance.

`SourceBindingEvidence` is a separate version-1 envelope in `source.binding_evidence_json`: `{version, sourceId, macOsApfsSourceRootEvidence}`. Its deterministic exact-schema codec bounds the UTF-8 document to 128 KiB, requires a positive Source ID, and delegates nested validation to the existing Source-root codec. For the bound row, the envelope Source ID, embedded context ID, post-bind Source revision, and structured root path/key must match the row. First binding is available only for a legacy Source with null dialect/context/evidence and `root_path_key == root_path`. The configured Unix root must exactly match the observed exact spelling, equal or descend from the accepted context anchor, and have accepted APFS identity/classification. The guarded Source update changes only dialect, canonical `lk1` key, context reference, evidence JSON, location revision N+1, and the caller's non-regressing update timestamp. It preserves `root_path` and all FileEntries. The accepted Source-root probe candidate becomes the initial persisted baseline after validation against current context authority and the post-bind Source revision. Later continuity checks may compare it with later observations. Binding and context lifecycle transitions share SQLite writer reservation; a bound Source blocks retirement/replacement. Repeat binding conflicts, and unbinding/rebinding are not implemented.

Pure comparison returns one of `ACCEPTED`, `UNAVAILABLE`, `UNCERTAIN`, `MISMATCH`, `UNSUPPORTED`, or `ERROR` with a bounded enum reason. Comparison produces `ACCEPTED` for matching evidence, `MISMATCH` for coherent authoritative contradictions, and `UNCERTAIN` when a Source root is structurally outside its claimed context. The production macOS/APFS probe uses the same bounded categories for capture: accepted results alone carry evidence; unavailable paths/tools, internal instability, unsupported platform/filesystem, coherent context-volume mismatch, and infrastructure/parser errors remain distinct non-successes. No comparator or probe rewrites a baseline. Explicit context acceptance stores accepted context evidence; first-time Source binding consults it as current context authority, while indexing does not.

### `content_record`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `size_bytes INTEGER NOT NULL CHECK >= 0`
- `created_at_ms INTEGER NOT NULL`

A ContentRecord permanently represents one byte-version. Its identity is an internal ID rather than an exact hash. An established record is not mutated to represent replacement bytes. Initial assignment creates one distinct record per eligible unassigned FileEntry occurrence/version, even when separate files have identical bytes and metadata. Exact hashing stores analysis artifacts for each record without changing or merging it. Exact duplicate groups are derived from compatible artifacts and likewise do not rewrite identity. Materialized grouping and merge behavior remain deferred; V1 has no canonical redirect or merge table.

### `file_entry` (V6)

`file_entry` is a physical occurrence independent of Source. It retains `id`, `current_content_id`, nonnegative `size_bytes`, paired exact mtime seconds/nanoseconds, nullable normalized `extension_key`, nonnegative `observation_revision`, and first/last observation timestamps. Its `location_identity_status` is `UNRESOLVED` or `RESOLVED`. Unresolved rows require null `location_context_id`, `location_path`, and `location_key`; resolved rows require all three. The context ID has a restrictive foreign key. A partial unique index on `(location_context_id, location_key)` applies only to resolved rows. Application code strictly decodes the structured `lp1` path and `lk1` key and requires canonical agreement before using a resolved row.

V6 removes Source ID, relative path/key, presence, and Source traversal provenance from FileEntry. All migrated V5 FileEntries are unresolved; their historical ContentRecords and analyses stay attached without inferred absolute identity. A trusted v3 observation resolves or inserts by `(location_context_id, location_key)` and reuses the same FileEntry across overlapping Sources. Size or exact mtime change increments `observation_revision` and clears `current_content_id`; a membership becoming present again without changed byte evidence does neither. `extension_key` remains the V2 lowercase technical suffix convention and is updated for newly resolved locations.

### `source_membership` (V6)

Each membership stores `id`, `source_id`, `file_entry_id`, Source-relative `relative_path` and `path_key`, `ACTIVE`/`RETIRED` applicability, `PRESENT`/`MISSING` presence, nonnegative `membership_revision`, `observed_file_entry_revision`, first/last positive timestamps, nullable last-positive ScanRunSource/generation, and nullable observed Source/context revisions. Foreign keys protect Source and FileEntry identity. `UNIQUE(source_id, file_entry_id)` prevents duplicate relationships; a partial unique index permits only one ACTIVE membership at a `(source_id, path_key)`.

`observed_source_location_revision` and `observed_location_context_revision` are both null for migrated/unproven history or both non-null for trusted observations. V6 enforces that pair with a CHECK. Last-positive ScanRunSource and traversal generation are intentionally not paired by a CHECK because V5 allowed partial historical values. The backfill copies V5 Source/path/presence/provenance fields exactly, sets applicability ACTIVE and membership revision zero, and leaves both authority revisions null. Trusted publication may retire an active unresolved path collision and insert a new resolved membership in one transaction; it never retargets the historical FileEntry or ContentRecord.

Only ACTIVE memberships participate in current presence. An ACTIVE PRESENT membership makes a physical FileEntry known present; all applicable ACTIVE memberships missing means no current membership reports it present. RETIRED rows remain historical. V3 missing sweeps require a trusted complete traversal and change only the scanned Source's ACTIVE membership presence and revision. FileEntry has no cached presence.

Assignment, hashing, and live media-metadata reads select only resolved FileEntries with at least one current ACTIVE/PRESENT trusted membership whose observed FileEntry revision matches. Publication reserves a SQLite writer, rereads Source/context authority and resolved path/relationship coherence, and guards the candidate revisions. A stale assignment rolls back its new ContentRecord. Hashing and metadata read the resolved absolute path with filesystem pre/post validation; unresolved historical rows remain readable but are not live-read candidates.

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
- `request_key TEXT NULL` (added by V3)
- `request_type TEXT NOT NULL`
- `status TEXT NOT NULL`
- `working_set_id INTEGER NULL`
- `options_version INTEGER NOT NULL CHECK > 0`
- `options_json TEXT NOT NULL`
- `created_at_ms INTEGER NOT NULL`
- `started_at_ms INTEGER NULL`
- `finished_at_ms INTEGER NULL`
- `error_message TEXT NULL`

ScanRun records user intent. The public indexing-start API uses V3's `request_key` for durable UUID idempotency. Non-null keys are unique through a partial index, while historical rows and v1 creation keep it null and multiple nulls remain valid. Public v2 acceptance persists canonical lowercase UUID text and atomically creates the ScanRun, Source membership, Job, and pending DISCOVERY stage. Replays compare `INDEX` plus the unique Source ID set; they retain the same execution even after terminal failure. New attempts require new keys. The initial scan-request API creates Source-based requests with `request_type = INDEX`, `status = PENDING`, `working_set_id = NULL`, `options_version = 1`, `options_json = {}`, and null execution timestamps/error. Options become immutable once execution begins. Additional lifecycle and request-type values remain implementation details.

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

Initial request creation writes one row for each selected Source with `status = PENDING`, the Source's current `location_revision` snapshot, `traversal_generation = 0`, and null completion, execution timestamps, and error. All requested Sources are validated before insertion, and the parent ScanRun plus all ScanRunSource rows are committed atomically. Reads order these rows by ascending `source_id`. The v1 request API creates no Job; public v2 acceptance includes its Job and DISCOVERY stage in the same transaction, with traversal submitted only after commit.

Each traversal from a Source root receives a fresh positive generation. A traversal restarted after interruption receives a new generation, so `traversal_generation` may be greater than the last `completed_generation` while newer work is in progress. A completed generation is positive and cannot exceed the current traversal generation. V1 allows only one active reconciliation traversal per Source initially. A missing-file sweep is authorized only after a complete successful traversal of the intended scope. Cancellation, inaccessible directories, offline Sources, and incomplete traversal must not mark previous entries missing. The missing update and completed-generation state are committed together. A later new ScanRun may rediscover after shutdown; directory-level traversal checkpoints are deferred.

The first implemented traversal advances generation zero to one and moves a Source from `PENDING` through `DISCOVERING` to `DISCOVERED`. Successful filesystem discovery deliberately leaves `completed_generation` and `completed_at_ms` null because reconciliation and the missing-file sweep have not completed. Reconciliation atomically performs that Source's missing sweep and transitions it to `COMPLETED`, sets `completed_generation = traversal_generation`, populates `completed_at_ms`, and clears any error. A terminal filesystem failure records `FAILED` state and the same completion timestamp for every ScanRunSource participating in that started attempt, so none remains `DISCOVERING`; each allocated traversal generation is preserved and each `completed_generation` remains null.

### `job`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `scan_run_id INTEGER NULL`
- `job_type TEXT NOT NULL`
- `execution_version INTEGER NOT NULL DEFAULT 1 CHECK > 0` (added by V3)
- `status TEXT NOT NULL`
- `current_stage_type TEXT NULL`
- `progress_completed INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `progress_total INTEGER NULL CHECK >= 0 when present`
- `attempt_count INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `created_at_ms INTEGER NOT NULL`
- `started_at_ms INTEGER NULL`
- `finished_at_ms INTEGER NULL`
- `error_message TEXT NULL`

Job is the durable execution authority. ScanRun remains the user-request and operation-summary record. `execution_version = 1` identifies the historical reconciliation-ending SCAN execution; version 2 identifies the historical pipeline through exact hashing; version 3 is the current membership-authority pipeline with the same four stages as v2. New creation starts with `status = PENDING`, `current_stage_type = DISCOVERY`, zero progress/attempts, and null execution timestamps/error without mutating the ScanRun or its Source rows.

V6 admission creates only version-3 SCAN Jobs. SQLite write reservation and a current active-Job check exclude competing work across versions. Partial indexes enforce one v3 Job per ScanRun and one active v2/v3 SCAN Job catalog-wide; historical v1/v2 records remain readable. The public indexing start/read API and background scheduler route new work to v3. The old manual v1 discovery/reconciliation write endpoints reject new writes.

When DISCOVERY starts, the Job becomes `RUNNING`, increments its attempt count, records its start time, and reports persisted regular-file observations as progress while total remains null. Successful discovery sets completed and total progress to the final observation count, keeps the Job `RUNNING`, and changes its current stage to `RECONCILIATION`. Starting RECONCILIATION keeps the same Job attempt and start timestamp but resets progress to zero out of the number of Sources, establishing that Job progress mirrors its current stage. Successful reconciliation completes the Job, clears `current_stage_type`, preserves completed Source-based progress, and records its finish time. Filesystem failure during DISCOVERY instead marks the Job `FAILED` with finish/error state.

### `job_stage`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `job_id INTEGER NOT NULL`
- `stage_type TEXT NOT NULL`
- `result_json TEXT NULL` (added by V3)
- `status TEXT NOT NULL`
- `progress_completed INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `progress_total INTEGER NULL CHECK >= 0 when present`
- `attempt_count INTEGER NOT NULL DEFAULT 0 CHECK >= 0`
- `created_at_ms INTEGER NOT NULL`
- `started_at_ms INTEGER NULL`
- `finished_at_ms INTEGER NULL`
- `error_message TEXT NULL`

The uniqueness rule is `UNIQUE(job_id, stage_type)`. A stage row is an aggregate durable checkpoint for one stage type within a Job. Version-2 assignment stores `{"version":1,"assignedCount":n,"skippedCount":n}` and hashing stores `{"version":1,"hashedCount":n,"cachedCount":n,"skippedCount":n,"failedCount":n}` in nullable `result_json`; typed codecs require the exact versioned shape. Durable SCAN v1 discovery and reconciliation stages leave `result_json` null, while the independent MEDIA_METADATA execution v1 stores the typed completed `IMAGE_METADATA` summary there. Intermediate assignment/hashing progress is not yet durable; only their final summaries are. Job-stage retry/resume and stage-attempt history remain deferred.

The initial handoff creates exactly one `DISCOVERY` stage with `status = PENDING`, zero completed progress, null total progress, zero attempts, the same creation timestamp as its Job, and null execution timestamps/error. Job and stage creation occur in one service transaction. Stage reads use stable ascending database-ID order; a deliberate multi-stage ordering model remains deferred.

V3 moves DISCOVERY to `RUNNING`, increments its attempt count, and updates progress in bounded transactions with membership publication. A trusted complete traversal creates the pending RECONCILIATION stage. Reconciliation marks only applicable SourceMemberships missing, then creates CONTENT_ASSIGNMENT; CONTENT_HASHING completes the Job/ScanRun. ScanRunSource becomes `COMPLETED` at reconciliation. Candidate-level hash skips/failures remain durable counts; whole-stage failure marks the current stage, Job, and ScanRun failed and clears `current_stage_type`. Historical v1/v2 stage records retain their original interpretation.

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
- `result_json TEXT NULL` (added by V4)
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

Only successfully completed records with complete specialized results may be reused. The implemented exact SHA-256 definition uses `analysis_type = CONTENT_HASH`, `analyzer_id = builtin.sha256`, analyzer version `1`, configuration version `1`, configuration JSON `{}`, and the lowercase SHA-256 hash of that exact UTF-8 JSON. Newly published rows are `COMPLETED` with attempt count one, start/create timestamps from hashing start, a finish timestamp, and no error. A non-completed exact-key record is not overwritten; separate retry infrastructure remains deferred.

`MEDIA_METADATA` uses V4's `result_json` for a strict version-1 `AVAILABLE` image/video payload or `UNSUPPORTED` outcome. The implemented ImageIO definition is `builtin.imageio` version `1`, configuration version `1`, exact configuration JSON `{}`, and that UTF-8 JSON's SHA-256 hash. It byte-sniffs only ImageIO-supported JPEG, PNG, GIF, BMP, and runtime-provided TIFF readers, stores canonical format plus encoded positive dimensions, and treats no-reader/unsupported formats as completed `UNSUPPORTED`. The pure video interpretation foundation defines an independent `builtin.ffprobe` version `1` with configuration version `1` and the same exact empty configuration/hash; no ffprobe AnalysisRecord is published yet. New extraction candidates are deduplicated and paged by ContentRecord ID; current present occurrences are independently paged by FileEntry ID. Compatible `COMPLETED`, `PENDING`, and `RUNNING` rows block scheduling, while `FAILED` is retryable. Retry updates the unique compatible row in place, increments `attempt_count`, refreshes attempt timestamps, and either remains `FAILED` with null result or becomes `COMPLETED` with a typed result and cleared error. Success and failure publication use one occurrence's snapshotted Source/FileEntry/ContentRecord evidence and write only if transactional catalog evidence remains current. Completed compatible results remain reusable after occurrences become missing. No SHA-256 prerequisite or cross-ContentRecord reuse by equal digest exists.

### `content_hash`

Implemented fields:

- `analysis_record_id INTEGER PRIMARY KEY`
- `algorithm TEXT NOT NULL`
- `digest_hex TEXT NOT NULL`

This is the specialized exact-hash artifact. The implemented built-in artifact uses algorithm `SHA-256` and a structurally validated lowercase 64-character hexadecimal digest. AnalysisRecord and ContentHash are inserted atomically after database evidence is revalidated. Index `(algorithm, digest_hex)`, but do not make that pair unique: separate ContentRecords with identical bytes retain separate artifacts with equal digests. The hash is never the ContentRecord primary key.

The exact duplicate view uses exact built-in AnalysisRecord provenance to derive groups with at least two distinct ContentRecords. It stores no group identity. Member counts are calculated independently of FileEntry joins; distinct physical FileEntries supply present/missing occurrence counts, while memberships supply Source counts and relative-path details. A ContentRecord without a FileEntry remains a member. Potential storage savings is estimated as `max(present physical FileEntry count - 1, 0) * size_bytes`; missing entries contribute no current savings, and this is not a measurement of allocated disk blocks.

Occurrence filters use `file_entry.extension_key` to select complete exact groups. Both `PRESENT` and `MISSING` retained occurrences can select a group; a ContentRecord without an occurrence remains a full member after another occurrence selects that group. Summary counts and savings remain whole-group values, while `filterMatch` counts only matching retained occurrences. Detail responses return the whole group and mark each occurrence against the filter. Filter options aggregate distinct digest-group and retained-occurrence counts by non-null extension across valid exact groups.

## Filesystem Timestamps

Application lifecycle timestamps use epoch milliseconds stored as SQLite integers. Filesystem modification times preserve available Java `FileTime` precision with an epoch-second value and nanosecond component. The two values are both present or both absent; nanoseconds are constrained to `0..999999999`. Filesystems that provide less precision remain valid.

## Current Membership Authority and Remaining Catalog Work

```text
Catalog
  ├─ LocationContext
  ├─ Source ──→ LocationContext
  └─ SourceMembership ──→ Source
                       └─→ FileEntry ──→ ContentRecord ──→ AnalysisRecord

FileEntry ──→ LocationContext (resolved only)
```

The pure `scan.authority` contract and v3 host adapter admit trusted local macOS/APFS observations. V3 retains `DISCOVERY -> RECONCILIATION -> CONTENT_ASSIGNMENT -> CONTENT_HASHING -> COMPLETED`, using SourceMembership for positive observations and complete-traversal missing claims. Unbound Sources, unsupported profiles, legacy raw acceptance evidence, and Windows hosts are ineligible for new v3 admission. Existing v1/v2 Jobs and ScanRuns keep their recorded meaning and remain readable; incompatible active work is failed during startup recovery. A later shortened SCAN, with hashing separate, requires execution version 4 or later.

No global case folding, Unicode normalization, `toRealPath()` identity, hash/inode-only identity, automatic mapped-drive/UNC equivalence, or automatic rename/move/remount recognition is introduced. Distinct hard-link names remain distinct FileEntries. Catalog switching, Source unbinding/rebinding, other host/provider profiles, and automatic historical consolidation remain deferred.

## Current Implemented Relationships, Foreign Keys, and Deletion

The current operational relationship is:

```text
Source -> SourceMembership -> FileEntry -> ContentRecord -> AnalysisRecord
Source -> LocationContext; resolved FileEntry -> LocationContext
WorkingSet -> ContentRecord membership
ScanRun -> ScanRunSource -> Source
ScanRun -> Job -> JobStage
```

Restrictive foreign keys preserve Source, FileEntry, ContentRecord, analysis, and context history. WorkingSet membership cascades from WorkingSet deletion, JobStage from Job deletion, and ContentHash from AnalysisRecord deletion. `source_membership.last_positive_scan_run_source_id` uses `ON DELETE SET NULL`. A bound Source and resolved FileEntries protect their LocationContext from deletion.

SQLite foreign-key enforcement is enabled for every physical datasource connection with the `foreign_keys=on` SQLite JDBC URL property.

## Initial Index Direction

V6 retains content/extension lookup indexes and adds resolved-location uniqueness, active membership path uniqueness, FileEntry-to-membership lookup, and Source reconciliation indexes. Its paired authority-revision CHECK and the resolved/unresolved FileEntry identity CHECK are structural database invariants. V3 Jobs have one per ScanRun and a catalog-wide active v2/v3 exclusion; service admission also checks active historical v1 work. Historical V1–V5 indexes remain represented by their original migrations.

## Lifecycle and Type Validation

Evolving status and type values are not locked into rigid SQLite `CHECK (... IN (...))` lists. SQL constraints enforce structural invariants such as nullability, foreign keys, uniqueness, ranges, and numeric validity. V5 maps LocationContext lifecycle and continuity values through strict Java enums; other exact lifecycle values are validated by their corresponding Java workflows.

With exclusive catalog ownership, startup recovery fails incompatible active v1/v2 SCAN work and interrupted v3 work. It preserves completed stages, results, FileEntries, memberships, content, and analysis artifacts; only the current running stage and active execution are failed. No old discovery/reconciliation is resumed against V6. Malformed active v2/v3 state still aborts startup instead of being silently repaired.

The independent `MEDIA_METADATA` execution version 1 has a null `scan_run_id` and one `IMAGE_METADATA` stage. Service admission uses process-local serialization under exclusive catalog ownership plus a short SQLite writer reservation to allow one active metadata Job independently of SCAN; no new schema index is required. Startup fails an abandoned active metadata Job and its sole stage, while exact-definition ImageIO `PENDING`/`RUNNING` AnalysisRecords become `FAILED` and retryable without changing their attempt counts. The next Job re-enumerates candidates rather than resuming a cursor; completed artifacts are skipped.

## Java Persistence Foundation

Immutable records represent the V6 FileEntry and SourceMembership rows. `CatalogRepository`, `SourceMembershipRepository`, `LocationContextRepository`, `ScanRepository`, `JobRepository`, and `AnalysisRepository` use focused Spring JDBC methods without an ORM or generic repository layer. `SourceMembershipPublicationService` reserves the SQLite writer and atomically checks current binding/context authority, publishes resolved FileEntries and memberships, retires unresolved path collisions, and reconciles trusted missing claims. V3 traversal and probe capture stay outside write transactions. Assignment, hashing, and metadata candidate reads use resolved membership authority; duplicate reporting counts physical FileEntries for storage savings and memberships for Source/path details.

## Explicitly Deferred

V1 does not include:

- ContentRecord merge/redirect infrastructure.
- Historical FileEntry path/content-version history.
- Directory-level traversal checkpoints.
- Analysis attempt-history or Job stage-instance tables.
- Perceptual fingerprints, embeddings, vector infrastructure, face/person schemas, or video fingerprints.
- Matching candidates, similarity relationships, materialized groups, or manual override schemas.
- AI-specific result schemas and provider infrastructure.
- Filesystem-action history.
- Thumbnail/cache metadata.
- Final FFmpeg/ffprobe discovery strategy.
- WAL-specific architecture.
- Final symlink/junction traversal behavior.
- Source remount/relocation detection algorithms.

Temporary duplicate ContentRecords are acceptable until the concrete merge/reconciliation operation is designed and tested.
