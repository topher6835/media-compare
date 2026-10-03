# Windows exFAT Slice 3 implementation report

Date: 2026-10-02, America/New_York. Starting clean HEAD: `0a777340ca2f6736f7f66d843d34c23dcf89033d`. Slices 1-2 are externally reviewed, approved and committed. This work is uncommitted.

## External-review durable-dispatch correction

Inspected the actual NTFS codecs/models, persisted fixtures and binding/preparation/authority tests. The unchanged NTFS context wire format is `["windows-ntfs-context-v1", contextId, contextRevision, anchorLp1, anchorLk1, volumeSerial, anchorFileId]`. The unchanged Source format is `["windows-ntfs-source-v1", sourceId, sourceRevision, contextId, contextRevision, rootLp1, rootLk1, volumeSerial, rootFileId]`. Serial and FileIdInfo evidence remain 16-/32-digit lowercase hex.

Added `WindowsDurableEvidenceFormat`: bounded duplicate/trailing-token-strict JSON parsing recognizes only those exact array tags and the exact exFAT object `version` values. It distinguishes NTFS/exFAT context/Source formats, unknown versions/formats and malformed JSON/discriminators. Role-specific access rejects unknown/malformed/wrong-slot formats; the selected full codec still validates every field. There is no decoder-failure fallback. Replaced all five former `hasExfatShape` call sites in `CurrentLocationAuthority`, `SourcePreparationState`, `SourcePreparationService` and the Source/context branches of `ExfatLifecycleTransactions`; removed the helper. Bound Windows preparation validates the full Source/context pair before either profile path, rejecting mixed profiles before acquisition. Existing registry ownership still causes conservative invalidation before invalid persisted evidence is rejected/rolled back.

Added nine portable `WindowsDurableEvidenceDispatchTests` covering actual encoded NTFS/exFAT formats, READY/authority dispatch, both mixed-profile directions, unknown/malformed/wrong-version/wrong-slot/duplicate/trailing evidence, incomplete known envelopes, legal surrounding whitespace, preparation routing and lifecycle failure/revocation. Enhanced the existing NTFS binding/history test with actual READY/idempotent preparation assertions. The first targeted attempt exposed an overly strict test expectation that already-owned rejection would not enter a transaction; corrected it to require rollback and no commit after conservative revocation. That attempt is not a passed gate.

Correction focused command (from `backend/`):

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' '-Dtest=WindowsDurableEvidenceDispatchTests,WindowsExfatEvidenceCodecTests,WindowsExfatPreparationLifecycleTests,ExfatAuthorityWindowRegistryTests,SourcePreparationServiceTests,SourceBindingServiceTests,WindowsDisabledPreparationTests,WindowsNtfsBindingWriterTests,WindowsNtfsScanAuthorityTests,WindowsNtfsExecutionTests,SourceUnbindingServiceTests,LocationContextRetirementServiceTests,LocationContextReplacementServiceTests' test
```

Passed: 158 tests, 0 failures/errors, 22 skips; BUILD SUCCESS. The nine new dispatch tests, four strict exFAT codec tests and eleven exFAT preparation tests passed without skips. NTFS binding, authority and live execution passed. Windows skips include all fifteen macOS-only SourcePreparationService tests and existing host-dependent binding/unbinding cases. Log: `backend/target/slice3-dispatch-focused-passed-tests.log`. The initial 104-test attempt failed only the corrected transaction expectation; its log is `backend/target/slice3-dispatch-targeted-tests.log`.

Correction full backend command:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' test
```

Full backend passed: 1381 tests, 0 failures, 0 errors, 287 skips; BUILD SUCCESS, total time 09:03 minutes, finished 2026-10-02 18:01:37 America/New_York. Log: `backend/target/slice3-dispatch-backend-tests.log`. The skip count remains unchanged; live macOS acceptance is not claimed on Windows. Updated STATUS, ARCHITECTURE, DATA_MODEL and this report to describe explicit known-format dispatch and fail-closed behavior. No production gate, mounted exFAT acceptance or Slice 4+ work is included; envelope fields, runtime ownership/cardinality/release/timeout and receipt/storage/pipeline behavior are unchanged by this correction.

## Implemented behavior and scope

1. **Context envelope:** immutable `windows-exfat-context-v1` in `location_context.continuity_evidence_json`: context UUID/revision, canonical drive-domain lp1/lk1, EXFAT profile, serial/GUID/drive root, explicit acceptance provenance/time. These are accepted configuration signals, never durable physical-medium identity.
2. **Source envelope:** immutable `windows-exfat-source-v1` in Source binding evidence: Source ID/revision, context ID/revision, configured root/key, separately resolved canonical root lp1/lk1, matching volume evidence, directory/non-link classification and initial binding provenance/time. No marker, legacy index, native handle, process ID, live flag or runtime UUID is stored.
3. **Strict codecs:** deterministic alphabetical property ordering, duplicate/unknown/missing/null rejection, no scalar coercion, valid Unicode, canonical lp1/lk1 agreement, positive IDs/binding revisions, bounded text and 128 KiB UTF-8 documents. Jackson's generic scalar setting does not reject numbers/booleans converted to strings, so explicit Textual coercion failures supplement it; tests include an otherwise-valid numeric serial. Application UUIDs require canonical lowercase IETF variant 2/version 4; OS volume GUID syntax is separate and permits the qualified version-1 GUID. Malformed exFAT evidence never falls back to NTFS.
4. **Runtime ownership:** one empty-on-start registry per selected catalog runtime, depending on CatalogPaths/CatalogOwnership. A window contains exact Source/context/open-period snapshots, configured/resolved evidence, a fresh window UUID, retained authority, acquisition time, phase, cancellation/release state and operation users. Exact lookup requires runtime UUID, Source ID, window UUID and matching durable snapshots. Retained chains and pending attempts also check the exact owning runtime object.
5. **UUIDs/cardinality:** runtime/window/attempt IDs use `UUID.randomUUID()` and canonical v4 validation. One current window and one pending Prepare attempt per Source; concurrent duplicate attempts fail closed. Replacement revokes/drains the old window. A later UUID at identical durable revisions never renews old holders/receipts. No ID is restored from the database or Session manifest.
6. **Acquisition:** fresh qualified Slice 1 protected drive-to-Source directory authority; every ancestor remains held. Native/NIO exFAT classification, canonical final paths, volume/route and no-follow classification are revalidated. Native acquisition runs outside the publication gate/SQLite transaction. Rejected acquisitions close their retained resources before releasing attempt accounting.
7. **First Prepare/Accept:** enforce direct local route and Session separation, guarded context selection/same-anchor collision rules, REVIEW_REQUIRED acceptance with no bound Sources, atomic normal Source binding/open period, then install only after commit. Public production acceptance remains unavailable. Caller transactions cannot surround Prepare or install uncommitted authority.
8. **Routine reacquisition:** capture existing evidence before native IO, check root/volume agreement, and perform a short writer-reserved unchanged reread before installing a new UUID. No Source/context update, rebind, revision advance, period rewrite, membership change, file observation or MISSING inference occurs. Tests compare complete Source/context/period/membership/FileEntry/content rows, including last-known ACTIVE/PRESENT history.
9. **Already-live Prepare:** freshly revalidate retained native authority and separation; reread unchanged durable snapshots, retain the same UUID and deadline. Invalid retained authority revokes affected context windows and fails that request. Structured-unbound Prepare remains STATE_CHANGED; actual fixed-root rebind is a separate internal command with the existing closed-period/no-ACTIVE-membership rules.
10. **Release/timeout/shutdown:** revoke admission immediately; asynchronously drain the outer publication gate and then close resources exactly once. RELEASED means actual closure; otherwise return DRAINING within the approved 30-second budget. Unused Prepare expires after five minutes, inert HANDOFF after two minutes. Limits are 64 prepared Source scopes, 4096 retained/reserved directories and depth 256. Shutdown cancels pending attempts and revokes before executor drains; registry cleanup precedes catalog ownership release. Stuck/uncertain closure remains revoked, denies subsequent exFAT acquisition and retains catalog ownership until process exit.
11. **Transaction ordering:** registry monitor covers maps/state only; no native IO or SQLite waiting under it. Operation leases hold the publication read gate through caller transaction commit and are thread-confined. Installation and exFAT durable lifecycle coordinators hold the write gate outside writer reservation through commit. True unbind/context transitions conservatively invalidate affected runtime authority; rejected durable transitions still roll back their rows. Native profile transaction semantics are preserved.
12. **Restart/projection:** actual catalog close/reopen tests load the same durable READY/envelopes/history with an empty new registry. Old runtime/window references fail; explicit acceptance receives different runtime/window UUIDs with unchanged durable rows. Internal `liveAuthorityAvailable`/window-ID projection performs no host IO and is not persisted. No API/frontend activation is included.
13. **Restricted forms:** acquisition retains Slice 1 direct local Windows exFAT restrictions. UNC/network/mapped shares, SUBST/redirection, device paths, directory-mounted aliases and ambiguous mount routes remain unsupported. NTFS uncertainty never selects exFAT. VeraCrypt is not an identity provider.
14. **Preservation/gating:** NTFS still uses FileIdInfo; APFS authority is unchanged. Standard indexing remains unable to use exFAT even with test-acquired windows. Native flows retain null tokens/receipts; occurrence persistence remains inert. `WindowsExfatSupport.PRODUCTION.available()` remains false, with no property/environment/HTTP/frontend activation. No schema/Session manifest, production FileEntry creation, discovery/publication/supersession, content pipeline, metadata/preview consumption, persisted reveal/open or cleanup support was added.
15. **Limitations/deviations:** existing Source keys encode configured spelling, so the new envelope separately preserves configured and resolved canonical keys instead of changing the native Source key contract. Each Source owns an independent protected chain; resources are counted conservatively without a directory pool. Any future sharing must meet the frozen runtime/context/revision/route/continuous-lifetime restrictions. Bundle/Job/phase hooks are inert; running-work progress, shared traversal, API/release endpoints and frontend workflow belong to later slices. Tests use injected authority/volume seams and disposable NTFS primitives; no mounted exFAT production acceptance occurred. Live macOS tests cannot establish acceptance on Windows.

## Files

Paths in the following lists are relative to `backend/src/main/java/io/github/topher6835/mediacompare/` unless specified otherwise.

Added production files:

- `filesystem/WindowsExfatContextEvidence.java`
- `filesystem/WindowsExfatSourceEvidence.java`
- `filesystem/WindowsExfatVolumeEvidence.java`
- `filesystem/WindowsExfatEvidenceCodec.java`
- `filesystem/WindowsDurableEvidenceFormat.java`
- `filesystem/ExfatAuthorityScope.java`
- `filesystem/ExfatRetainedRoot.java`
- `filesystem/ExfatAuthorityWindowRegistry.java`
- `catalog/WindowsExfatBindingWriter.java`
- `catalog/WindowsExfatSourcePreparationService.java`
- `catalog/ExfatLifecycleTransactions.java`

Modified production files:

- `filesystem/WindowsExfatHostFileSystem.java`
- `catalog/CurrentLocationAuthority.java`
- `catalog/SourcePreparationState.java`
- `catalog/SourcePreparationService.java`
- `catalog/SourceUnbindingService.java`
- `catalog/LocationContextRetirementService.java`
- `catalog/LocationContextReplacementService.java`
- `config/CatalogConfiguration.java`
- `scan/Version2IndexingExecutor.java`
- `analysis/MediaMetadataExecutor.java`
- `preview/ThumbnailScheduler.java`

Test paths are relative to `backend/src/test/java/io/github/topher6835/mediacompare/`: added `filesystem/WindowsExfatEvidenceCodecTests.java`, `filesystem/ExfatAuthorityWindowRegistryTests.java`, `filesystem/ExfatSlice3Fixtures.java`, `catalog/WindowsExfatPreparationLifecycleTests.java` and `catalog/WindowsDurableEvidenceDispatchTests.java`; modified `filesystem/WindowsProtectedAccessTests.java` and `catalog/WindowsNtfsBindingWriterTests.java`. Cases cover strict envelopes/dispatch, exact ownership/revisions/evidence/periods, replacement/release/expiry/bundles, pending attempts, resource limits, native failure/closure, shutdown, actual paused commit, uncommitted caller refusal, first binding/rebind/collision/unsupported forms, production denial and actual database reopen/no-change reacquisition.

Documentation: modified `README.md`, `docs/ARCHITECTURE.md`, `docs/DATA_MODEL.md`, `docs/DECISIONS.md`, `docs/STATUS.md` and `docs/WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md`; added this report. Historical investigation/qualification/evidence logs are unchanged. Slice 4 is next only after separate review/authorization.

## Initial Slice 3 validation

Commands run from `backend/`, using installed Maven 3.9.15, Java 21, the existing offline dependency cache and workspace-local JUnit temporary storage. Review-approved cache access avoids the known sandbox cached-JAR restriction. No tooling/dependency installation occurred.

Initial focused attempts exposed a test assertion that incorrectly matched the required word `windows` as a runtime field. Corrected field-name assertions passed. Two full runs were deliberately cancelled: the first to add the final cross-runtime pending-attempt and transaction/operation ownership guards, the second after a direct Jackson check exposed numeric-to-text coercion requiring explicit rejection. Their interrupted-fork results are not passed suites. Ignored attempt logs remain under `backend/target/`.

Relevant regressions (342 tests, 0 failures/errors, 25 skips):

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' '-Dtest=WindowsExfatEvidenceCodecTests,ExfatAuthorityWindowRegistryTests,WindowsExfatPreparationLifecycleTests,WindowsProtectedAccessTests,SourcePreparationServiceTests,SourceBindingServiceTests,SourceUnbindingServiceTests,SourceRebindingServiceTests,SourceRootRelocationServiceTests,SourceBindingPeriodMigrationTests,LocationContextActivationServiceTests,LocationContextAcceptanceServiceTests,LocationContextRetirementServiceTests,LocationContextReplacementServiceTests,SessionStartupTests,SessionTests,SessionFilesystemPolicyTests,WindowsHostFileSystemTests,WindowsNtfsNativeTests,WindowsExfatNativeTests,WindowsExfatNativeCallsTests,WindowsNtfsBindingWriterTests,WindowsNtfsExecutionTests,WindowsNtfsScanAuthorityTests,WindowsDisabledPreparationTests,MacOsApfsContinuityVerifierTests,MacOsApfsLocationContextEvidenceCodecTests,MacOsApfsSourceRootEvidenceCodecTests,ScanObservationAuthorityTests,ExfatOccurrenceMigrationTests,ExfatOccurrenceRepositoryTests,ExfatObservationReceiptCodecTests' test
```

`SourceRebindingServiceTests`/`SourceRootRelocationServiceTests` are not separate classes; those cases run in `SourceBindingServiceTests`. Live NTFS tests passed. Windows skips include live macOS preparation/acceptance and existing privilege/host-dependent cases; portable APFS codec/authority tests passed. Log: `backend/target/slice3-regression-tests.log`.

Final strictness/ownership/shutdown focused command:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' '-Dtest=WindowsExfatEvidenceCodecTests,ExfatAuthorityWindowRegistryTests,WindowsExfatPreparationLifecycleTests,WindowsProtectedAccessTests,Version2IndexingExecutorTests,MediaMetadataExecutorTests,ThumbnailSchedulerTests' test
```

Passed: 44 tests, 0 failures/errors/skips. Log: `backend/target/slice3-strict-final-targeted-tests.log`. Earlier ownership-focused and targeted runs passed 43 and 40 tests respectively with zero failures/errors/skips.

Full backend command:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' test
```

Passed: 1372 tests, 0 failures, 0 errors, 287 skips; BUILD SUCCESS, total time 10:17 minutes, finished 2026-10-02 17:18:19 America/New_York. Final log: `backend/target/slice3-backend-complete-tests.log`. The skip count matches the approved Slice 2 backend baseline; live macOS/host-dependent cases remain unavailable on Windows. No package/frontend gate is claimed or required for this slice.

Repository scope audit inspected the tracked diff and every added production/test file. Frontend, migrations/resources, the production gate, NTFS/APFS authority implementations, FileEntry/occurrence/membership persistence and scan discovery/publication are unchanged. Executor changes only revoke exFAT runtime authority before their existing shutdown drain; metadata/preview consumption is unchanged. No runtime authority is persisted and routine reacquisition performs no implicit rebind. No mounted exFAT production acceptance, commit or push occurred. HEAD remains the starting baseline and no files are staged.

Repository-root `git diff --check` passed (exit 0). UTF-8/BOM/trailing-whitespace checks passed for all 36 modified/added files. Current `git status --short` contains 19 modified tracked files and 17 untracked added files, all within this slice:

```text
 M README.md
 M backend/src/main/java/io/github/topher6835/mediacompare/analysis/MediaMetadataExecutor.java
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/CurrentLocationAuthority.java
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/LocationContextReplacementService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/LocationContextRetirementService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/SourcePreparationService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/SourcePreparationState.java
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/SourceUnbindingService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/config/CatalogConfiguration.java
 M backend/src/main/java/io/github/topher6835/mediacompare/filesystem/WindowsExfatHostFileSystem.java
 M backend/src/main/java/io/github/topher6835/mediacompare/preview/ThumbnailScheduler.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version2IndexingExecutor.java
 M backend/src/test/java/io/github/topher6835/mediacompare/catalog/WindowsNtfsBindingWriterTests.java
 M backend/src/test/java/io/github/topher6835/mediacompare/filesystem/WindowsProtectedAccessTests.java
 M docs/ARCHITECTURE.md
 M docs/DATA_MODEL.md
 M docs/DECISIONS.md
 M docs/STATUS.md
 M docs/WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md
?? backend/src/main/java/io/github/topher6835/mediacompare/catalog/ExfatLifecycleTransactions.java
?? backend/src/main/java/io/github/topher6835/mediacompare/catalog/WindowsExfatBindingWriter.java
?? backend/src/main/java/io/github/topher6835/mediacompare/catalog/WindowsExfatSourcePreparationService.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/ExfatAuthorityScope.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/ExfatAuthorityWindowRegistry.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/ExfatRetainedRoot.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/WindowsDurableEvidenceFormat.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/WindowsExfatContextEvidence.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/WindowsExfatEvidenceCodec.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/WindowsExfatSourceEvidence.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/WindowsExfatVolumeEvidence.java
?? backend/src/test/java/io/github/topher6835/mediacompare/catalog/WindowsDurableEvidenceDispatchTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/catalog/WindowsExfatPreparationLifecycleTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/filesystem/ExfatAuthorityWindowRegistryTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/filesystem/ExfatSlice3Fixtures.java
?? backend/src/test/java/io/github/topher6835/mediacompare/filesystem/WindowsExfatEvidenceCodecTests.java
?? docs/WINDOWS_EXFAT_SLICE3_REPORT.md
```
