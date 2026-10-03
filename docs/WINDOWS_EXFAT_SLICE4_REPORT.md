# Windows exFAT Slice 4 implementation report

Date: 2026-10-03, America/New_York. Starting clean approved baseline: `35b76e0d27c19cd6a937dd03aeec6ed759dda174`. Slices 1-3 are externally reviewed, approved and committed. Slice 4 work is uncommitted. Production exFAT remains disabled.

## Implementation and ownership

1. **Admission/window association:** `IndexingRunAcceptance` captures prepared exact references through `ExfatScanBundles`, then brackets a catalog `TransactionTemplate` with the registry write gate. The existing v3 ScanRun/SCAN Job/ScanRunSource creation occurs in that transaction. V3 has an explicit exFAT admission path rather than interpreting every Windows Source as NTFS.
2. **Bundle ownership/rollback:** one fresh bundle UUID associates all captured windows with the same ScanRun/SCAN Job; `associateAll` validates every member before changing any phase. Exact scope includes full Source/context/open-period snapshots, configured/resolved root evidence and runtime/window UUID. Failed association rolls back durable admission; failed commit revokes transient associations and removes the runtime bundle. Scheduling follows commit. Request-key replay never captures or attaches new authority.
3. **Shared traversal:** `WindowsExfatDiscoveryWalker` groups selected prepared Sources by exact context, finds maximal selected roots and traverses a containing root once. Same-observation fan-out uses structural exact-root containment; disjoint roots walk independently. All participating generations start before publication. Unselected/unprepared/historical Sources cannot receive a new membership. Native walker/batching is unchanged.
4. **Retained acquisition/evidence:** `WindowsExfatDiscoveryAccess` is a discovery-only protected-resource seam; its native implementation uses Slice 1 `openDirectoryChain`/`openProtectedFile`. Every ancestor remains held. Enumeration preserves spelling and rejects uncertain classifications/case aliases. Before/after regular-file evidence reconciles native/NIO attributes, exact size/mtime/final route, exFAT volume route and contradiction-only legacy index. Creation/attribute/index/route changes fail closed. No later-original-read API was introduced.
5. **Same-handle SHA:** discovery reads byte zero through EOF using the file's exact protected channel, 64 KiB buffers, checked byte accounting and the existing SHA-256 analysis algorithm. Every independent positive hashes completely, including unchanged rescans. Cancellation/window checks run between reads/entries. Actual progress renews a constructor-configurable five-minute inactivity deadline; long healthy scans have no total-duration deadline. Expiry revokes stalled work without prematurely closing IO resources. Hash IO/root revalidation occurs outside SQLite transactions and outside the short publication gate.
6. **Positive transaction:** `ExfatObservationPublicationService` acquires exact operation/publication leases after hashing, then reserves the writer and checks exact bundle, RUNNING SCAN/DISCOVERY, unchanged durable scope, DISCOVERING generation, current route/profile and still-open file. Final FileEntry/receipt/memberships, supersession and physical progress commit atomically. Any rejection rolls all those writes back.
7. **IDs/token/receipt:** the Slice 2 allocator reserves one FileEntry ID and one ID per participating membership under the writer. A fresh canonical v4 token and the complete immutable `windows-exfat-observation-v1` receipt are formed before insertion. Receipt entries include resulting membership IDs/revisions, Source routes, windows and traversal provenance. No provisional committed row, staging table or mutable receipt is used. Never-committed numeric IDs may be reused; committed tokens are never reused for a different positive.
8. **Supersession:** page active resolved routes in the verified context, then retire all older equivalent exFAT ACTIVE memberships across Sources, including ACTIVE/MISSING and unselected routes. Presence is preserved and revisions use checked increments. Historical FileEntries/content/analyses remain intact. Native resolved collisions fail; legitimate UNRESOLVED path history follows the existing retirement rule. Supersession is a positive operation, not MISSING.
9. **Case/alias handling:** exact keys remain lossless. On Windows, Java host `Path` equality identifies spelling-alias candidates; portable fake tests use case-insensitive component comparison. Candidates are confirmed by the newly held exact final route and uniquely enumerated ancestry. Case-only file/descendant-ancestor changes create new occurrences. No folded key, old-path open or rename inference from hash/index is used. Ambiguous enumeration rejects before publication.
10. **Commit-before-close:** hash/revalidate, publication lease, writer transaction, commit, publication-lease release, file/child closure. IO retention leases keep accepted roots alive without holding the publication gate. Drain waits for users outside that gate, preventing a cancelled IO user from deadlocking against a drain. Close uncertainty revokes access, denies acquisition and retains catalog ownership; committed evidence survives.
11. **Progress:** DISCOVERY counts one published physical observation per retained file, even with multiple memberships. Source-local generations/coverage still record each participating Source. Assignment/hash count the shared occurrence once. Native counting/batching semantics remain unchanged; result schemas are unchanged.
12. **Missing claims:** `MissingClaimAuthority` has an optional dedicated `WindowsExfatScanAuthority`. ExFAT claims retain exact scope/runtime/window/bundle/scan/job/row/generation. Both complete-discovery and missing writers validate that identity under leases. The missing writer also rejects native, malformed or foreign-context resolved occurrences before sweeping; legitimate migrated UNRESOLVED history retains the existing rule. Only complete authorized traversal reaches reconciliation; failed/subtree-inaccessible/ambiguous/cancelled/resource-limited/end-check-failed traversal preserves committed positives without an unseen sweep. A later window at identical revisions cannot revive a claim.
13. **CONTENT_ASSIGNMENT:** `ExfatReceiptAuthority` checks strict receipt/FileEntry coherence, original live bundle/windows, catalog/open-period snapshots, completed generation, all qualifying membership revisions/routes and receipt Source coverage. `ContentAssignmentWriter.assignExfat` creates/attaches one distinct immutable ContentRecord under the existing writer/rollback boundary. Equal bytes never merge records. No original is opened or hashed.
14. **CONTENT_HASHING:** strict authority/receipt checks precede cache lookup. Current ContentRecord size and receipt-consistent membership/FileEntry revisions are required. `ContentHashWriter.publishExfat` revalidates and publishes the receipt SHA using the existing analysis definition. Matching completed same-occurrence/content results are reusable; conflicting size/digest or missing/malformed/foreign/stale receipt fails.
15. **No original-path rehash:** exFAT candidates use `ExfatReceiptProcessingService`; native candidate queries remain null-token-only. Receipt processing has no filesystem/channel/hasher dependency. Fake flows inject an original-path hasher that fails on invocation, verify exactly one discovery open, and continue receipt hashing with the fake original removed/read-disabled. Missing receipt is included in exFAT candidate validation rather than falling through to the native hasher.
16. **Interruption/recovery:** `Version2InterruptionRecovery` revokes the associated runtime Job before durable failure handling. Startup has an empty registry and fails unfinished SCAN work under existing recovery rules. Committed receipts, ContentRecords and completed hashes survive; no automatic continuation or missing inference occurs. Media metadata recovery required no change because Slice 4 owns no exFAT metadata work.
17. **Scheduling:** rejected submissions, shutdown races and cancellation before execution revoke associated authority and use existing Job failure semantics. Execution failure also revokes its bundle. Successful internal indexing enters existing finite HANDOFF without implementing metadata handoff consumers. No stranded IN_BUNDLE window remains after failure.
18. **Native preservation:** NTFS retains FileIdInfo and APFS retains continuity authority. Native publication still reuses address-based FileEntries, refreshes by metadata and keeps null occurrence evidence. Its discovery batching, Source-local reconciliation and later `ContentHashFileHasher` file-authority/read mechanics are unchanged. The native hasher has an optional buffer-progress callback for mixed bundles; ordinary native calls use the same behavior with no callback work. Stage IO retention is separate from short publication leases, so native reads never run inside an exFAT publication transaction. Existing API test fixture wiring changed only for the new admission transaction boundary; assertions were retained.
19. **Production gate:** `WindowsExfatSupport.PRODUCTION.available()` remains false. Normal public Prepare/Analyze admission stays unavailable, including when test-acquired live windows exist beside production-constructed admission. Package-local test construction is the only execution seam; no property/environment/HTTP/UI bypass exists.
20. **Slice boundary:** no Slice 5 protected later-original-read workflow, supplied-channel ImageIO, decoder allowlist, exFAT metadata/preview extraction, retry/handoff consumer, reveal/Explorer, cleanup/preflight activation or frontend/API activation. No Slice 6 activation. No schema/migration/dependency/system-software change.

## Files

Production paths below are relative to `backend/src/main/java/io/github/topher6835/mediacompare/`.

Added:

- `scan/ExfatScanBundles.java`
- `scan/authority/WindowsExfatScanAuthority.java`
- `scan/WindowsExfatDiscoveryAccess.java`
- `scan/WindowsExfatNativeDiscoveryAccess.java`
- `scan/WindowsExfatDiscoveryWalker.java`
- `scan/ExfatObservationPublicationService.java`
- `scan/ExfatReceiptAuthority.java`
- `scan/ExfatReceiptProcessingService.java`

Modified:

- `filesystem/ExfatAuthorityWindowRegistry.java`
- `catalog/ExfatOccurrenceRepository.java`
- `catalog/ContentAssignmentWriter.java`
- `scan/IndexingRunAcceptance.java`
- `scan/Version3ScanExecutionService.java`
- `scan/Version2BackgroundIndexingService.java`
- `scan/Version3DiscoveryService.java`
- `scan/Version3DiscoveryCompletionWriter.java`
- `scan/SourceMembershipPublicationService.java`
- `scan/Version3ReconciliationService.java`
- `scan/ContentAssignmentService.java`
- `scan/Version2ContentAssignmentService.java`
- `scan/Version2ExecutionState.java`
- `scan/Version2InterruptionRecovery.java`
- `analysis/ContentHashingService.java`
- `analysis/ContentHashFileHasher.java`
- `analysis/ContentHashWriter.java`
- `analysis/Version2ContentHashingService.java`
- `scan/authority/MissingClaimAuthority.java`

Test paths are relative to `backend/src/test/java/io/github/topher6835/mediacompare/`.

Added `scan/ExfatSlice4TestSupport.java`, `scan/ExfatSlice4FlowTests.java`, `scan/ExfatSlice4GuardsTests.java`, `scan/ExfatSlice4ReceiptTests.java`, `scan/ExfatSlice4ConcurrencyTests.java`. Modified `IndexingRunApiTests.java` constructor/transaction fixture wiring, `filesystem/ExfatSlice3Fixtures.java` for a short deterministic drain budget, and `filesystem/ExfatAuthorityWindowRegistryTests.java` with a fake-clock no-progress/healthy-long-run case. No native assertion was weakened.

New flows cover parent/nested ordering, disjoint roots, unchanged independent rescans, selected-subset supersession of ACTIVE/PRESENT and ACTIVE/MISSING history, case-only file/ancestor changes, unresolved history, source-local missing and physical exact-duplicate counts. Guard/receipt cases cover admission rollback, replay, scheduling/shutdown, stale scope/window/generation, incomplete traversal, transaction rollback, revision overflow, native contradictions, receipt mismatches, cache ordering, distinct/idempotent content, no later original read, interruption and actual catalog restart. Latch tests pause hashing and commit during Release and check held resources/committed rows. Close-failure and rejected-acquisition cleanup tests deny subsequent runtime acquisition without deleting committed evidence. Fake access checks that enumeration/acquisition/reads/evidence/close occur outside SQLite transactions.

Documentation: modified `README.md`, `docs/ARCHITECTURE.md`, `docs/DATA_MODEL.md`, `docs/DECISIONS.md`, `docs/STATUS.md`, `docs/WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md`; added this report. Frozen historical qualification/investigation/report/evidence is unchanged.

## Validation

Commands run from `backend/` with installed Maven/Java 21, the existing offline cache and workspace-local JUnit temporary storage. Reviewed cache access addresses the earlier slices' sandbox cached-JAR restriction. No tooling/dependency installation occurred.

Focused command:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' '-Dtest=ExfatSlice4*Tests' test
```

Passed: 66 tests, 0 failures/errors/skips; BUILD SUCCESS, 06:22 minutes, finished 2026-10-03 10:30:05 America/New_York. Log: `backend/target/slice4-targeted-tests.log`. The preceding complete focused run passed 58 tests before additional stale-publication/resource cases and guarded-writer strengthening. Initial attempts did not pass: sandbox cached-JAR compilation failed, test compilation needed native fixture constructor wiring, and an assertion incorrectly expected singleton duplicate detail to be absent. Corrected current-count assertions preserve existing duplicate semantics. Logs retain those attempts; they are not passed gates.

Relevant regression command (includes the focused cases again after native-admission preservation review):

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' '-Dtest=ExfatSlice4*Tests,WindowsProtectedAccessTests,WindowsExfatNativeTests,WindowsExfatNativeCallsTests,ExfatOccurrenceMigrationTests,ExfatOccurrenceRepositoryTests,ExfatObservationReceiptCodecTests,WindowsExfatEvidenceCodecTests,ExfatAuthorityWindowRegistryTests,WindowsExfatPreparationLifecycleTests,WindowsDurableEvidenceDispatchTests,IndexingRunApiTests,IndexingRunReadApiTests,Version2BackgroundIndexingTests,Version2RecoveryTests,Version2IndexingExecutorTests,Version3ExecutionTests,Version3DiscoveryWalkerTests,SourceMembershipAuthorityTests,DiscoveryApiTests,DiscoveryTraversalFailureApiTests,ReconciliationApiTests,ContentAssignmentApiTests,ContentAssignmentStaleServiceTests,ContentHashingApiTests,ContentHashingPagingAndContinuationTests,WindowsNtfsExecutionTests,WindowsNtfsScanAuthorityTests,ScanObservationAuthorityTests,MacOsApfsContinuityVerifierTests,MacOsApfsLocationContextEvidenceCodecTests,MacOsApfsSourceRootEvidenceCodecTests,ExactDuplicateApiTests,MediaMetadataRecoveryTests' test
```

Initial relevant regressions passed: 323 tests, 0 failures/errors, 55 skips; BUILD SUCCESS, 07:40 minutes, finished 2026-10-03 10:38:28 America/New_York. Log: `backend/target/slice4-regression-tests.log`. Host-dependent skips are retained; actual NTFS execution and portable APFS authority checks passed. `MediaMetadataRecoveryTests` is not a standalone class; the final regression below uses the actual metadata Job/executor classes, and the full suite also covers recovery.

Affected focused command after the inactivity/publication-lease review:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' '-Dtest=ExfatSlice4*Tests,ExfatAuthorityWindowRegistryTests,WindowsNtfsExecutionTests,ContentHashingPagingAndContinuationTests' test
```

Passed: 83 tests, 0 failures/errors, 1 skip; BUILD SUCCESS, 06:27 minutes, finished 2026-10-03 10:49:30 America/New_York. Log: `backend/target/slice4-final-focused-tests.log`. This includes fake-clock healthy-long-run/inactivity expiry and the existing live NTFS execution tests.

Final relevant regression command, including additional interruption and contradictory missing-sweep cases:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' '-Dtest=ExfatSlice4FlowTests,ExfatSlice4ConcurrencyTests#interruptedHashFailsThroughRecoveryWithoutInferringMissing,ExfatSlice4GuardsTests#completeTraversalCannotSweepAContradictoryNativeResolvedOccurrenceMissing,WindowsProtectedAccessTests,WindowsExfatNativeTests,WindowsExfatNativeCallsTests,ExfatOccurrenceMigrationTests,ExfatOccurrenceRepositoryTests,ExfatObservationReceiptCodecTests,WindowsExfatEvidenceCodecTests,ExfatAuthorityWindowRegistryTests,WindowsExfatPreparationLifecycleTests,WindowsDurableEvidenceDispatchTests,IndexingRunApiTests,IndexingRunReadApiTests,Version2BackgroundIndexingTests,Version2RecoveryTests,Version2IndexingExecutorTests,Version3ExecutionTests,Version3DiscoveryWalkerTests,SourceMembershipAuthorityTests,DiscoveryApiTests,DiscoveryTraversalFailureApiTests,ReconciliationApiTests,ContentAssignmentApiTests,ContentAssignmentStaleServiceTests,ContentHashingApiTests,ContentHashingPagingAndContinuationTests,WindowsNtfsExecutionTests,WindowsNtfsScanAuthorityTests,ScanObservationAuthorityTests,MacOsApfsContinuityVerifierTests,MacOsApfsLocationContextEvidenceCodecTests,MacOsApfsSourceRootEvidenceCodecTests,ExactDuplicateApiTests,MediaMetadataJobTests,MediaMetadataExecutorTests' test
```

Passed: 283 tests, 0 failures/errors, 64 skips; BUILD SUCCESS, 05:00 minutes, finished 2026-10-03 10:57:56 America/New_York. Log: `backend/target/slice4-final-regression-tests.log`. The final rejected-acquisition cleanup case was added afterward and is included in the full backend run.

Full backend command:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' test
```

Passed: 1451 tests, 0 failures/errors, 287 skips; BUILD SUCCESS, 15:26 minutes, finished 2026-10-03 11:15:49 America/New_York. Log: `backend/target/slice4-backend-tests.log`. All 69 new Slice 4 cases and the added registry inactivity case ran and passed. Existing host-dependent skips remain unchanged from the approved baseline. Live NTFS/protected native-access tests and portable APFS checks passed; no live macOS acceptance is claimed.

## External-review progress clarification

On 2026-10-03, inspected the retained uncommitted Slice 4 tree at the same approved baseline. Contrary to the review premise, `ContentHashFileHasher` already assigned each current read result to `count`; cumulative accounting used the separate `bytesRead`. Renamed `count` to `read` in the loop/accounting/callback so the intended condition is explicitly `if (read > 0) progress.run();`. This preserves existing runtime behavior, hashing bytes/SHA, EOF handling and native authority checks. The protected exFAT discovery loop, receipt hashing, registry, timeout duration and authority/publication behavior are unchanged.

Added `analysis/ContentHashFileHasherTests.java` using the existing protected `read` seam and a real `abc` file. Reads are controlled to return `1, 0, 2, -1`; callback counts before each read are `0, 1, 1, 2`, with two callbacks total after EOF. The test verifies the known SHA-256 and ordinary hasher parity. Two additional cases preserve rejection of short/excess read byte counts. No production test seam or dependency was added. Tests support the existing Windows/macOS hosts; this run establishes Windows validation only.

Correction validation uses the same offline cache and workspace-local temporary-storage arguments documented above:

```powershell
mvn -o '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-DargLine=-Djava.io.tmpdir=D:\Dev\projects\ai-projects\media-compare\backend\target\test-tmp' '-Dtest=ContentHashFileHasherTests,ExfatSlice4FlowTests,ExfatSlice4ConcurrencyTests,ExfatAuthorityWindowRegistryTests' test
```

Focused run passed: 33 tests, 0 failures/errors/skips; BUILD SUCCESS. Log: `backend/target/slice4-progress-focused-reviewed-tests.log`. The initial sandbox attempt failed during test compilation on cached-JAR access before tests ran; log: `backend/target/slice4-progress-focused-tests.log`. Retrying with reviewed access to the existing cache passed; no software/dependency installation occurred.

The relevant regression selection replaces the three Slice 4 selectors in the final relevant regression command above with `ContentHashFileHasherTests,ExfatSlice4*Tests`; all remaining selectors and Maven arguments are identical. Passed: 343 tests, 0 failures/errors, 64 skips; BUILD SUCCESS, 07:53 minutes. Log: `backend/target/slice4-progress-regression-tests.log`.

The full backend command above passed again: 1454 tests, 0 failures/errors, 287 skips; BUILD SUCCESS, 14:15 minutes. Log: `backend/target/slice4-progress-backend-tests.log`. All three new hasher cases, all 69 Slice 4 cases and all 13 registry cases passed. Host-dependent skips remain; no live macOS acceptance is claimed. This correction changes only the hasher read-result name, its new focused test, STATUS and this report. Production exFAT remains disabled. No Slice 5+ work, migration, frontend change, mounted exFAT acceptance, commit or push occurred.

## Limitations and scope audit

Production remains unavailable. Internal flows are qualified by fake protected-resource evidence, existing Slice 1 tests and native regressions, not a mounted exFAT production run. No mounted exFAT production acceptance occurred. Live macOS acceptance cannot be established on Windows.

Directory chains are conservatively duplicated/count-reserved rather than pooled; deep/large directories can fail the approved limits. Blocking native IO/CloseHandle cannot be forcibly made safe; Release remains DRAINING while users retain resources, and uncertain closure denies acquisition/retains ownership. No public cancellation API was introduced. Complete traversals currently fail the bundle conservatively on any discovery issue; already committed qualified positives survive. Case-only spelling changes below a retained Source root are covered; changed accepted root configuration still requires existing explicit authority/preparation rules.

The exact runtime authority is process-local and finite. Receipts survive restart/supersession as historical evidence, but interrupted work requires a new indexing request and fresh observation. Successful finite HANDOFF has no Slice 5 consumers. Equal SHA establishes exact bytes only; serial/GUID/path/index equality never supplies durable physical identity.

Actual diff audit confirms no Slice 5 protected later-original-read implementation, exFAT metadata/preview decoding, reveal/cleanup activation, frontend change, public activation, production gate flip or schema migration. Native authority tests/assertions and the APFS/NTFS authority implementations are unchanged. Independent exFAT positives always allocate new FileEntries; receipt hashing has no original-path reader. Incomplete discovery cannot supply reconciliation claims, and exact runtime references reject later-window borrowing. No commit or push has been made.

After the progress clarification, `git diff --check` passed (exit 0). Independent UTF-8/BOM/trailing-whitespace checks passed for all 43 changed/new files. `git status --short` shows 28 modified tracked files and 15 added untracked files, all unstaged. HEAD remains `35b76e0d27c19cd6a937dd03aeec6ed759dda174`. Test logs/build artifacts remain ignored.

```text
 M README.md
 M backend/src/main/java/io/github/topher6835/mediacompare/analysis/ContentHashFileHasher.java
 M backend/src/main/java/io/github/topher6835/mediacompare/analysis/ContentHashWriter.java
 M backend/src/main/java/io/github/topher6835/mediacompare/analysis/ContentHashingService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/analysis/Version2ContentHashingService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/ContentAssignmentWriter.java
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/ExfatOccurrenceRepository.java
 M backend/src/main/java/io/github/topher6835/mediacompare/filesystem/ExfatAuthorityWindowRegistry.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/ContentAssignmentService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/IndexingRunAcceptance.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/SourceMembershipPublicationService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version2BackgroundIndexingService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version2ContentAssignmentService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version2ExecutionState.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version2InterruptionRecovery.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version3DiscoveryCompletionWriter.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version3DiscoveryService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version3ReconciliationService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version3ScanExecutionService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/authority/MissingClaimAuthority.java
 M backend/src/test/java/io/github/topher6835/mediacompare/IndexingRunApiTests.java
 M backend/src/test/java/io/github/topher6835/mediacompare/filesystem/ExfatAuthorityWindowRegistryTests.java
 M backend/src/test/java/io/github/topher6835/mediacompare/filesystem/ExfatSlice3Fixtures.java
 M docs/ARCHITECTURE.md
 M docs/DATA_MODEL.md
 M docs/DECISIONS.md
 M docs/STATUS.md
 M docs/WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md
?? backend/src/main/java/io/github/topher6835/mediacompare/scan/ExfatObservationPublicationService.java
?? backend/src/main/java/io/github/topher6835/mediacompare/scan/ExfatReceiptAuthority.java
?? backend/src/main/java/io/github/topher6835/mediacompare/scan/ExfatReceiptProcessingService.java
?? backend/src/main/java/io/github/topher6835/mediacompare/scan/ExfatScanBundles.java
?? backend/src/main/java/io/github/topher6835/mediacompare/scan/WindowsExfatDiscoveryAccess.java
?? backend/src/main/java/io/github/topher6835/mediacompare/scan/WindowsExfatDiscoveryWalker.java
?? backend/src/main/java/io/github/topher6835/mediacompare/scan/WindowsExfatNativeDiscoveryAccess.java
?? backend/src/main/java/io/github/topher6835/mediacompare/scan/authority/WindowsExfatScanAuthority.java
?? backend/src/test/java/io/github/topher6835/mediacompare/analysis/ContentHashFileHasherTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice4ConcurrencyTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice4FlowTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice4GuardsTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice4ReceiptTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice4TestSupport.java
?? docs/WINDOWS_EXFAT_SLICE4_REPORT.md
```
