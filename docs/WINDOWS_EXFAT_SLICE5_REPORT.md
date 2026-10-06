# Windows exFAT Slice 5 report

Initial implementation date: 2026-10-04. Slice 5 is externally reviewed and committed at `fa4a91d51418d40209aae2c29301e2cb7a4ab858`; its approved Windows preview correction is committed at `a5c7b2adb702961c0ea778f2a036baf5d66afe0f`. Production exFAT remains disabled. Earlier sections retain historical implementation/review evidence.

## Real mounted Slice 5 acceptance - 2026-10-05

Started on clean `main` at required HEAD `a5c7b2adb702961c0ea778f2a036baf5d66afe0f`; initial `git status --short` produced no output. Windows/Temurin Java 21.0.12.1, installed offline Maven 3.9.15 and the existing user dependency cache were used. No software/dependency/build configuration, schema, frontend or Slice 6 changes were made. No commit/push is authorized or performed.

The production `WindowsVolumeProbe` verified accessible `Z:\`, native and NIO `exFAT`, drive type 3, volume root and sole mount path `Z:\`, `ordinaryExfatRoute=true`, `redirectedAlias=false`, DOS mapping `\Device\VeraCryptVolumeZ`, serial `98d16f05`, GUID `\\?\Volume{5b22bd93-5d21-11ec-bf63-7085c24c9111}\`. CIM was denied by the environment; the production native/NIO probe succeeded. Serial/GUID/mapping/index/timestamps are supporting or contradiction evidence, never durable physical identity.

Every attempt refused an existing `Z:\media-compare-slice5-acceptance` root. All original fixtures and mutation controls were generated beneath that newly owned exact root. No unrelated `Z:` user file was enumerated, read, hashed or changed. Disposable SQLite catalog/Session cache storage stayed on NTFS under workspace-local JUnit temporary storage.

| Original fixture relative to the acceptance root | Bytes | Full SHA-256 |
| --- | ---: | --- |
| `fixture.png` | 2954 | `f2a380ea381119cdf1fbb35d58dd4242cef9fdeb33bb45651387e89eb4e3f7f0` |
| `fixture.jpeg` | 839 | `705755fc87db1dd6a3a5560f1caa07192fd4d2bec458d5dcd673aab695b16de7` |
| `nested\child.png` | 2954 | `f2a380ea381119cdf1fbb35d58dd4242cef9fdeb33bb45651387e89eb4e3f7f0` |
| `unsupported.txt` | 32 | `e3da22043d02174772cdc51b42169b0d156df0c4e4081e8216ba0acbdc1f23b2` |

PNG and JPEG have deterministic 43 x 27 encoded pixels. JPEG has a generated EXIF Orientation=6 tag. Mutation-only controls (`replacement.bin`, renamed/moved paths and an empty directory) were created/restored/removed within the same root.

| Mounted automated acceptance | Observed result |
| --- | --- |
| Native protected acquisition | Production JNA calls, READ-only file sharing; directory READ+WRITE sharing without DELETE, no-follow/backup and noninheritability checks. All four fixtures opened successfully. |
| Retained parent/root chain | Actual drive-to-parent chain retained and revalidated; directory count equals parent depth + 1. Production checks reconciled direct requested/no-follow/native final paths, native/NIO regular/directory classification, file size/exact mtime and current volume facts. No links/reparse traversal was introduced. |
| Native reads/seeks/EOF | Full byte equality; seeks to zero/interior/EOF/5,000,000,000; EOF returns -1; rewind/full SHA; closed channels reject reads. Successful native closes reached zero between stages. |
| File overwrite/truncate/delete/rename/move | Independently opened Win32 conflicting operations denied, each error **32**. Corresponding controls succeeded after protected closure; bytes/routes restored. |
| Replacement | `MoveFileEx(REPLACE_EXISTING)` denied with error **5**; post-close control succeeded. |
| Retained empty-directory delete/rename/move | Denied with error **32**; post-close controls succeeded. Empty-directory deletion isolates sharing from nonempty-directory restrictions. |
| Acceptance-root DELETE access/intermediate-parent rename | Independently opened DELETE access to the retained acceptance root and rename of the retained nested parent both denied with error **32**. Post-close root DELETE-access open/close and parent rename/restore controls succeeded; the root was never renamed outside its boundary. |
| Existing writer/writable mapping | Protected acquisition denied with error **32** for an existing writer, writer + writable mapping, and retained writable mapping after writer closure. Unmap/mapping close succeeded; fresh protected acquisition succeeded. |
| Real scan preparation | Explicit test construction installs actual retained chains and real configuration facts; existing native discovery/observation/receipt/content-assignment/hash services publish four real occurrences/content records. No public Prepare/Accept/indexing activation. |
| Supplied-channel JPEG/PNG metadata | Existing approved JDK reader selection and metadata extractor report encoded **43 x 27** for both formats and the nested PNG. Nonimage takes the existing unsupported path. |
| Thumbnails/EXIF | PNG thumbnail **43 x 27**; JPEG thumbnail **27 x 43**, exercising Orientation=6. Successful catalog rows and derived PNGs on NTFS. |
| First/second hashes and no reopen | Channel-only forwarding observer records complete SHA digests at EOF for first and second hashes, equal to canonical content evidence. Production also validates each byte count against expected length. Exactly one native file acquisition per content read; decoder/renderer pathname overloads throw if called. Same supplied protected channel survives reader/wrapper disposal and rewinds for the second hash. |
| Protected publication lifetime | Observed manager records publication, successful delegate commit, then original close; original channel is open at publication/commit. Existing exact guarded authority and SQLite writer are used. Rollback has close without commit. |
| Cache reuse/repair | Subsequent cache hit without a live exFAT window increments no original native open count. Missing derived file repaired under fresh valid content authority, preserving the preview catalog row. |
| Rollback/no-clobber | Injected failure after guarded file/row install rolls back the row, leaving a disposable derived orphan. A corrupt occupied immutable target remains byte-identical and unregistered when regeneration fails. |
| Finite ownership | Sealed batches drain after success and failures. Explicit release during a paused in-flight decode returns DRAINING while handles remain; after actual item exit, release returns RELEASED with zero native handles. |
| Released/stale capture | Released old capture is rejected before any native file open while a newly installed window exists. Cached preview remains available. Physical reveal returns STALE_AUTHORITY and cleanup preflight AUTHORITY_UNAVAILABLE for persisted exFAT entries. |
| Durable state | Full Source/context/open-binding-period rows compare unchanged across mounted metadata/preview, routine reacquisition and release. No routine revision/binding churn. |
| Cleanup | Application shutdown and explicit release close counted native resources to zero. Exact-root/known-occupant checks precede individually named deletes. Root absence independently confirmed with PowerShell. |

The automated mounted run and final rerun each passed **1 test, zero failures/errors/skips** (`backend/target/slice5-mounted-automated.log` and `slice5-mounted-final.log`). The final rerun additionally checks retained-root DELETE access, intermediate-parent rename, explicit unsupported metadata outcome, and logs hash byte counts/rewinds. The count represents one sequential acceptance scenario with the assertions above, not one test per acceptance row. Initial compile and diagnostic setup failures are retained in `slice5-mounted-initial.log`, `slice5-mounted-retry.log` and `slice5-mounted-mapping-retry.log`. The mapping harness originally used GENERIC_WRITE alone; PAGE_READWRITE required GENERIC_READ+GENERIC_WRITE. The release barrier originally honored the registry's cancellation interrupt, allowing legitimate immediate RELEASED rather than the intended held-in-flight DRAINING observation; it now waits for explicit barrier exit while preserving interruption. These were harness corrections, not production defects. Every attempt that created a fixture closed its handles and removed the exact root safely.

### Completed normal manual VeraCrypt dismount/remount gate

The previously pending manual gate has now passed. The user supplied the observed VeraCrypt actions and post-remount test results below. Manual UI outcomes are recorded as user observations, not native dismount traces; no event timestamps are inferred. Together with the preserved automated evidence above, **the approved Windows/VeraCrypt/exFAT Slice 5 mounted acceptance is complete for the defined V1 scope**. Slice 6 and production activation remain incomplete.

| Manual gate / post-remount check | Observed result |
| --- | --- |
| Normal dismount while HELD | While the acceptance test reported `HELD`, normal VeraCrypt dismount of `Z:` was attempted. VeraCrypt refused because files/folders were in use and offered a force dismount. |
| Force prompt | Force dismount was explicitly declined. No forced removal/eject occurred. |
| Authority release / normal dismount | The test was signaled to release authority. After it reported `RELEASED`, normal VeraCrypt dismount succeeded. |
| Remount / continuation | The same VeraCrypt volume was remounted as `Z:`; the test was then signaled with `remounted.txt`. |
| Production preflight after remount | Profile `EXFAT`; NIO type `exFAT`; serial `98d16f05`; GUID `\\?\Volume{5b22bd93-5d21-11ec-bf63-7085c24c9111}\`; DOS mapping `\Device\VeraCryptVolumeZ`; mount path `Z:\`; `ordinaryExfatRoute=true`. |
| Old authority | Old pre-remount captures were rejected. Matching volume/path/hash evidence did not restore old-window authority. |
| Fresh protected JPEG | Explicit fresh post-remount protected JPEG acquisition succeeded. Both expected full hashes were `705755fc87db1dd6a3a5560f1caa07192fd4d2bec458d5dcd673aab695b16de7`. |
| Publication lifetime | Ordering remained `publication → commit → close`. |
| Closure / cleanup | Native handles returned to zero. `Z:\media-compare-slice5-acceptance` was removed successfully. |
| Final manual mounted test | **1 test, zero failures/errors/skips; Maven BUILD SUCCESS.** |
| Production policy | Production exFAT remained disabled throughout. |

Serial/GUID/DOS mapping/mount path/index and content equality remain supporting or contradiction evidence only; no durable physical identity is claimed. No forced/surprise-removal behavior was qualified. macOS was not rerun and privilege-dependent Windows symlink checks remain skipped; these are residual platform/privilege limitations, not failures of this completed mounted acceptance. This documentation step changes only STATUS/this report, preserving the earlier acceptance code/tests and automated evidence.

The only production edit is a package-local injected-host constructor in `WindowsExfatNativeDiscoveryAccess`; the default constructor still creates the disabled production host. Three new test files provide the opt-in Windows mounted fixture, explicitly constructed scan services and acceptance scenario. `WindowsExfatSupport.PRODUCTION.available()` remained false throughout. Runtime windows remain process-local. No broader decoder, video/ffprobe, historical physical authority or public activation was added.

Commands run from `backend/`:

```powershell
mvn -o '-Dmaven.repo.local=C:/Users/sears/.m2/repository' '-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp' '-DargLine=-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp' '-Dmedia-compare.slice5.mounted=true' '-Dtest=MountedExfatSlice5AcceptanceTests' test *> target/slice5-mounted-automated.log
mvn -o '-Dmaven.repo.local=C:/Users/sears/.m2/repository' '-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp' '-DargLine=-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp' '-Dtest=ExfatSlice5FlowTests,PreparedPreviewPublicationTests,ExfatSlice5ImageIoTests,ExfatSlice5SeparationTests,ExfatSlice5SchedulerTests,ExfatAuthorityWindowRegistryTests,WindowsProtectedAccessTests,WindowsNtfsNativeTests,WindowsNtfsExecutionTests,WindowsNtfsReparseTests,WindowsNtfsScanAuthorityTests' test *> target/slice5-mounted-focused.log
```

The mounted test skips without `media-compare.slice5.mounted=true`. Its manual checkpoint requires `media-compare.slice5.manual-remount=true` and a new NTFS `media-compare.slice5.control` directory. It signals HELD, accepts an operator release signal, confirms resource closure, signals RELEASED and waits for a remounted signal before stale/fresh checks. Those signals coordinate manual actions; they are not native dismount traces or a proof that a dismount happened. The completed VeraCrypt actions and successful post-remount test are recorded above.

Focused regression validation passed **101 tests, zero failures/errors, four skips**; shell/Maven exit 0 and BUILD SUCCESS. XML totals for all eleven requested classes were independently collected into ignored `backend/target/slice5-mounted-focused-totals.json` before the full run could overwrite reports:

| Focused suite | Tests | Skips |
| --- | ---: | ---: |
| `ExfatSlice5FlowTests` | 38 | 0 |
| `PreparedPreviewPublicationTests` | 14 | 0 |
| `ExfatSlice5ImageIoTests` | 9 | 0 |
| `ExfatSlice5SeparationTests` | 2 | 2 |
| `ExfatSlice5SchedulerTests` | 6 | 0 |
| `ExfatAuthorityWindowRegistryTests` | 13 | 0 |
| `WindowsProtectedAccessTests` | 5 | 0 |
| `WindowsNtfsNativeTests` | 3 | 0 |
| `WindowsNtfsExecutionTests` | 3 | 0 |
| `WindowsNtfsReparseTests` | 4 | 2 |
| `WindowsNtfsScanAuthorityTests` | 4 | 0 |

The separation skips are macOS-only; two symlink tests skip for missing Windows creation privilege. Both junction tests and all live Windows native/NTFS/protected-access cases pass. Existing duplicate-temp-property, native-access and Mockito warnings were not suppressed through dependency/build changes.

Full backend validation passed **1533 tests, zero failures/errors, 290 skips across 133 suites**; shell/Maven exit 0 and BUILD SUCCESS, elapsed 17:48. Independent XML aggregation restricted to the suites named in this run's log matches every suite's counts and the final total; stale reports are excluded. Results are in ignored `backend/target/slice5-mounted-backend.log`, `slice5-mounted-backend-totals.json` and `slice5-mounted-backend-suites.json`. The new mounted class contributes one opt-in skip in the normal full run; its real mounted invocation passed separately. Other skips are existing host/platform/privilege cases, including macOS/APFS-dependent suites; no live macOS acceptance is claimed.

```powershell
mvn -o '-Dmaven.repo.local=C:/Users/sears/.m2/repository' '-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp' '-DargLine=-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp' test *> target/slice5-mounted-backend.log
```

Final native/NIO preflight repeats the original expected volume/route evidence and confirms the acceptance root is absent. Final `git diff --check` passes. All six changed/new repository files pass explicit UTF-8/BOM/trailing-whitespace checks. Final branch is `main`; HEAD remains `a5c7b2adb702961c0ea778f2a036baf5d66afe0f`. Final status is three modified tracked files and three new untracked test files, all unstaged:

```text
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/WindowsExfatNativeDiscoveryAccess.java
 M docs/STATUS.md
 M docs/WINDOWS_EXFAT_SLICE5_REPORT.md
?? backend/src/test/java/io/github/topher6835/mediacompare/contentread/MountedExfatSlice5AcceptanceTests.java
?? backend/src/test/java/io/github/topher6835/mediacompare/filesystem/MountedSlice5Fixture.java
?? backend/src/test/java/io/github/topher6835/mediacompare/scan/MountedSlice5ScanConfiguration.java
```

`WindowsExfatSupport`, frontend, migrations and Maven configuration have no diff. Production exFAT remains disabled; no commit or push occurred. Recommended next step is external review of these uncommitted acceptance additions/results, followed by separately authorized Slice 6 integration and production acceptance. The approved Windows/VeraCrypt/exFAT Slice 5 mounted acceptance is complete for the defined V1 scope; durable physical identity, forced/surprise-removal behavior, universal exFAT/provider behavior and full production activation are not claimed.

## Windows validation portability correction - 2026-10-05

Started on clean `main` at `fa4a91d51418d40209aae2c29301e2cb7a4ab858`, Windows/Temurin Java 21.0.12.1 and installed offline Maven 3.9.15. Reproduced the reported class result: 38 tests, two failures, one error, zero skips. Bounded temporary diagnostics and an isolated disposable-file probe both found `expectedKeyNull=true`, `actualKeyNull=true`, `keyEqual=true`, `sizeEqual=true`, `modifiedEqual=true`, `regular=true`, `symlink=false`. Only the requirement for a non-null NIO key failed; no size, timestamp-precision, equality-instability or classification mismatch was observed. Diagnostic code was removed; no production metadata logging was added.

Both repair and rollback tests stopped at `preparePublication()` before installation/SQLite publication. The preview success test's unconditional row assertion in original close then masked the same preparation exception: there was no row to observe. Inspection confirms the production transaction template completes commit inside the exact content-read gate before original close; no Windows SQLite visibility or late-commit defect was found.

Only production `PreviewCacheWriter` changes. Its prepared-file snapshot uses the existing bounded `WindowsNtfsNative.observe()` no-follow handle observation when Windows NIO supplies no key. Unsupported/unverifiable identity and reparse entries fail closed. The identity is retained only in memory for a single prepared publication, applies to derived Session cache files/directories on supported storage, and grants no original authority or durable physical identity. macOS's non-null key comparison and native `publish()` path remain unchanged. The temporary snapshot now brackets PNG validation/hash, and occupied-target snapshots bracket validation/equivalence. Size and exact modification-time checks remain; same-size mutation tests set a deterministic distinct modification time, while replacement and size tests restore the original time to isolate their checks. These are the existing bounded mutation checks, not a new claim of protection against hostile in-place writes that restore every attribute.

Installation still checks staged identity/size/time, parent identity and any previously validated equivalent occupied target. Unvalidated racing targets fail even when equivalent, and `Files.createLink` still provides no-clobber installation. No PNG decoding, digest or byte-equivalence scan enters the writer. Rollback still permits only a disposable derived orphan without a catalog row; no protected-original pathname reopen, hash sequence, production activation, schema, frontend or public Prepare/Accept change.

Tests change `ExfatSlice5FlowTests` and test-only `ExfatSlice5Configuration`, and add `PreparedPreviewPublicationTests`. A test-only transaction-manager observer checks the row on the active writer connection, then records successful delegate commit while the original is open, then records original close; the required event order is publication/commit/close. The existing paused-commit/release test independently proves retention through a release request. The rollback test now checks the exact injected post-install rollback message. Fourteen new cache cases cover host keys, unchanged output/digest, transaction placement, size/same-size mutation, same-byte replacement, classification, valid occupied reuse, changed/deleted occupied output, equivalent/invalid racing creation, corrupt occupied output and parent replacement with the same staged file.

Validation: corrected `ExfatSlice5FlowTests` passes 38 tests with zero failures/errors/skips. Focused regressions pass 166 tests with zero failures/errors and 59 skips. All fourteen new cache tests pass. WindowsProtectedAccess (5), WindowsNtfsNative (3), WindowsNtfsExecution (3), WindowsNtfsScanAuthority (4) and both junction checks in WindowsNtfsReparse pass; the two symlink checks skip because this environment cannot create symlinks. Other focused skips: two macOS-only separation cases, 54 macOS-guarded thumbnail-service cases and one cache symlink case. Full backend reports BUILD SUCCESS: 1532 tests, zero failures/errors, 289 skips across 132 suites. Slice 5's 38 flow tests and all fourteen new cache tests pass again within that full run.

The focused retry and full run's PowerShell tool status was 1 because redirected Mockito self-attachment stderr warnings were represented as `NativeCommandError`; Maven's final summaries are BUILD SUCCESS, with no failed tests. Independent XML aggregation restricted to the 132 suites named in the full-run log confirms exactly 1532/0/0/289; four stale reports from earlier test classes are excluded. No build/agent configuration change was made just to suppress these warnings. Skips and disposable NTFS checks do not qualify complete Windows Slice 5 or mounted exFAT production acceptance.

Logs are ignored under `backend/target/`. Plain requested Maven initially could not create default `C:/.m2/repository`; using the existing user cache avoided this. The first explicit-cache run encountered sandbox access denial resolving the default temporary directory. The first regression invocation failed during test compilation naming a cached Spring Boot jar; retrying with workspace-local temporary-file configuration also supplied to Maven passed. Successful validation commands use workspace-local JVM temporary files, without changing dependencies/build configuration or installing software:

```powershell
mvn -o "-Dmaven.repo.local=C:/Users/sears/.m2/repository" "-DargLine=-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp" "-Dtest=ExfatSlice5FlowTests" test > target/windows-preview-flow.log 2>&1
mvn -o -e "-Dmaven.repo.local=C:/Users/sears/.m2/repository" "-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp" "-DargLine=-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp" "-Dtest=PreparedPreviewPublicationTests,SmallThumbnailServiceTests,SmallThumbnailRendererTests,PreviewCacheLayoutTests,ExfatSlice5ImageIoTests,ExfatSlice5SeparationTests,ExfatSlice5SchedulerTests,ExfatAuthorityWindowRegistryTests,WindowsProtectedAccessTests,WindowsNtfsNativeTests,WindowsNtfsExecutionTests,WindowsNtfsReparseTests,WindowsNtfsScanAuthorityTests" test > target/windows-preview-regressions-retry.log 2>&1
mvn -o "-Dmaven.repo.local=C:/Users/sears/.m2/repository" "-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp" "-DargLine=-Djava.io.tmpdir=D:/Dev/projects/ai-projects/media-compare/backend/target/tmp" test > target/windows-preview-backend.log 2>&1
```

Final scope at this correction milestone: one production file (`PreviewCacheWriter`), two modified test files (`ExfatSlice5Configuration`, `ExfatSlice5FlowTests`), one added test file (`PreparedPreviewPublicationTests`), and STATUS/this report. `git diff --check` passes; all six files are unstaged. Branch remains `main`, HEAD remains `fa4a91d51418d40209aae2c29301e2cb7a4ab858`. Production support remains false and its source is unchanged. No schema, frontend, Slice 6, public Prepare/Accept, dependency/build change, real VeraCrypt/exFAT operation, commit or push occurred at that milestone. External review and remaining host-specific/mounted acceptance were the next steps then; the correction is now committed and the defined Slice 5 mounted acceptance has completed as recorded above.

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

45. **Windows validation at initial implementation.** Actual JNA/kernel32 protected open/read/seek/EOF/close; sharing flags; close/read serialization; exact drive-path case/spelling; final-path reconciliation; reparse/junction rejection; native/NIO agreement; complete live NTFS regressions; supplied JPEG/PNG on deployed Windows Java 21 (including JPEG native decoder); Windows Session no-clobber preview publication were pending at this Mac implementation milestone. Later Windows results and residual privilege limitations are recorded above.

46. **Mounted acceptance at initial implementation.** No real VeraCrypt/exFAT IO occurred at this milestone; retained-chain/share protection, production-channel JPEG/PNG, Prepare → SCAN → metadata, finite previews, release, normal dismount/remount, stale old-window rejection and actual handle closure remained later qualification work. The approved Slice 5 mounted acceptance has since completed for the defined V1 scope as recorded above. An absent mounted volume was not an implementation blocker.

47. **Documentation.** README and ARCHITECTURE/DATA_MODEL/DECISIONS/STATUS/IMPLEMENTATION_PLAN now describe Slice 5 and remaining public/Windows gates. This report records implementation boundaries and validation. Historical Slice 3/4/retained-handle reports remain untouched.

48. **Limits.** Production is disabled; only JPEG/PNG originals are allowed. No GIF/BMP/TIFF/HEIC/HEIF/video/ffprobe exFAT decoding, Explorer or destructive cleanup. No whole-Source preview generation, resumable old bundles, native-handle transfer between threads or runtime persistence. Occupied corrupt previews fail closed; rollback may leave disposable cache files. Uncertain original/partial-acquisition closure revokes authority and retains catalog ownership until process exit; it does not undo commits. Existing mock-based tests initially failed because JVM dynamic attachment is unavailable in the sandbox; rerunning with the already-cached Mockito premain agent resolved the harness errors without dependencies/software/build changes. No new platform qualification is inferred from passing fake-host tests.

49. **Diff check.** Final `git diff --check` passed. Added files were independently checked for UTF-8, BOM and trailing whitespace because untracked files are not included by that command.

50. **Working tree/scope.** Final `git status --short --untracked-files=all` has 40 modified tracked files and 21 untracked added files, all unstaged; the inventory below is that complete set. Protected paths (frontend, migrations/resources, build configuration, CurrentMembershipAuthority, WindowsNtfsNative and WindowsExfatSupport) are unchanged. Diff review confirms dedicated exFAT branches rather than weakened native guards, no pathname byte fallback, no new occurrences from later reads and no persisted runtime authority.

51. **No commit/push at initial implementation.** No commit or push was made at this milestone; HEAD remained its starting baseline. External review and Windows validation were the next steps then. Subsequent review/commits and completed Slice 5 mounted acceptance are recorded above; public Slice 6 work still requires separate authorization.

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

