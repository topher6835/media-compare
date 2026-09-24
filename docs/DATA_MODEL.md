# Data Model

## Status and Scope

The reviewed V1 persistence design is implemented by Flyway migration `V1__create_core_schema.sql`. Java Flyway migration `V2__add_file_entry_extension_key` evolves `file_entry` with normalized technical extension metadata and an index. SQL migration `V3__add_durable_indexing_foundation.sql` adds fields and partial unique indexes used by internal durable full-pipeline indexing. V4 adds nullable `analysis_record.result_json` for typed compact analysis results. V5 adds the `location_context` table and nullable Source binding fields, now used by explicit first-time local APFS binding. The application now has twelve tables, immutable Java record representations, and small Spring JDBC repositories.

The first migration contains exactly eleven application tables. The fields and constraints below describe the implemented schema.

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

V5 does not reinterpret or rewrite the existing `root_path_key`. `root_path_dialect = NULL` means the Source root/key has not been established under the future resolver contract. `bound_location_context_id` restrictively references `location_context(id)`, and `idx_source_bound_location_context` supports reference lookup. All three fields remain null for migrated and newly registered Sources; no current workflow requires or interprets them.

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

### `file_entry`

Implemented fields:

- `id INTEGER PRIMARY KEY`
- `source_id INTEGER NOT NULL`
- `relative_path TEXT NOT NULL`
- `path_key TEXT NOT NULL`
- `extension_key TEXT NULL` (added by V2)
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

`extension_key` is technical normalized FileEntry metadata, not a user category or tag. V2 snapshots its extraction rules inside the historical migration: use the basename suffix after its last dot and lowercase with `Locale.ROOT`; lone leading dots, trailing dots, and names without a dot store null. Paths and extension text are not Unicode-normalized. Runtime discovery/re-observation uses `FileExtensionNormalizer`; both currently implement the V2 rules. A future change to persisted extension semantics requires a later migration so fresh databases remain consistent with databases that already applied V2. Unknown extensions remain valid. Broad `PHOTO`, `VIDEO`, and `DOCUMENT` values are derived in Java from this key and are not persisted; unclassified extensions have no technical FileCategory.

A FileEntry represents a filesystem occurrence, not immutable content. Its occurrence remains historically useful when the file disappears. `PRESENT` and `MISSING` are the minimum conceptual presence states. `observation_revision` increments when an observation indicates that bytes or content association may have changed; seeing the same unchanged file does not increment it.

Implemented discovery inserts a new observed occurrence as `PRESENT`, with normalized extension metadata, no ContentRecord, observation revision zero, equal first/last-seen timestamps, and the current ScanRunSource/traversal generation. Re-observation always refreshes path spelling and its derived extension, presence, size, modification time, last-seen time, and traversal identity. A size change, modification-time change, or return from a non-`PRESENT` state increments the revision once and clears `current_content_id`; unchanged metadata preserves both revision and content association.

Implemented reconciliation marks a currently `PRESENT` occurrence `MISSING` when it belongs to the Source being reconciled and its last-seen ScanRunSource/generation pair does not exactly match the completed traversal. Null last-seen fields count as not observed. This update changes only `presence_status`; it preserves `extension_key`, `current_content_id`, observation revision, paths, size, modification time, first/last-seen timestamps, and traversal identity. Already-`MISSING` entries remain unchanged.

Implemented ContentRecord assignment selects only `PRESENT`, content-null FileEntries whose last-seen ScanRunSource and generation exactly match a completed ScanRunSource traversal. Candidate reads use ascending FileEntry-ID keyset pages of at most 250 and carry only ID, observation revision, and size. ContentRecord insertion and publication to `current_content_id` share one transaction. The conditional publication requires unchanged presence, null content, observation revision, and size; a stale candidate rolls back the inserted record so no orphan remains. Last-seen traversal identity is intentionally not part of that publication guard, allowing a later unchanged observation of the same occurrence version. Existing content identity survives unchanged rescans, and repeating assignment creates no duplicate record.

Implemented content hashing selects only `PRESENT`, assigned FileEntries whose last-seen ScanRunSource/generation pair exactly matches a completed traversal. Ascending-ID keyset pages contain at most 250 candidates and snapshot FileEntry/ContentRecord/Source identity, portable path, observation revision, size, exact mtime, and the ScanRunSource's Source-location revision. Final publication requires the FileEntry to retain its Source, presence, ContentRecord, observation revision, size, and exact mtime and requires the Source location revision to match. Last-seen traversal identity may change after selection if the occurrence version otherwise remains unchanged. Hashing never mutates FileEntry or ContentRecord.

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

Job is the durable execution authority. ScanRun remains the user-request and operation-summary record. `execution_version = 1` identifies the public reconciliation-ending SCAN execution; version 2 identifies the backend-owned pipeline through exact hashing. Both creation paths start with `status = PENDING`, `current_stage_type = DISCOVERY`, zero progress/attempts, and null execution timestamps/error without mutating the ScanRun or its Source rows.

Normal service creation permits one SCAN execution owner per ScanRun and excludes competing v1/v2 ownership under SQLite write reservation. Historical repository fixtures can retain both versions for explicit version-aware reads. `ScanExecutionService` enforces that API behavior; no schema-wide `UNIQUE(scan_run_id)` constraint was added because Job remains generic. V3 enforces at most one version-2 `SCAN` Job per ScanRun and at most one globally active version-2 `SCAN` Job whose status is `PENDING` or `RUNNING`. Terminal version-2 Jobs, version-1 Jobs, and unrelated Job types do not occupy that global slot. Internal version-2 creation uses those constraints as the race-safe authority and converts conflicts to service-domain conflicts. Bounded background scheduling, startup interruption finalization, public v2 start/read APIs, and the frontend polling cutover are implemented.

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

Execution moves DISCOVERY to `RUNNING`, increments its attempt count, and updates progress in the same bounded transactions as FileEntry observations. Success completes it and creates one pending RECONCILIATION stage. Version 1 ends by completing the Job and ScanRun after reconciliation. Version 2 instead creates CONTENT_ASSIGNMENT, then CONTENT_HASHING, and completes the Job/ScanRun only with successful hashing finalization. ScanRunSource becomes `COMPLETED` at reconciliation and is not kept running through assignment or hashing. Candidate-level hash skips/failures are durable counts on a completed stage; whole-stage failure marks the current stage, Job, and ScanRun failed and clears `current_stage_type`.

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

The exact duplicate view uses this existing index and the exact built-in AnalysisRecord provenance to derive groups with at least two distinct ContentRecords. It stores no group identity or membership rows. Member counts are calculated independently of FileEntry joins; retained FileEntries then supply present/missing occurrence and Source counts. A ContentRecord without a FileEntry remains a member. Potential storage savings is estimated as `max(present occurrence count - 1, 0) * size_bytes`; missing occurrences contribute no current savings, and this is not a measurement of allocated disk blocks.

Occurrence filters use `file_entry.extension_key` to select complete exact groups. Both `PRESENT` and `MISSING` retained occurrences can select a group; a ContentRecord without an occurrence remains a full member after another occurrence selects that group. Summary counts and savings remain whole-group values, while `filterMatch` counts only matching retained occurrences. Detail responses return the whole group and mark each occurrence against the filter. Filter options aggregate distinct digest-group and retained-occurrence counts by non-null extension across valid exact groups.

## Filesystem Timestamps

Application lifecycle timestamps use epoch milliseconds stored as SQLite integers. Filesystem modification times preserve available Java `FileTime` precision with an epoch-second value and nanosecond component. The two values are both present or both absent; nanoseconds are constrained to `0..999999999`. Filesystems that provide less precision remain valid.

## Approved Target Model (Partially Migrated)

The preceding sections describe the implemented twelve-table schema, including V5 LocationContext persistence and first-time Source binding storage. The wider target model below remains approved architecture, not implemented SourceMembership, FileEntry identity/presence authority, probe orchestration, rebinding, or scan behavior.

The new `scan.authority` records are transient pure values, not database rows or a migration. A trusted `ResolvedFileCandidate` carries current Source/context IDs and revisions, exact absolute structured file path/key, a component-derived Source-relative portable path/key, regular non-link and same-volume APFS classification, size, and exact mtime. Its future FileEntry identity portion is `(location_context_id, file_location_key)`; the Source-relative portion belongs to a future membership. The typed start/end snapshots and traversal-completion result can authorize a future missing sweep only for an unchanged, completely covered scope. No current FileEntry is converted to resolved status by reconstruction from Source root and relative path; no V6 schema or operational indexing change has occurred.

```text
Catalog
  ├─ LocationContext
  ├─ Source ──→ LocationContext
  └─ SourceMembership ──→ Source
                       └─→ FileEntry ──→ ContentRecord ──→ AnalysisRecord

FileEntry ──→ LocationContext
```

Each independent Catalog will eventually use a separate SQLite file and immutable internal `catalog_uuid`. The implemented LocationContext foundation is intended to represent one accepted continuity period for one address/binding domain; it is not the entire catalog, necessarily one Source, or a physical-volume identifier. Explicitly bound Sources can now reference LocationContext; current FileEntries do not reference it.

V5 implements the LocationContext fields described above. Services enforce structural-domain overlap during ACTIVE creation and same-anchor replacement, plus guarded retirement. The accepted context baseline protects its address domain during first binding; the bound Source-root baseline protects the selected recursive root. The pure path/key contract and the limited macOS exact-spelling/local-APFS probe are implemented as described above; unbinding/rebinding, relocation, and other host/provider profiles remain open.

`SourceMembership` will be a current durable relationship, not a row per Source revision. Conceptual fields are Source/FileEntry IDs, Source-relative portable path/key, `ACTIVE`/`RETIRED` applicability, `PRESENT`/`MISSING` membership presence, membership revision, observed FileEntry revision, positive-observation timestamps, and traversal/reconciliation evidence. Intended uniqueness is `UNIQUE(source_id, file_entry_id)` plus active-path uniqueness equivalent to `UNIQUE(source_id, path_key) WHERE applicability_status = 'ACTIVE'`. A rebind retains the Source ID, changes its binding revision, retires active memberships, and makes no missing claim or filesystem observation. It never retargets a membership merely because a relative path matches.

Target FileEntry retains its surrogate ID, content association, size, exact mtime, extension metadata, observation revision, and location-level timestamps. Source ownership, relative path/key, authoritative presence, and Source traversal evidence move to SourceMembership. It gains `location_identity_status`, `location_context_id` (referencing LocationContext), `location_path`, and `location_key`. The path is lossless display/diagnostic/reconstruction data; the key is a versioned unambiguous equality encoding. Resolved identities are intended to be unique by `(location_context_id, location_key)`; legacy ambiguity remains `UNRESOLVED` rather than guessed or consolidated.

There is no universal path equality: no global case folding or Unicode normalization, `toRealPath()` identity, content-hash identity, inode/file-key-only identity, automatic mapped-drive/UNC equivalence, or automatic rename/move/remount recognition. Different hard-link names remain separate FileEntries. Only explicitly supported platform/address cases resolve; ambiguous cases remain unresolved.

In the target model FileEntry has no independently authoritative persisted presence. Membership `PRESENT` reflects positive observation absent a later authorized sweep; `MISSING` requires a successful complete applicable traversal that did not observe it. Unavailable Sources, incomplete traversal, and age alone never prove absence. Aggregate FileEntry presence is initially derived rather than cached. A candidate still requires a current active/present membership in an applicable accepted LocationContext with matching observed FileEntry revision, then existing filesystem pre/post checks and transactional publication revalidation.

FileEntry observation revision changes only when byte-version evidence changes or becomes untrusted. Membership revision changes with applicability, presence, relative path/key, or observed FileEntry revision. Source binding revision changes with root/context/scope interpretation, not name edits or temporary availability. Publication will snapshot and revalidate Source, membership, FileEntry, ContentRecord, and context evidence.

The target `PRESENT` to `MISSING` transition additionally requires an active Source binding to an accepted LocationContext; passing context-anchor and Source-root continuity profiles before and after traversal; unchanged captured Source/context revisions; complete intended-scope coverage with no inaccessible subtree, cancellation, traversal failure, or unresolved link/reparse/storage boundary; and transactional revalidation of that evidence. Routine scans may infer absence when these checks pass. Under uncertain continuity, current inspection may be diagnostic, but existing membership presence and content associations are preserved and no missing sweep or trusted historical-content attachment is allowed.

The eventual migration creates one membership from each current FileEntry without changing ContentRecord or AnalysisRecord identities. It preserves ambiguous legacy duplicates and does not merge them because hashes match. Future ScanRunSource rows must snapshot Source/context revisions, resolved root/scope, continuity profile versions, and scan-time acceptance evidence; historical v1/v2 executions cannot be backfilled with information they never stored. Historical SCAN v2 remains `DISCOVERY -> RECONCILIATION -> CONTENT_ASSIGNMENT -> CONTENT_HASHING -> COMPLETED`; a likely future v3 ends after assignment and runs exact hashing as a separate deterministic operation. Existing v1/v2 Jobs remain readable. Catalog switching, cross-catalog queries, hot switching, membership-history generations, cached aggregate presence, automatic freshness expiry, and host/provider resolver details remain deferred.

## Current Implemented Relationships, Foreign Keys, and Deletion

The conceptual relationship is:

```text
Source -> FileEntry -> ContentRecord -> AnalysisRecord -> specialized results
Source -> LocationContext (nullable dormant V5 reference)
WorkingSet -> ContentRecord membership
ScanRun -> ScanRunSource -> Source
ScanRun -> Job -> JobStage
```

Historical catalog evidence must not disappear accidentally when a Source, ContentRecord, scan record, or related parent is removed. The migration uses restrictive deletion for durable catalog identities and reusable analysis relationships. WorkingSet membership cascades from WorkingSet deletion, JobStage cascades from Job deletion, and ContentHash cascades from AnalysisRecord deletion. `file_entry.last_seen_scan_run_source_id` uses `ON DELETE SET NULL`, allowing execution history to be removed later without deleting FileEntry history.

V5 uses `ON DELETE RESTRICT` from nullable `source.bound_location_context_id` to `location_context.id`. This protects a referenced context; explicit first-time binding now establishes the reference.

SQLite foreign-key enforcement is enabled for every physical datasource connection with the `foreign_keys=on` SQLite JDBC URL property.

## Initial Index Direction

Beyond primary keys and uniqueness constraints, the reviewed initial useful indexes are:

- `file_entry(current_content_id, presence_status)`
- `file_entry(source_id, presence_status, last_seen_traversal_generation)` for Source-led reconciliation
- `file_entry(extension_key, current_content_id)` for occurrence-led exact-group filtering (added by V2)
- `working_set_content(content_record_id)`
- `scan_run_source(source_id, status)`
- `job(scan_run_id)`
- `content_hash(algorithm, digest_hex)`
- `source(bound_location_context_id)` (added by V5)

V3 additionally creates these partial unique indexes:

- `scan_run(request_key) WHERE request_key IS NOT NULL`
- `job(scan_run_id) WHERE job_type = 'SCAN' AND execution_version = 2`
- `job(execution_version) WHERE job_type = 'SCAN' AND execution_version = 2 AND status IN ('PENDING', 'RUNNING')`

V5 additionally creates `UNIQUE location_context(anchor_location_key) WHERE lifecycle_status = 'ACTIVE'` with binary anchor-key comparison.

Do not add indexes for hypothetical queries before measuring actual access patterns.

## Lifecycle and Type Validation

Evolving status and type values are not locked into rigid SQLite `CHECK (... IN (...))` lists. SQL constraints enforce structural invariants such as nullability, foreign keys, uniqueness, ranges, and numeric validity. V5 maps LocationContext lifecycle and continuity values through strict Java enums; other exact lifecycle values are validated by their corresponding Java workflows.

With exclusive catalog ownership, startup recovery atomically fails each interrupted active v2 SCAN Job, clears its current stage, fails its nonterminal ScanRun, and fails only its current `RUNNING` stage. Never-started stages remain `PENDING`; completed stage results are preserved. Active `DISCOVERING` ScanRunSource rows become `FAILED` with completion time and null completed generation. `DISCOVERED` rows retain successful traversal evidence without authorizing an automatic missing sweep, and `COMPLETED` rows retain committed reconciliation state. All observations, content associations, and published analysis/hash artifacts survive. Impossible state aborts startup instead of being repaired. Terminal and version-1 Jobs are unchanged; no automatic resume/retry is implemented.

The independent `MEDIA_METADATA` execution version 1 has a null `scan_run_id` and one `IMAGE_METADATA` stage. Service admission uses process-local serialization under exclusive catalog ownership plus a short SQLite writer reservation to allow one active metadata Job independently of SCAN; no new schema index is required. Startup fails an abandoned active metadata Job and its sole stage, while exact-definition ImageIO `PENDING`/`RUNNING` AnalysisRecords become `FAILED` and retryable without changing their attempt counts. The next Job re-enumerates candidates rather than resuming a cursor; completed artifacts are skipped.

## Java Persistence Foundation

Immutable records representing the implemented table row shapes, including V3 execution fields, V4 result storage, and V5 LocationContext/Source binding fields, and concrete Spring JDBC repositories are organized under the persistence-related feature packages:

- `catalog`
- `scan`
- `job`
- `analysis`

`CatalogRepository`, `LocationContextRepository`, `ScanRepository`, `JobRepository`, and `AnalysisRepository` provide focused insert, read, and workflow-specific update operations. They use `JdbcTemplate` directly without a generic repository superclass or ORM. `LocationContextActivationService`, `LocationContextAcceptanceService`, `LocationContextRetirementService`, and `LocationContextReplacementService` use `LocationContextRepository` for short transactional lifecycle changes; no Source or indexing workflow calls them. `CatalogRepository` includes Source lookup/listing, extension-maintaining FileEntry observation, an explicit Source-scoped missing update, bounded assignment/hashing candidate reads, and guarded publication checks. `AnalysisRepository` reads exact provenance keys and persists AnalysisRecord/ContentHash artifacts. `ExactDuplicateRepository` performs read-only integrity, filtered grouped-summary, match-context, filter-option, member, and occurrence queries. Dedicated Spring beans give discovery start/batches/finalization/failure, reconciliation start/finalization, each Source's atomic missing-sweep/completed-generation boundary, each ContentRecord insert/FileEntry publication, and each guarded AnalysisRecord/ContentHash publication a real transaction. Exact grouping uses no transaction spanning its live read view. Filesystem traversal and hashing remain outside database transactions; reconciliation, ContentRecord assignment, and exact grouping/filtering perform no filesystem work.

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
