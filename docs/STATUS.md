# Status

## Current State

Media Compare is a Spring Boot/SQLite backend with a React frontend. The coordinated V6 SourceMembership/FileEntry authority cutover and SCAN execution version 3 are implemented. V7 adds durable Source binding-period history while the Source row remains the sole current binding authority. Trusted publication/reconciliation, four-stage execution, downstream candidate reads, duplicate counting, and the local macOS/APFS mount-boundary adapter remain implemented. This backend-only slice has focused validation; the full backend and frontend suites were not rerun for V7.

V7 validation: `mvn -q -Dtest=SourceBindingPeriodMigrationTests,SourceBindingServiceTests,SourceMembershipAuthorityTests,LocationContextRetirementServiceTests,LocationContextReplacementServiceTests test` passed 84 tests with no failures, errors, or skips. After adding an explicit FK-action assertion, `mvn -q -Dtest=SourceBindingPeriodMigrationTests test` passed its 3 tests. `git diff --check` passed.

The current operational relationship is `Source -> SourceMembership -> FileEntry -> ContentRecord -> AnalysisRecord`. FileEntry has no Source ownership or persisted presence. V5 FileEntries migrate one-for-one to UNRESOLVED rows with one historical membership each and no invented absolute identity. New trusted observations under overlapping Sources can share one RESOLVED FileEntry. Storage savings count physical FileEntries, not memberships.

## Documentation

`docs/ARCHITECTURE.md` describes current boundaries and v3 flow. `docs/DATA_MODEL.md` describes V7 fields, constraints, and backfill. `docs/DECISIONS.md` records durable V6/v3 and V7 decisions. This status records the current validation state and next work.

## What Currently Works

- V1–V5 migrations retain their historical meaning. Transactional Java Flyway V6 rebuilds FileEntry, backfills one SourceMembership per V5 row, verifies preserved IDs/content/metadata/provenance and foreign keys, and enforces resolved identity uniqueness, active membership path uniqueness, and the paired Source/context authority-revision CHECK. Partial V5 scan provenance remains legal.
- Transactional Java Flyway V7 creates `source_binding_period` and backfills one exact open period for each currently bound Source. Unbound Sources get none. It rejects partial Source binding shapes, checks foreign keys, and leaves existing catalog rows unchanged. Period constraints enforce unique bound revisions, one open period per Source, and paired chronological closing fields.
- LocationContext creation, acceptance, retirement, same-anchor replacement, and explicit first-time local macOS/APFS Source binding remain implemented. Valid legacy raw acceptance is persisted history but not current authority; malformed current acceptance remains an integrity failure.
- First binding now inserts its matching open period atomically with the guarded Source update under the existing SQLite writer reservation. A period-insert failure rolls back the Source update. Current binding and v3 admission/publication authority still come only from the Source row and `SourceBindingAuthority`.
- V3 admission requires every requested Source to have current supported bound local macOS/APFS authority. It is all-or-nothing and does no filesystem probe. Unbound, unsupported, Windows-host, and legacy-raw acceptance Sources cannot start new v3 work.
- V3 retains `DISCOVERY -> RECONCILIATION -> CONTENT_ASSIGNMENT -> CONTENT_HASHING -> COMPLETED`. Fresh context/root probes and a mount-aware directory walk run outside write transactions. Structured `df` mount-point evidence plus `diskutil` APFS identity reject child mounts even if the UUID matches; uncertain storage and symbolic links fail closed. The pure scan authority contract qualifies exact resolved candidates and complete-traversal missing claims.
- Writer-reserved short transactions reread Source/context authority, resolve FileEntry by `(location_context_id, location_key)`, validate canonical structured location and containment, and publish a SourceMembership. Legacy unresolved active-path collisions are retired atomically without retargeting history. Byte evidence change advances FileEntry revision and clears content; membership presence alone does not. Trusted reconciliation changes only the scanned Source's ACTIVE memberships and never makes a missing claim after incomplete traversal.
- Assignment, exact hashing, and media-metadata live candidate selection require a current ACTIVE/PRESENT trusted membership and RESOLVED FileEntry. Publication rechecks the persisted authority, path relationship, and candidate revisions. Hashing and media metadata use resolved absolute paths with file-evidence checks. Historical content/analysis remains readable.
- Exact duplicate grouping counts distinct FileEntries for physical copies and savings; Source counts and relative path details use memberships. The frontend duplicate detail view identifies Source paths separately from physical counts.
- Historical v1/v2 Jobs and ScanRuns remain readable. Their old discovery/reconciliation writers are closed after V6; startup recovery fails incompatible active v1/v2 work and interrupted v3 work. The public indexing start/polling path routes new work to v3.

## Known Limitations / Not Yet Implemented

- Only the tested local macOS/APFS binding profile can execute v3 scans. Windows, network, and other provider profiles remain future work. Source unbinding/rebinding, SourceMembership retirement on unbind, multiple active catalogs, catalog switching, automatic historical merge, and the shortened SCAN are not implemented. A future unbind can close the open period before or while withdrawing Source authority.
- Valid migrated unresolved history remains separate and is never inferred to equal a newly resolved FileEntry. A later trusted observation may retire the old path membership while preserving its ContentRecord and analyses.
- The future shortened scan ending after assignment is execution version 4 or later; v3 still includes exact hashing.

## Next Recommended Step

Implement Source unbinding/rebinding and SourceMembership retirement as a separate lifecycle slice, using V7 period history while keeping the Source row authoritative.
