# Data Model

## Status and Scope

The persistent application schema has not been designed or implemented. This document records the confirmed conceptual data model that will guide a later SQLite schema design. Concept names below do not commit the project to exact table, column, enum, key, or Java class names.

The current physical database remains effectively empty: there are no versioned Flyway migrations or application tables, and the local database contains only Flyway's `flyway_schema_history` bookkeeping table.

## Confirmed Persistence Choices

- SQLite is the single selected catalog database.
- Spring JDBC is selected instead of JPA.
- Flyway owns schema migrations.
- The configured JDBC URL is `jdbc:sqlite:data/media-compare.db`.
- When the backend starts from `backend/`, the local database is `backend/data/media-compare.db`.
- Local files under `backend/data/` are ignored by Git; `backend/data/.gitkeep` preserves the directory.
- Flyway migrations belong in `backend/src/main/resources/db/migration/` and load from `classpath:db/migration`.
- A saved index or WorkingSet will be represented within the one catalog database, not in a separate database file.

## Conceptual Relationship View

```text
Source -> FileEntry -> ContentRecord -> AnalysisRecord -> specialized results
                           |
                           -> future relationships/groups

WorkingSet -> ContentRecord membership

ScanRun -> Job -> stages/checkpoints
```

This is pre-schema notation. It intentionally omits cardinalities, SQL names, columns, foreign keys, indexes, and ownership details that have not been decided.

## Source

A Source is a durable registered scan root, such as a folder, external drive, whole drive, or another filesystem root.

- It has an internal persistent identity.
- It defines a scan boundary and stores location/configuration information.
- Its absolute path is not its identity.
- It can move, mount at another path, or be temporarily unavailable without losing catalog history.
- Platform-specific filesystem or volume identifiers may be optional hints, but cannot be required identity.

The schema for current and historical Source locations is not yet decided.

## FileEntry

A FileEntry represents one known filesystem occurrence: “there is, or was, a file at this location within this Source.”

It conceptually retains:

- Its Source relationship.
- A path relative to that Source.
- Size, filesystem timestamps, and useful filesystem attributes.
- Current presence or absence.
- First-seen and last-seen information.
- An association with exact content when known.

Relative Source paths are preferred over absolute paths as occurrence identity. Path handling must remain compatible with Java `Path`/NIO and cannot assume a particular separator, drive-letter scheme, case behavior, or filesystem.

A missing file is not automatically deleted from the catalog. `PRESENT` and `MISSING` are the minimum conceptual states; no additional states or exact enum names are finalized. When bytes at an existing path change, the FileEntry remains the occurrence and its ContentRecord association can change.

## ContentRecord

A ContentRecord represents exact bytes independently of filesystem location.

- It has a stable internal database identity.
- Its primary identity is not an exact hash value.
- Multiple FileEntries can reference it after exact byte identity is trusted.
- Reusable analysis belongs primarily to it rather than to a path.
- It can remain useful when physical copies are missing and can be reused when bytes reappear later.

Original and transformed copies have different ContentRecords because their bytes differ. Their similarity or derivation is represented later through matching, relationships, or grouping—not by merging their exact-content identities. No separate logical-media abstraction is currently defined.

## Exact Hash Artifact

A trusted exact hash is a reusable artifact that confirms byte-for-byte identity. Hashing need not happen immediately for every FileEntry, and a hash is not the ContentRecord's database primary key.

The stored representation must identify its algorithm so the system is not permanently tied to one choice. SHA-256 is the leading initial candidate, but the schema and implementation choice remain open. Rules for reconciling separate ContentRecords that later receive the same trusted hash also remain to be designed.

Size and timestamp may mark content as probably unchanged for reconciliation, but they are never proof of byte identity.

## WorkingSet

A WorkingSet is a durable logical collection of catalog content used in repeated comparison or organization workflows.

- Membership references ContentRecords and does not duplicate their analysis.
- A WorkingSet can grow as additional Sources or content are introduced.
- Its identity and useful history survive when current FileEntries become `MISSING`.
- It enables new content to reuse and compare against analysis already stored for earlier content.

Exact membership history, removal, ordering, naming, and lifecycle semantics remain undecided.

## ScanRun

A ScanRun represents a user's requested operation. It is conceptually responsible for recording the selected Sources or WorkingSet, requested analysis options, requested mode, and lifecycle information.

Index, compare, and combined index-and-compare are conceptual modes only. Their final names and representation are not decided.

ScanRun is distinct from Job: the former captures intent, while the latter captures long-running execution.

## Job

A Job represents durable long-running work. Jobs may eventually cover scanning/indexing, deeper analysis, bulk filesystem operations, exports, and other future operations.

Conceptual Job information includes work type, status, current stage, progress, timing, and error information. Potential scan stages include discovery, reconciliation, hashing, media metadata, fingerprints, faces, embeddings, matching, deep comparison, and grouping. These stage names are illustrative and are not a finalized enum, API, or table design.

Job and stage state must support pause/resume after a complete application shutdown. The model should record durable, database-driven checkpoints rather than serialized Java execution state or a fragile exact filesystem iterator position. Work should be processed and committed in small batches, with incomplete work found by querying for missing compatible artifacts. Batch size and detailed checkpoint structures remain undecided.

## AnalysisRecord

An AnalysisRecord is the general provenance and lifecycle record for reusable analysis of a ContentRecord. Conceptually it answers:

- What kind of analysis ran?
- Which ContentRecord was analyzed?
- Which algorithm, model, or provider produced it?
- Which analyzer/model version and configuration produced it?
- What is its lifecycle status and timing?
- Did it fail, and is retry information needed?
- Where is its specialized result represented?

Exact persisted field names and lifecycle states are not finalized.

### Versioning and Reuse

An analysis artifact can be reused only when its analysis type, analyzer/model/provider, version, and relevant configuration are compatible with the request. When any compatibility-defining input changes, the new result is recorded separately instead of silently replacing the earlier result.

This applies to hashes, fingerprints, media metadata extractors, embeddings, face analysis, and AI-based results. It lets completed expensive work survive renames, moves, duplicates, later scan runs, and application restarts.

## Specialized Results

AnalysisRecord holds common provenance, versioning, and lifecycle information. Specialized structures hold results that need domain-specific representation or efficient querying, potentially including:

- Exact content hashes.
- Media metadata.
- Perceptual fingerprints.
- Embeddings.
- Detected faces and face embeddings.
- Video fingerprints.
- AI results.

The design will not place every analysis output into one generic JSON result column. Query-heavy data such as hashes and fingerprints must remain efficiently searchable and indexable. Final result structures are not yet defined.

Embeddings may initially use compact SQLite BLOB storage, likely float32 arrays, with similarity calculated in Java. This is an implementation candidate rather than a finalized schema. No vector database or vector extension has been selected.

## Filesystem Metadata and Media Metadata

Filesystem metadata belongs to FileEntry because it describes a physical occurrence. Examples include relative path, filename, size, filesystem timestamps, presence, and filesystem-specific attributes.

Media-derived metadata belongs to versioned ContentRecord analysis because it describes the exact bytes. Examples include dimensions, duration, codec, frame rate, stream information, orientation, useful EXIF information, and ffprobe-derived metadata.

Moving or renaming unchanged content must not invalidate compatible media-derived analysis.

## Face and Human Classification Concepts

Face-related data conceptually separates:

1. Face-detection analysis of a ContentRecord.
2. A detected face instance, potentially including a bounding region and confidence.
3. A face embedding with its own model/version provenance.
4. A later human-facing person or group classification in the organization layer.

Analyzer output and user classification are separate facts. The final detected-face, embedding, person, and grouping schema is not designed.

## AI Analysis

AI results use the same provenance and versioning approach as other analysis. Relevant provenance may include task, provider, model identifier/version, prompt or template version, and configuration. Local and cloud providers may coexist, AI remains optional, and catalog records must not depend directly on one provider.

The AI-provider interface and specialized AI-result schema remain undecided.

## Large Derived Data

The SQLite catalog will hold compact metadata, hashes/fingerprints, vectors, classifications, provenance, and relationships. Large derived files—such as thumbnails, previews, extracted video frames, and large intermediates—will generally live in an application-managed cache rather than as SQLite BLOBs.

The cache schema, lifecycle, and platform-specific locations on macOS and Windows are not yet decided.

## Historical and Reconciliation Rules

- Previously known FileEntries are marked missing rather than automatically deleted when unobserved.
- ContentRecords and reusable analysis can remain after physical copies disappear.
- WorkingSet identity and useful history can remain after members lose current physical occurrences.
- Filesystem discovery may be rerun while compatible expensive artifacts are reused.
- OS file IDs and similar attributes can be optional reconciliation hints, never mandatory cross-platform identity.
- Exact hashing provides trusted byte-identity confirmation; size and timestamp only support cheaper probable-unchanged decisions.

## Data Model Decisions Still Needed

The schema design pass still needs to decide:

- Exact tables, columns, keys, constraints, foreign keys, and indexes.
- Concrete names and representations for file classifications, presence states, run modes, job states, and stages.
- Source location history, relocation, availability, and optional platform-hint storage.
- FileEntry uniqueness and path case/collation behavior across filesystems.
- Deferred hashing and safe ContentRecord reconciliation transactions.
- WorkingSet membership and history semantics.
- ScanRun-to-Job relationships and durable checkpoint representation.
- Analysis compatibility keys, lifecycle states, retries, and retention.
- Specialized result schemas and embedding encoding.
- Matching-candidate persistence and relationship/group representation.
- Face/person/manual-classification structures.
- AI-result representation.
- Application-cache metadata, paths, cleanup, and recovery behavior.

No migration should be created until this conceptual model is translated into a reviewed first concrete schema.
