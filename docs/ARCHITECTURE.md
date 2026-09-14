# Architecture

## Implementation Status

Media Compare currently has only a working full-stack scaffold:

- A React single-page frontend in `frontend/`.
- A Spring Boot REST backend in `backend/`.
- One local SQLite database configured through Spring JDBC and Flyway.
- One implemented application endpoint, `GET /api/health`, which returns plain text `ok`.
- One frontend route, `/`, which requests and displays the health result.

The rest of this document records the confirmed conceptual architecture for future implementation. Unless explicitly described above as current behavior, it is not implemented.

## Architectural Style

Media Compare will begin as a modular monolith:

- One Spring Boot backend.
- One React frontend.
- One SQLite catalog.
- No microservices.
- No message queues.
- No Docker requirement.
- No separate worker process initially.

Responsibilities must still be kept meaningfully separated inside the applications. Expected responsibility areas include catalog/indexing, scanning/reconciliation, analysis, matching, jobs/progress, organization/manual decisions, filesystem operations, media tooling, and AI integrations. These areas are conceptual boundaries, not finalized Java packages or frontend modules.

## Catalog and Identity Model

The catalog represents files generally, not only supported media. A source can contain images, videos, audio, documents, archives, and miscellaneous or unknown file types. Cheap filesystem and catalog processing can apply to all encountered files; expensive analysis applies only to selected and supported media.

Broad file classifications such as image, video, audio, document, archive, other, and unknown are useful conceptual categories. Their exact enum names and persistence representation are not decided.

### Source

A Source is a persistent registered scan root, such as a folder, external drive, whole drive, or another filesystem root. It defines a scan boundary and configuration, but it is not the identity of the files or content beneath it.

Each Source has a durable internal identity. Its absolute path is location/configuration information and may change if a folder moves or a volume mounts differently. A Source can also be temporarily unavailable. Catalog history should survive all of these conditions.

Platform-specific filesystem or volume identifiers may be used later as optional hints, but cannot be required for identity because they are not portable or consistently reliable across macOS, Windows, and different filesystems.

### FileEntry

A FileEntry represents one known filesystem occurrence: a file exists, or previously existed, at a relative location within a Source. It owns filesystem-location information such as relative path, size, filesystem timestamps and attributes, presence, and first/last-seen information.

Paths should be stored relative to their Source rather than using an absolute path as file identity. Filesystem code must use platform-neutral Java `Path` and NIO concepts and must not assume slash separators, Windows drive letters, macOS case behavior, or a single filesystem model.

FileEntry history is retained when a file disappears. The minimum conceptual presence states are `PRESENT` and `MISSING`; additional states are not decided. If a file changes in place, the FileEntry remains the occurrence while its association with exact content may change.

### ContentRecord

A ContentRecord represents exact file bytes independently of filesystem location. It has a stable internal database identity that is not an exact hash. Hashing may be deferred, algorithms may change, and internal references must remain stable across those changes.

Multiple FileEntries can refer to one ContentRecord when a trusted exact hash confirms that they contain the same bytes:

```text
FileEntry A ---\
FileEntry B ----> ContentRecord
FileEntry C ---/
```

Reusable expensive analysis belongs primarily to ContentRecord. This allows results to survive moves, renames, duplicate copies, later scans, and the disappearance and reappearance of physical copies.

Transformed copies do not share a ContentRecord. For example, an original image and a cropped derivative have different exact bytes and therefore separate ContentRecords. A later matching or grouping process may infer a relationship between them. No separate vague “logical media item” is introduced at the catalog layer at this stage.

## Exact Hashing and Reconciliation

A trusted exact hash confirms byte-for-byte identity and is a reusable analysis artifact. The hash algorithm must be recorded explicitly; the model must not permanently assume a single algorithm. SHA-256 is the leading initial candidate because Java supports it without another dependency, but the concrete schema and implementation have not been selected.

If separate ContentRecords are later proven to have the same trusted exact hash, catalog logic should carefully reconcile them. That process is not designed or implemented yet.

Revisiting a Source begins with lightweight reconciliation before expensive analysis:

```text
start scan
    -> stream source traversal with Java NIO
    -> reuse entries whose path and cheap metadata appear unchanged
    -> re-evaluate changed metadata
    -> create or identify entries for new paths
    -> conservatively recognize moves/renames where possible
    -> mark previously known but unobserved entries MISSING
    -> checkpoint
```

Size and timestamp can support a “probably unchanged” optimization, but do not prove exact content identity. OS file IDs, inodes, or similar attributes may assist move/rename recognition as optional hints only. Exact hashing remains the trusted confirmation mechanism.

## ScanRun, Job, and Resumability

A ScanRun represents what the user asked Media Compare to do: selected Sources or a WorkingSet, requested analysis options, mode, and lifecycle information. Possible modes such as index, compare, or index-and-compare are conceptual; exact enum and API names are not finalized.

A Job represents long-running executable work and is intentionally distinct from ScanRun. Future jobs may cover scanning/indexing, deep analysis, bulk filesystem operations, exports, or other long-running work. A Job conceptually tracks its type, status, stage, progress, timing, and errors.

REST remains the application API direction. SSE is planned for later server-to-client live/progress updates, but its endpoints, event format, and recovery behavior are not designed.

Potential scan stages include discovery, reconciliation, hashing, media metadata, fingerprinting, faces, embeddings, matching, deep comparison, and grouping. These are conceptual stages rather than a finalized enum or pipeline, and not every run performs every stage.

Pause and resume must survive full application shutdown. The design will not serialize fragile Java execution state or depend on an exact filesystem iterator cursor. Instead, stages should be durable, database-driven, and idempotent where practical:

- Query for work whose requested artifacts are still missing.
- Process a small batch.
- Persist results and progress.
- Commit the batch.
- Continue or resume from durable state.

Filesystem discovery may restart when necessary because it is relatively cheap, while completed expensive analysis is reused. Directory-level discovery checkpoints may be introduced later if real performance measurements justify them. Batch sizes remain an implementation and tuning decision.

## Working Sets

A WorkingSet is a persistent logical collection of known content for repeated comparison and organization workflows. It references ContentRecords and never duplicates their reusable analysis.

This supports incremental workflows such as indexing Sources A and B, later adding Source C, analyzing only missing artifacts, and comparing C against the stored A+B catalog state.

WorkingSet identity and useful membership history remain even if every current FileEntry for a ContentRecord becomes `MISSING`. A user-facing saved index will be a persistent concept backed by the one catalog database, not a separate SQLite database file.

## Analysis and Provenance

Reusable analysis attaches primarily to ContentRecord through a general AnalysisRecord concept. An AnalysisRecord records what analysis ran, which content it analyzed, the algorithm/model/provider and version/configuration used, lifecycle status and timing, and relevant failure/retry information. Exact persisted names are not finalized.

Analysis reuse depends on compatible type, analyzer/model, version, and configuration. A compatible existing artifact is reused. A changed algorithm, model, provider, prompt/template, or configuration creates a new analysis result rather than silently overwriting the prior one. This rule applies to deterministic and AI-based analysis.

AnalysisRecord provides provenance, versioning, and lifecycle state. Query-heavy results should use specialized, efficiently searchable structures rather than placing every output in one generic JSON column. Potential specialized results include exact hashes, media metadata, perceptual fingerprints, embeddings, detected faces, video fingerprints, and AI results. Their final schemas are undecided.

### Filesystem and Media Metadata

Filesystem metadata belongs to FileEntry because it describes an occurrence: path, filename, size, filesystem timestamps, presence, and filesystem-specific attributes.

Media-derived metadata belongs to analysis of ContentRecord because it describes the bytes: dimensions, duration, codec, frame rate, stream information, orientation, useful EXIF data, and ffprobe-derived metadata. Moving or renaming unchanged content should not invalidate that analysis.

### Embeddings, Faces, and AI

Embeddings are versioned analysis artifacts. A first implementation may store vectors compactly in SQLite, likely as float32 BLOB data, and calculate similarity in Java. This storage choice is not final. No vector database or SQLite vector extension is selected; specialized vector search should be considered only if demonstrated scale requires it.

Face processing conceptually separates face detection, a detected face instance, a versioned face embedding, and later human organization into a person/group identity. Analyzer output (“a face was detected here”) must remain distinct from a user's classification (“these faces are the same person”). The final person schema is not designed.

AI analysis follows the same provenance and versioning rules. Local and cloud providers can coexist, AI remains optional and supplementary, and the rest of the catalog must not depend directly on a particular provider. Provider interfaces and runtime architecture are not designed yet.

## Matching and Scale

The architecture should support thousands, tens of thousands, and potentially hundreds of thousands of files without speculative distributed infrastructure. Implementation should stream filesystem traversal, batch database work, use indexed SQLite queries, avoid retaining an entire drive listing in memory unnecessarily, and defer expensive work to selected/supported media that needs it.

Matching must not perform full pairwise comparison across a large catalog. It will use cheap candidate generation, persist or otherwise durably manage plausible candidates, and perform deeper comparison only for those candidates. Pending candidate work must be resumable. Candidate algorithms and persistence schema remain undecided.

## Derived-Data Storage

SQLite will hold the catalog, compact metadata, hashes/fingerprints, vectors, classifications, provenance, and relationships. Large derived files such as thumbnails, preview media, extracted frames, and large intermediates should live in a future application-managed cache rather than as large SQLite BLOBs.

Platform-specific application-data and cache locations for macOS and Windows are not decided.

## Current Conceptual Relationship View

```text
Source -> FileEntry -> ContentRecord -> AnalysisRecord -> specialized results
                           |
                           -> future relationships/groups

WorkingSet -> ContentRecord membership

ScanRun -> Job -> stages/checkpoints
```

This diagram describes conceptual ownership and relationships, not tables, foreign keys, packages, or final cardinalities.

## Current Development Request Flow

The implemented local development request flow remains:

```text
React application on Vite :5173
    -> request to /api
Vite development proxy
    -> Spring Boot :8080
    -> SQLite at backend/data/media-compare.db
```

The frontend health request has been successfully exercised through this route.

## Cross-Platform Requirement

The application must support both macOS and Windows. Filesystem work should use Java NIO and must not hard-code platform-specific paths or assumptions.

Future FFmpeg/ffprobe integration must not assume `/opt/homebrew/bin/ffmpeg`, `/opt/homebrew/bin/ffprobe`, or any other fixed executable location. Executable discovery/configuration must work across macOS and Windows; its strategy remains undecided.

## Planned Product Capabilities

The conceptual architecture is intended to support folders, unrelated folders, whole drives, persistent indexing, incremental/reconciliation scans, exact duplicate and transformed-copy detection, similar/related media, resumable analysis, manual organization overrides, optional face analysis, optional local/cloud AI, and later explicit and safeguarded filesystem modification.

None of these product capabilities is implemented by the current scaffold.

## Undecided Architecture Areas

The following remain open:

- Concrete Java package boundaries and frontend module boundaries.
- Concrete SQLite tables, columns, constraints, indexes, and migrations.
- Exact enums and API representations for classifications, run modes, job states, and stages.
- Source relocation/offline detection details and use of platform-specific hints.
- ContentRecord reconciliation rules and transactional behavior.
- Candidate-generation, similarity, grouping, and transformed-copy algorithms.
- Job scheduling, concurrency, cancellation, retry, and SSE event design.
- WorkingSet membership/history semantics beyond the confirmed conceptual behavior.
- Exact analysis-result schemas and storage encodings, including embeddings.
- Face/person schema and classification workflow.
- AI-provider interfaces and configuration.
- Cross-platform FFmpeg/ffprobe discovery and process management.
- Application-managed cache locations and lifecycle on macOS and Windows.
- Safeguards and workflow for eventual filesystem-modifying operations.
