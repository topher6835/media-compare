# Windows exFAT Slice 5 report

Date: 2026-10-04. Slice 5 is implemented in the working tree and awaits external review. Production exFAT remains disabled. This report describes the final local implementation; historical Slice 3/4 reports and retained-handle qualification are unchanged.

## Focused external-review corrections — 2026-10-05

The existing uncommitted Slice 5 implementation received only the three requested corrections:

- `MediaMetadataExecutor` obtains the abandoned list, catches each never-started finalizer's runtime/error failure separately, logs a constant bounded diagnostic and continues through the remaining tasks. A `finally` block explicitly checks executor termination even after failures; either failed finalization or nontermination retains catalog ownership until process exit. Recovery failure is reported, never marked as successful recovery. `Task.neverStarted()`'s cleanup `finally` and registry-before-executor shutdown remain unchanged. A package-local executor-injection constructor permits deterministic multiple-task tests without changing production queue bounds.
- `ExactDuplicateRepository` computes the projected path and strict persisted exFAT classification once. Physical actions are available only for a non-null projected path and non-exFAT occurrence; otherwise the reason is AUTHORITY_UNAVAILABLE. Queries, grouping, counts and filters are unchanged.
- `ThumbnailScheduler` retains its validated request and uses defensive-copy IDs/windows for all classification, pending-item capture, batch admission and response ordering. Caller mutation cannot change admitted authority or ordering. Batch/drain/IO/publication design is unchanged.

Tests: `MediaMetadataExecutorTests` adds failed-finalizer cases with terminated and nonterminated executors, checking all three finalizers are attempted, termination is checked, registry authority is unavailable and the catalog lock stays retained; it also tests successful abandoned finalization releases the lock. New `ExactDuplicateCapabilityTests` covers native usable path, native unusable path and exFAT usable path, including one classification per row. `ExfatSlice5SchedulerTests` adds caller mutation during classification, checking admitted original windows and original response order. Seven regression cases were added in total.

Focused correction validation: 97 tests, zero failures/errors/skips, exits 0. Full correction backend validation: 1516 tests across 131 suites, zero failures/errors, 15 host/environment-specific skips, exits 0. Same macOS/Java 21/offline cache and cached Mockito premain workaround; no Windows or mounted-volume validation was attempted.

Commands from `backend/`:

```sh
./mvnw -o -q '-Dtest=MediaMetadataExecutorTests,MediaMetadataJobTests,ExfatSlice5*Tests,ExactDuplicate*Tests' '-DargLine=-javaagent:/Users/chris/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar -Djava.io.tmpdir=/Users/chris/Code/Personal/Projects/media-compare/backend/target/tmp' test > target/slice5-review-focused.log 2>&1
./mvnw -o -q '-DargLine=-javaagent:/Users/chris/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar -Djava.io.tmpdir=/Users/chris/Code/Personal/Projects/media-compare/backend/target/tmp' test > target/slice5-review-backend.log 2>&1
```

Correction scope is three production files, the three test files named above and these two status/report documents. The approved HEAD is unchanged. SHA/protected-read/provider/HANDOFF/publication/batch/native-authority/schema/frontend/production-gate implementation is unchanged from the pre-correction working tree. Production remains false; no real VeraCrypt/exFAT access, commit or push occurred.

## Requested implementation and safety findings

1. **Starting baseline.** Clean approved Slices 1–4 commit `76e9ce75864648cd6e065e4ea93c2d4d582bd74c`. HEAD remains there. The actual committed implementation, rather than older planned class names, governed this change.

2. **Implementation/test host.** macOS 26.6.2, Temurin Java 21.0.10+7, repository `/Users/chris/Code/Personal/Projects/media-compare`. The request's Windows repository location was not accessed. No real Z: volume was required or probed.

3. **Files.** The inventory below lists every added/modified file. There are 12 new production classes, 33 modified production classes, eight new test/support classes, one modified existing executor test class, and seven documentation files (including this report). The executor test additions and correction scope are described above.

4. **HANDOFF descriptor.** `ExfatScanBundles.Handoff` is an immutable process-local descriptor keyed by original ScanRun. It retains runtime, original bundle, exact immutable SCAN authorities/windows/scopes, ScanRun ID, SCAN Job ID and optional associated metadata Job ID. SCAN authority objects supply provenance only; later reads use dedicated content owners. Preservation occurs before removing scan admission state. Descriptors are bounded to 64 and expired/revoked entries are pruned by a daemon; shutdown clears them.

5. **Metadata binding/replay.** Acceptance resolves the exact live HANDOFF and freshly checks separation outside the writer. Inside the transition gate and writer it checks completed SCAN/Job/source-generation state, unchanged scopes, creates a metadata Job and transfers the exact window set before commit. Submission follows commit. Same live HANDOFF replay returns that Job with `submit=false`. An unrelated active metadata Job conflicts. Expiry/release never changes completed indexing to failed. Metadata execution version stays 1 and `job.scan_run_id` stays NULL.

6. **Standalone bundles.** Explicit fresh prepared windows are consumed into a new random content bundle, independent of historical SCAN ownership. No synthetic SCAN IDs, interrupted-scan resumption, receipt-window resurrection or arbitrary prepared-window lookup occurs. No-body native metadata remains native-only.

7. **Authority/capture fields.** `ExfatContentReadAuthority` contains exact `ExfatAuthorityScope`, runtime/Source/window `WindowId`, canonical bundle UUID and exactly one metadata Job or thumbnail batch owner. Scope includes full Source/context/open-period snapshots. `ExfatContentReadCapture` adds immutable FileEntry, selected membership, ContentRecord, completed SHA AnalysisRecord and ContentHash snapshots. These include token, observation/membership revisions, exact paths/keys/routes, content length, analyzer/configuration identity and digest. Queued captures contain no native handles or thread-confined leases.

8. **Expected SHA evidence.** `ExfatContentReadCatalog` requires COMPLETED `CONTENT_HASH`/`builtin.sha256` version 1, configuration version 1, exact JSON `{}` and its defined configuration hash, algorithm `SHA-256`, lowercase 64-hex digest, exact analysis/content IDs and matching file/content/receipt lengths and receipt digest. It compares complete captured rows again at execution/publication. Metadata cache identity cannot substitute for SHA evidence when a new original read is required.

9. **Fresh Session separation.** New `SessionSourceBoundary.requireFreshSeparate` reopens/validates the selected Session manifest/storage/cache route, rejects linked/aliased roots and freshly resolves the existing Source route before checking containment. It runs at admission and each protected operation outside writer transactions. It preserves the selected Session and existing native separation behavior. Production wiring supplies the boundary; isolated fake-host tests use the established test-catalog override.

10. **Protected original opening.** The coordinator checks catalog evidence, retains the exact content owner without the publication gate, checks fresh separation, revalidates retained Source root, reserves directory capacity and asks the existing host boundary for one protected original. Windows uses its existing bounded drive-to-parent chain and one regular-file lease, checking volume/profile, exact final/enumerated spelling and native/NIO evidence. Unsupported hosts fail closed. Fresh evidence describes this operation and never supplies historical continuity.

11. **First hash.** SHA-256 reads the entire retained seekable channel with a 64 KiB buffer, checkpoints between reads, reports actual byte progress, counts every byte and rejects excess/short reads or digest mismatch. It performs no original pathname byte read and runs outside the writer and registry monitor.

12. **Borrowed ImageIO input.** `BorrowedImageInput` forwards reads/seeks to that same channel and has local ImageInputStream state. Closing it closes neither channel nor protected lease. It creates no pathname fallback or source spool. The coordinator owns all original resource closure.

13. **JPEG selection.** Public `IIORegistry`/`ImageReaderSpi` enumeration filters exact `com.sun.imageio.plugins.jpeg.JPEGImageReaderSpi` in the Java desktop module before `canDecodeInput`. The created reader must be exact `JPEGImageReader` from that module and originating SPI. No inaccessible internal imports or third-party fallback exist.

14. **PNG selection.** PNG uses the corresponding exact `PNGImageReaderSpi`/`PNGImageReader` identities and module/origin checks. Signature recognition occurs through supplied bytes. A competing SPI receives no protected input. A recognized format with its approved provider absent is infrastructure failure; rejected/truncated recognized image data is decoder failure. Other signatures produce unsupported results.

15. **EXIF.** The existing bounded `JpegExifOrientation` parser consumes the same supplied input before reader consumption. Its limits and malformed-orientation fallback remain. Preview orientation is applied through the shared bounded renderer; metadata dimensions remain encoded dimensions.

16. **PNG flush/rewind.** Concrete decoders dispose their reader before returning. The coordinator closes the borrowed wrapper and rewinds the retained channel directly, so PNG `flushBefore()` cannot prevent the second hash. It never seeks backward through the flushed wrapper.

17. **Second hash.** After decode/output preparation, another complete hash checks expected count/digest on the same channel. The coordinator then revalidates fresh original/root evidence and unchanged captured catalog rows before publication. A private-constructor, thread-confined `VerifiedRead` proof is created only inside the guarded transaction after both hashes and expires when the callback exits.

18. **ExFAT candidate routing.** A separate paged query limits admitted Source IDs inside eligible-membership selection before `MIN(eligible.id)`. Per-item capture fixes the exact chosen membership/window before IO. An unaccepted lower-ID overlapping Source cannot replace the admitted route; later supersession or route revision rejects the capture.

19. **Native candidate preservation.** Existing native methods and their `occurrence_token IS NULL` predicates remain unchanged. ExFAT methods are additive and token-specific. Native deterministic ordering, candidate verification and paging remain on their existing path.

20. **Metadata attribution lifetime.** Cache resolution happens first. SUCCESS, content-derived UNSUPPORTED and guarded malformed-image FAILED storage execute inside the protected coordinator after the second SHA. Authority/hash/native/infrastructure failure escapes to Job failure rather than producing attributed decoder evidence. A healthy unsupported format does not itself revoke authority; normal terminal bundle completion closes its ownership.

21. **Metadata recovery/shutdown.** Background tasks capture an immutable owner list after admission commits. Queue rejection fails the Job and finalizes ownership. Executor shutdown inspects never-started tasks returned by `shutdownNow`; they finalize explicitly. Actual running service exit releases mappings in `finally`, while registry users retain resources until closure. Existing restart recovery fails unfinished Jobs with an empty new registry; completed artifacts remain durable history.

22. **Sealed thumbnail batches.** One immutable exact window set owns a finite batch, with at most 100 admitted items and 64 concurrent batch mappings. Every item's evidence is captured before submitting tasks. Submission ownership remains until sealing; only sealed batches with zero remaining actual item exits drain. Zero-admitted batches drain on seal. Items close exactly once.

23. **Queue/finalization.** Existing one-worker/64-queued-task bounds remain. Partial/all rejection, capture failure, duplicate-owner conflict and never-started shutdown close their item references. Running tasks close references only in their actual `finally`, after generation's resource closure. A cancelled/done Future is never used as proof of worker exit. Uncooperative shutdown retains catalog ownership under the existing policy.

24. **Stale queued work.** A queued task uses its captured runtime/window/bundle/route/occurrence/content evidence. It cannot borrow a new window. ExFAT tasks with the same FileEntry ID but different ownership return bounded `AUTHORITY_UNAVAILABLE` rather than joining an older queued task. Native `ALREADY_QUEUED` behavior remains.

25. **Metadata caches.** `MediaMetadataCache` remains keyed by exact ContentRecord and analyzer/configuration. Compatible completed results can be read without a Source/window/protected original. The exFAT analyzer checks the cache before per-item original capture. No cross-ContentRecord reuse or new persisted live flag exists.

26. **Preview caches.** The exFAT branch checks current PreviewAsset evidence and validates the Session-owned cached PNG before native physical authority. Cache hits require no source/window and perform no host-original access. Missing/corrupt/stale cache material requires exact protected regeneration. Captured-task cache reuse still checks content/revision agreement; public immutable cache serving remains unchanged.

27. **Preview installation/repair.** Rendering produces a temporary Session PNG. PNG decode/dimensions/size checks, complete generated-output SHA/count and existing-target byte equivalence happen outside the writer before the second original hash. `PreparedPublication` captures temporary/target/parent evidence. Only after the exact original guard does the short writer perform bounded stat/hard-link no-clobber installation and catalog insertion/reuse. Existing-row missing-file repair follows the same guard. Corrupt occupied immutable targets and unvalidated racing targets fail closed. Rollback can leave a disposable final-file orphan; it creates no incorrect successful asset row. Generated-output hash is transient provenance, not persisted physical authority.

28. **Metadata guard.** Protected publisher requires the active verified-read proof, exact content owner, unchanged scope/open period/membership/file/content/SHA evidence and active version-1 metadata Job/IMAGE_METADATA stage. Native `CurrentMembershipAuthority` is unchanged. Existing storage/cache semantics are shared after the separate guard.

29. **Preview guard.** Protected publisher requires the same proof and exact catalog/runtime owner, matches generated PreviewSourceEvidence to the capture and validates row equivalence before installing. The outer coordinator holds runtime gate → SQLite writer → commit → gate release, while original protection remains live. No generated PNG decode/hash happens in the writer.

30. **Release/expiry.** Revocation immediately prevents new retention/publication, interrupts cooperative content workers and preserves outstanding resource users until actual closure. Both hashes checkpoint; stale work waiting to publish cannot enter the writer. An already established committing transaction may finish according to existing gate semantics and its artifact survives. Running bundles use the existing no-progress deadline; bytes and catalog progress renew it without a total-duration timeout. Native IO, decoder IO, SQLite waits, closure and executor draining occur outside the registry monitor. Latch tests cover release at both hashes, decode, publication waiting and paused commit; existing registry tests cover deadlines/progress/restart.

31. **No new occurrence.** Later-read classes have no occurrence/token creation or historical FileEntry/membership/receipt/presence write route. Integration tests compare original FileEntry and counts after metadata/preview reads; supersession tests reject old queued captures without reattribution. Changed bytes fail SHA and await a future independent scan/new occurrence.

32. **Cleanup denial.** After existing request/group precedence, `CleanupPreflightFileValidator` classifies strict persisted exFAT at the start of physical validation and returns blocked `AUTHORITY_UNAVAILABLE` before path/host/hash access. Keeper and candidate physical validation share it. Mixed/malformed token/profile/receipt/context evidence fails integrity checks. No destructive operation was added.

33. **Reveal denial/precedence.** A narrow catalog-only exFAT check precedes the non-macOS host gate. Valid exFAT returns existing STALE_AUTHORITY/409 AUTHORITY_UNAVAILABLE without original probing or Finder/Explorer. Native Windows remains 501 unsupported-host; positive absent IDs on non-macOS retain that existing 501 precedence. Nonpositive IDs remain 404; macOS native behavior is unchanged.

34. **Capabilities.** Library item/detail and exact-duplicate occurrence backend DTOs add `physicalActionsAvailable` and bounded reason. Strict persisted profile/receipt evidence always marks exFAT unavailable with AUTHORITY_UNAVAILABLE regardless of any live window. The shared classifier makes no host/native call. These fields are informational and never feed action authorization.

35. **Library/exact preservation.** Existing physical FileEntry/content grouping, counts, membership routes, pagination and cache keys are preserved. Joined projection columns permit strict classification without per-row host or repository IO. Existing record constructors remain available for native callers. Frontend consumers were not modified.

36. **Foreign-host safety.** Services/classification/cache paths preserve lazy Windows loading. macOS tests use fail-if-called host/native dependencies for historical projections, cache hits and physical denial. Full application test contexts start without loading kernel32. Windows acquisition occurs only through the protected host operation, never to classify persisted history.

37. **NTFS/APFS.** Native FileIdInfo/APFS authority, native membership publication guards, null occurrence evidence and original pathname pipelines remain unchanged. Shared supplied-input rendering preserves native JPEG/PNG behavior; native metadata retains JPEG/PNG/GIF/BMP/TIFF. Portable regressions pass, but this macOS execution does not prove live Windows NTFS behavior.

38. **Production gate.** `WindowsExfatSupport.PRODUCTION.available()` remains false, with its source unchanged. Added metadata admission/batch paths reject production execution even with test-acquired windows. Package-local test seams do not add a production activation setting.

39. **Slice 6/frontend.** No frontend changes, public Source Prepare/Accept/release/indexing activation, live-authority Source projection integration or frontend handoff/batch workflow. Metadata/thumbnail backend request DTOs are additive only; no-body native requests remain compatible. Metadata DTO selects exactly one indexingScanRunId or explicit bounded authority-window set.

40. **Schema.** No migration, schema/table/index/column/dependency/build-configuration change. Runtime owners, handoffs, batch IDs and associations remain memory-only. No metadata-to-scan durable link was added.

41. **Focused validation.** Initial `ExfatSlice5*Tests` run: 57 tests, zero failures/errors/skips. Exact command below. Coverage comprises real supplied JPEG/PNG/EXIF/hostile SPI fixtures, protected flow and guarded publication, strict SHA/capture rejection, overlapping routing, handoff replay/conflict, scheduler lifecycle, physical denial and fresh Session separation.

42. **Relevant regressions.** Earlier targeted run passed 852 tests with zero failures/errors and 13 host-specific skips; after implementation refinements, 322 focused integration/regression checks passed with zero failures/errors/skips. The final full-suite command below also reruns all relevant Slice 1–4, metadata, ImageIO/EXIF, preview/cache/scheduler, Library/exact, cleanup/reveal, NTFS/portable APFS and touched API regressions. Those results are included in the initial 1509-test count, not added to it. Logs for the intermediate runs are `backend/target/slice5-regression-agent.log` and `slice5-final-checks.log`.

43. **Full backend.** Initial offline backend suite exits 0: 1509 tests, zero failures/errors, 15 skips across 130 test suites. Exact command below; ignored log `backend/target/slice5-backend-complete.log`. Skips: WindowsProtectedAccess 5, WindowsNtfsExecution 3, WindowsNtfsReparse 2, WindowsNtfsNative 3, HostFileSystem 1, optional MacOsMountedVolumeContinuityAcceptance 1. Skipped Windows tests are not reported as passed.

44. **Mac-portable evidence.** Tests prove fake-host exact capture rejection, expected bytes/length, same-channel lifetime and reverse closure, writer-free hash/decode/output validation, real JDK JPEG/PNG supplied-input behavior, EXIF, PNG flush, hostile-SPI exclusion, missing-provider failure, guarded success/unsupported/malformed metadata, no attribution on SHA/authority loss, scan handoff/standalone routing, sealed batches/queue/shutdown, cache-first behavior, guarded repair/rollback/orphans, release/commit ordering, uncertain-close fail-closed handling and catalog-only restrictions. Existing suites provide portable lifecycle/restart/native-policy regression coverage. This is not evidence of real Windows handles or mounted exFAT behavior.

45. **Windows validation pending.** Actual JNA/kernel32 protected open/read/seek/EOF/close; sharing flags; close/read serialization; exact drive-path case/spelling; final-path reconciliation; reparse/junction rejection; native/NIO agreement; complete live NTFS regressions; supplied JPEG/PNG on deployed Windows Java 21 (including JPEG native decoder); Windows Session no-clobber preview publication. None was executed on this Mac.

46. **Mounted acceptance pending.** No real VeraCrypt/exFAT IO occurred. Later acceptance must cover retained-chain/share protection, production-channel JPEG/PNG, Prepare → SCAN → metadata, finite previews, release, normal dismount/remount, stale old-window rejection and actual handle closure. An absent mounted volume was not an implementation blocker.

47. **Documentation.** README and ARCHITECTURE/DATA_MODEL/DECISIONS/STATUS/IMPLEMENTATION_PLAN now describe Slice 5 and remaining public/Windows gates. This report records implementation boundaries and validation. Historical Slice 3/4/retained-handle reports remain untouched.

48. **Limits.** Production is disabled; only JPEG/PNG originals are allowed. No GIF/BMP/TIFF/HEIC/HEIF/video/ffprobe exFAT decoding, Explorer or destructive cleanup. No whole-Source preview generation, resumable old bundles, native-handle transfer between threads or runtime persistence. Occupied corrupt previews fail closed; rollback may leave disposable cache files. Uncertain original/partial-acquisition closure revokes authority and retains catalog ownership until process exit; it does not undo commits. Existing mock-based tests initially failed because JVM dynamic attachment is unavailable in the sandbox; rerunning with the already-cached Mockito premain agent resolved the harness errors without dependencies/software/build changes. No new platform qualification is inferred from passing fake-host tests.

49. **Diff check.** Final `git diff --check` passed. Added files were independently checked for UTF-8, BOM and trailing whitespace because untracked files are not included by that command.

50. **Working tree/scope.** Final `git status --short --untracked-files=all` has 40 modified tracked files and 21 untracked added files, all unstaged; the inventory below is that complete set. Protected paths (frontend, migrations/resources, build configuration, CurrentMembershipAuthority, WindowsNtfsNative and WindowsExfatSupport) are unchanged. Diff review confirms dedicated exFAT branches rather than weakened native guards, no pathname byte fallback, no new occurrences from later reads and no persisted runtime authority.

51. **No commit/push.** No commit or push was made. HEAD remains the starting baseline. External review and the pending Windows validation are the next steps; public Slice 6 work requires separate authorization.

## Initial Slice 5 validation (before external-review corrections)

Commands run from `backend/`; the Mockito agent is an already-cached jar, and JVM temporary files stay under ignored workspace-local `target/tmp`.

Initial focused Slice 5 run (57 tests):

```sh
./mvnw -o -q '-Dtest=ExfatSlice5*Tests' '-DargLine=-javaagent:/Users/chris/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar -Djava.io.tmpdir=/Users/chris/Code/Personal/Projects/media-compare/backend/target/tmp' test > target/slice5-focused-final.log 2>&1
```

Initial relevant regressions and full backend run (1509 tests, including the focused 57):

```sh
./mvnw -o -q '-DargLine=-javaagent:/Users/chris/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar -Djava.io.tmpdir=/Users/chris/Code/Personal/Projects/media-compare/backend/target/tmp' test > target/slice5-backend-complete.log 2>&1
```

These host-specific validation arguments are report evidence, not application filesystem paths or a cross-platform executable-discovery choice. No FFmpeg/ffprobe discovery decision was made.

Repository checks:

```sh
git diff --check
git status --short --untracked-files=all
git rev-parse HEAD
```

## Complete changed-file inventory

Paths are relative to the repository; `M` means modified tracked file, `A` means new untracked file.

### Modified files

- `M README.md`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/ImageIoImageMetadataExtractor.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/ImageMetadataExtractor.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/MediaMetadataBackgroundService.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/MediaMetadataCandidateRepository.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/MediaMetadataExecutor.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/MediaMetadataInterruptionRecovery.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/MediaMetadataJobAcceptance.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/MediaMetadataJobService.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/analysis/MediaMetadataPublisher.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/filesystem/ExfatAuthorityWindowRegistry.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/filesystem/HostFileSystem.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/filesystem/WindowsExfatHostFileSystem.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/filesystem/WindowsHostFileSystem.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/library/MediaLibraryItem.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/library/MediaLibraryRepository.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/matching/CleanupPreflightFileValidator.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/matching/ExactDuplicateOccurrence.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/matching/ExactDuplicateOccurrenceRow.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/matching/ExactDuplicateRepository.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/matching/ExactDuplicateService.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/matching/RevealCatalog.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/matching/RevealFileService.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/preview/PreviewCacheWriter.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/preview/SmallThumbnailRenderer.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/preview/SmallThumbnailService.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/preview/ThumbnailPublisher.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/preview/ThumbnailScheduler.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/scan/ExfatScanBundles.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/session/SessionSourceBoundary.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/web/ExactDuplicateOccurrenceResponse.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/web/MediaLibraryController.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/web/MediaMetadataRunController.java`
- `M backend/src/main/java/io/github/topher6835/mediacompare/web/ThumbnailScheduleRequest.java`
- `M backend/src/test/java/io/github/topher6835/mediacompare/analysis/MediaMetadataExecutorTests.java`
- `M docs/ARCHITECTURE.md`
- `M docs/DATA_MODEL.md`
- `M docs/DECISIONS.md`
- `M docs/STATUS.md`
- `M docs/WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md`

### Added files

- `A backend/src/main/java/io/github/topher6835/mediacompare/catalog/PersistedPhysicalActions.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/contentread/BorrowedImageInput.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/contentread/ExfatContentReadAuthority.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/contentread/ExfatContentReadBundles.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/contentread/ExfatContentReadCapture.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/contentread/ExfatContentReadCatalog.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/contentread/ExfatImageMetadataAnalyzer.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/contentread/ExfatProtectedOriginalAccess.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/contentread/JdkOriginalImageReaders.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/preview/ExfatThumbnailService.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/web/CreateMediaMetadataRunRequest.java`
- `A backend/src/main/java/io/github/topher6835/mediacompare/web/SourceAuthorityWindowRequest.java`
- `A backend/src/test/java/io/github/topher6835/mediacompare/analysis/ExfatSlice5ExecutorFixture.java`
- `A backend/src/test/java/io/github/topher6835/mediacompare/contentread/ExfatSlice5Configuration.java`
- `A backend/src/test/java/io/github/topher6835/mediacompare/contentread/ExfatSlice5SeparationTests.java`
- `A backend/src/test/java/io/github/topher6835/mediacompare/matching/ExactDuplicateCapabilityTests.java`
- `A backend/src/test/java/io/github/topher6835/mediacompare/matching/ExfatSlice5PhysicalActionsTests.java`
- `A backend/src/test/java/io/github/topher6835/mediacompare/preview/ExfatSlice5ImageIoTests.java`
- `A backend/src/test/java/io/github/topher6835/mediacompare/preview/ExfatSlice5SchedulerTests.java`
- `A backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice5FlowTests.java`
- `A docs/WINDOWS_EXFAT_SLICE5_REPORT.md`

