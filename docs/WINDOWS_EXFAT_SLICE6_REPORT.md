# Windows exFAT Slice 6 — pre-activation integration report

2026-10-07, macOS / Temurin Java 21.0.10 / Node 24.17.0. Implemented and validated; stopped for external diff review. No staging, commit or push.

## External-review corrections — 2026-10-07

This narrow frontend correction started from the reviewed uncommitted Slice 6 tree on `main` at `d547715d4f3cd44147b23e601e7012130542c3c7`. Read-only status/HEAD/diff checks matched; the initial diff check passed. The implementation/validation and repository snapshot in sections 1–7 below record the earlier pre-activation milestone; this section records the subsequent correction pass and supersedes its frontend test total.

- Preview authority no longer derives from either physical-action capability field. The hook uses only the existing terminal `authority-unavailable` scheduling phase to trigger explicit authority recovery. Its small `scheduleExplicitThumbnailRetry` function fetches the current Source, determines EXFAT solely from `filesystemProfile`, requires READY/live=true/exact window via the existing Source helper, and submits that exact Source/window for the finite request. Ordinary native retry retains its existing path; a native Source encountered during explicit recovery also submits no windows and retains queue backoff. EXFAT queue rejection stays user-action-needed. No automatic Prepare or already-admitted task/window mutation is introduced.
- Item Detail omits the preview-specific Prepare paragraph because it has no independent filesystem/preview-authority projection. Physical-action/reveal messaging remains unchanged.
- Duplicate planning eligibility now requires `physicalActionsAvailable === true`; absent/undefined capability is denied. Historical exFAT reveal/cleanup denial and explanatory text remain intact; no destructive action is enabled.

Exactly six existing working-tree files changed in this correction:

```text
frontend/src/library/useVisibleThumbnails.ts
frontend/src/library/MediaLibraryItemDetailPage.tsx
frontend/src/duplicates/DuplicatePhysicalCopies.tsx
frontend/tests/exfatAuthority.test.mjs
frontend/tests/exfatRendering.test.mjs
docs/WINDOWS_EXFAT_SLICE6_REPORT.md
```

Seven added tests exercise the actual explicit-retry function with Source/scheduling API responses and React rendering: physical reason alone does not grant exFAT preview authority; each explicit exFAT request freshly captures the current Source/window without changing an earlier admission; missing live authority or UUID does not schedule/Prepare; native APFS/NTFS requests omit windows; exFAT finite queue rejection remains terminal while native queue backoff survives; detail wording uses no inferred profile; missing/undefined duplicate capability fails closed and explicit true retains native keeper/reveal controls. Existing cached-preview/no-authority and historical exFAT denial tests remain unchanged and pass.

Actual validation, in order (frontend commands from `frontend/`, Git checks from root):

| Command | Result |
| --- | --- |
| `node --test tests/exfatAuthority.test.mjs tests/exfatRendering.test.mjs tests/mediaLibraryState.test.mjs` | 35 tests, 35 passes, 0 failures/cancellations/skips, exit 0 |
| `node --test tests/*.test.mjs` | 75 tests, 75 passes, 0 failures/cancellations/skips, exit 0 |
| `npm run lint` | 0 lint errors, exit 0 |
| `npm run build` | TypeScript + Vite success, exit 0 |
| `git diff --check` | Passed, exit 0 |
| `git status --short --untracked-files=all`; `git diff --stat`; `git rev-parse HEAD` | Exit 0; uncommitted Slice 6 tree retained, HEAD unchanged |

The existing optional Vite HMR WebSocket emits the same sandbox EPERM warning recorded at the initial milestone; all rendering tests pass. Hash comparison against a snapshot taken before this correction proves every backend file and every unrelated existing file is unchanged. No backend tests were rerun for this frontend-only pass. No API, schema, dependency, durable state or production-authority code changed. New/untracked corrected files were also checked for UTF-8/BOM/trailing whitespace.

`WindowsExfatSupport.PRODUCTION.available()` remains **false**; its source and availability line are untouched. Stash state is untouched. Nothing was staged, committed or pushed. Stop for another external diff review. Sequencing remains pre-activation implementation → review/corrections → separately authorized production flip → required real Windows/VeraCrypt/exFAT production acceptance → only then exFAT V1 complete. No activation or Windows/mounted production acceptance is claimed.

## 1. Reconciliation

Starting and ending HEAD: `d547715d4f3cd44147b23e601e7012130542c3c7`, branch `main`. Initial `git status --short --untracked-files=all` was empty. This exactly matches the approved baseline. `git stash list` was empty on this Mac checkout; no stash command modified anything and the described old Windows stash was untouched.

Read-only inspection covered AGENTS, README, STATUS, ARCHITECTURE, DATA_MODEL, DECISIONS, implementation plan, retained-handle qualification and relevant Slice 3–5 reports/classes/tests before edits. No material architecture contradiction was found. Existing Source preparation already routed bound exFAT before the native READY return; registry projection/release and Slice 5 handoff/standalone/finite preview ownership already existed. Shared `SourceAuthorityWindowRequest` uses `sourceId/windowId`; metadata ownership is mutually exclusive scan ID or windows. Public Source projection/release and indexing window input were absent. Current indexing admission implicitly captured a current window; this is now explicit. Frontend has Node tests, lint/build scripts and existing React rendering tools, with no npm test script. No old DTO manifest was substituted.

## 2. Implementation

- **Source projection / Prepare / release:** new `catalog/SourceAuthorityProjection` derives profile from persisted binding evidence/history and checks exact durable scope against the registry. Native profiles have null transient fields. GET/list/register/Prepare/release projections send no-store. New release DTOs target the exact supplied UUID and expose updated Source, RELEASED/DRAINING and other-volume windows. Existing first-bind/commit, no-change reacquisition and valid-window revalidation/UUID retention are reused. An unavailable retained root now revokes/fails before replacement; a separate explicit Prepare is required. Registry's new boolean retained-window presence check is lifecycle-only and supplies no authority or UUID. Structured-unbound remains REBIND_REQUIRED. Release preserves existing FAILED/recovery and protected drain ordering.
- **Indexing admission:** shared explicit windows flow through real controller, request, service, acceptance and bundle admission. Selected exFAT entries must match exactly; duplicates, missing, mismatched, extra or stale windows fail before execution. Captures are immutable before association/commit/submission. Native overloads supply no windows and cannot bypass exFAT admission. Background submission uses checked original bundle authorities. Request-key replay remains Source-set identity and never submits/attaches replacement windows. Existing rollback/scheduling unwind is retained. Slice 4/mounted test callers were updated to supply their actual captured UUIDs; the API race fixture overrides the new overload.
- **Metadata handoff/recovery:** existing four stages and HANDOFF/standalone ownership are retained. A narrow runtime authority-loss exception maps stale handoff/window admission to bounded HTTP 409 rather than generic 500. The exFAT UI follows the returned associated Job ID; remembered association is browser memory only. An explicit retry tries the valid original handoff first, then, on its 409, may use freshly prepared explicit windows for a new standalone Job. Neither backend nor UI resumes old discovery/assignment/hash/receipts. Metadata execution version 1 and null durable `job.scan_run_id` are unchanged. Full browser reload cannot reconstruct an expired association from latest timestamps; explicit recovery may be needed. Cached catalog/metadata results remain readable.
- **Finite previews:** existing Slice 5 capture, protected IO and sealed-batch drain are retained. Missing-window finite admission fails as AUTHORITY_UNAVAILABLE without host reads, including injected-policy integration. UI accepts CACHED/AUTHORITY_UNAVAILABLE and keeps authority loss terminal. An explicit Library request re-fetches Source state and consumes its exact prepared UUID for one preview; existing API maximum remains 100, queue 64, one worker. Authority-bearing API calls cannot be silently split across batches. Queued captures never switch to new occurrences/content/windows. Published previews need no live authority.
- **Frontend:** Source API types, indexing/metadata/preview request shapes, READY versus live display, explicit Prepare/Accept, current-projection refresh before Analyze/poll/focus/terminal/release, uncertain-request replay with original window input, and release controls independent of ordinary busy state. RELEASED/DRAINING and other-window text do not claim the volume is idle or manage VeraCrypt. Library/detail/exact-copy controls consume persisted physical capabilities. Reveal and physical cleanup remain disabled/explained for exFAT even with live content authority. Node tests include actual React rendering with existing TypeScript/React dependencies; no framework/dependency was added.
- **Docs:** README, ARCHITECTURE, DECISIONS, STATUS, implementation plan and this report describe integration before activation and the remaining gates. Historical Slice reports and DATA_MODEL are unchanged; no persistent structure changed.

The complete exact modified/new filename inventory appears in section 7 below.

## 3. Authority invariants and audit

READY means durable configuration; live availability is a separate catalog/registry-only projection. GET/list do not probe, acquire, revoke, repair or write. Reacquisition uses the existing no-change writer reread; release is registry-only. Exact Source/context/open-period snapshots and supplied window UUIDs flow into immutable admitted bundles; no indexing/content task selects a latest window or implicitly prepares. Request-key replay exits before new admission and never resubmits. Delayed old release targets runtime+Source+UUID, with context only supporting the other-volume projection.

Protected originals retain the Slice 5 protected ancestor/file handles, first full SHA/length, supplied-channel JPEG/PNG decoding, second full SHA/length, route/window/catalog revalidation, guarded publication and commit before closure. No decoder, pathname reopen, whole-file spool, occurrence/receipt semantics or cache identity was changed. Metadata/preview completion creates no occurrence. Historical physical-action flags derive persisted evidence and never inspect live windows. Native FileIdInfo/APFS rules, formats and original-read paths are retained.

No schema/migration, durable window, activation flag, new Job status, identity decision, Source relocation/rebind UI, Explorer, destructive operation, additional exFAT image format, video/ffprobe or dependency/build-tool change. Production policy is unchanged. Fake-host service/catalog tests exercise the authority boundary below HTTP, not just mocked controller successes.

## 4. Production policy

`WindowsExfatSupport.PRODUCTION.available()` remains **false**. `WindowsExfatSupport.java` and its production availability source line have no diff. No property/environment/HTTP/dev activation switch was introduced. Existing package-local test injection permits portable integration tests only. The production bundle admission guard now consults the same unchanged policy consistently rather than rejecting unconditionally even after a hypothetical future separately reviewed flip; it still rejects production exFAT today.

## 5. Validation

All commands below actually ran; final shell exits were collected. Backend tests ran from `backend/` with the already-cached Mockito premain agent and ignored workspace temporary directory, without installing/upgrading software. The host-specific validation paths are evidence, not application configuration.

Common exact backend argument:

```sh
-DargLine=-javaagent:/Users/chris/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar -Djava.io.tmpdir=/Users/chris/Code/Personal/Projects/media-compare/backend/target/tmp
```

Each test command is `./mvnw -o '<selector below>' '<common argument above>' test > target/<log> 2>&1`. Full runs omit the selector.

| Check / selector | Log | Tests | Failures | Errors | Skips | Exit / Maven result |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| Compile/test compile: `./mvnw -o -q -DskipTests test` (twice) | slice6-compile.log | not run | — | — | — | 0 both; successful compile |
| Initial focused: `-Dtest=ExfatSlice6IntegrationTests,WindowsExfatPreparationLifecycleTests,SourceApiTests,IndexingRunApiTests,MediaMetadataRunApiTests,ExfatSlice5SchedulerTests` | slice6-focused.log | 61 | 2 | 0 | 0 | 1 / BUILD FAILURE |
| Same focused selector after race-fixture correction | slice6-focused-rerun.log | 61 | 0 | 0 | 0 | 0 / BUILD SUCCESS |
| Focused integrated: same selector plus `ExfatSlice5FlowTests` | slice6-focused-final.log | 102 | 0 | 0 | 0 | 0 / BUILD SUCCESS |
| exFAT regressions: `-Dtest=ExfatAuthorityWindowRegistryTests,WindowsExfatPreparationLifecycleTests,WindowsExfatEvidenceCodecTests,ExfatSlice4*Tests,ExfatSlice5*Tests,ExfatObservationReceiptCodecTests,ExfatOccurrenceRepositoryTests,ExactDuplicateCapabilityTests,PreparedPreviewPublicationTests` | slice6-exfat-regressions.log | 208 | 0 | 0 | 0 | 0 / BUILD SUCCESS |
| Native regressions: `-Dtest=WindowsNtfs*Tests,WindowsHostFileSystemTests,WindowsProtectedAccessTests,WindowsDurableEvidenceDispatchTests,WindowsDisabledPreparationTests,SourcePreparationServiceTests,SourceBindingServiceTests,SourceUnbindingServiceTests,SourceMembershipAuthorityTests,ScanObservationAuthorityTests,Version3ExecutionTests,Version3DiscoveryWalkerTests,HostFileSystemTests,MacOs*Tests` | slice6-native-regressions.log | 263 | 0 | 0 | 15 | 0 / BUILD SUCCESS |
| First full backend | slice6-full-backend.log | 1542 | 0 | 0 | 16 | 0 / BUILD SUCCESS |
| Final lifecycle audit: `-Dtest=ExfatSlice6IntegrationTests,WindowsExfatPreparationLifecycleTests,ExfatAuthorityWindowRegistryTests,ExfatSlice5FlowTests` | slice6-audit-focused.log | 74 | 0 | 0 | 0 | 0 / BUILD SUCCESS |
| Final full backend after unavailable-root correction | slice6-full-backend-final.log | **1543** | **0** | **0** | **16** | **0 / BUILD SUCCESS** |

The first two focused failures were the existing concurrent-key fixture still overriding the old acceptance overload; correcting the fixture restored its real race barrier. The final lifecycle test proves unavailable retained-root projection, invalidation without replacement in that request, separate explicit reacquisition and unchanged durable rows. It justified the final full rerun. Final XML independently totals 134 suites / 1543 tests / 0 failures / 0 errors / 16 skips.

Full-suite skips: WindowsProtectedAccess 5, WindowsNtfsExecution 3, WindowsNtfsReparse 2, WindowsNtfsNative 3, HostFileSystem 1, optional MacOsMountedVolumeContinuityAcceptance 1, opt-in MountedExfatSlice5Acceptance 1. Portable NTFS/APFS authority tests and Mac fixtures pass. No real Windows NTFS or mounted VeraCrypt/exFAT test ran on this Mac; the optional mounted APFS gate was skipped. Prior mounted Slice 5 evidence remains historical qualification, not a new production gate.

Frontend commands ran from `frontend/`:

| Command | Actual executions/results |
| --- | --- |
| `node --test tests/*.test.mjs` | Initially 65/65 passed; an unchanged-suite repeat also passed 65. After rendering coverage, two runs passed 68/68; final reviewed run: 68 tests, 0 failures, 0 cancellations, 0 skips, exit 0. Logs: `/tmp/slice6-frontend-tests*.log`. Existing Vite rendering setup emits a sandbox-denied optional HMR WebSocket warning; tests themselves pass. |
| `npm run lint` | Initial exit 1 for an effect's synchronous state path; corrected asynchronous refresh. Both subsequent runs exit 0, no lint errors. Logs: `/tmp/slice6-frontend-lint*.log`. |
| `npm run build` | Initial exit 2 for a duplicated narrowed thumbnail-phase comparison; corrected. Both subsequent builds exit 0 (TypeScript + Vite). Logs: `/tmp/slice6-frontend-build*.log`. |
| `java -version`; `node --version` | Both exit 0; versions listed above. |
| `git diff --check` | Repository-root checks pass, exit 0. |
| `git status --short --untracked-files=all`; `git diff --stat`; `git rev-parse HEAD`; `git branch --show-current`; `git stash list` | Exit 0; final outputs below. Stash output empty; branch main. |

Two local editing-script invocations used the wrong working directory and stopped with file-not-found before any mutation; rerunning from the repository root completed them. They were not validation passes. New files were also checked independently for trailing whitespace/BOM because ordinary `git diff --check` excludes untracked files. No browser interaction acceptance or Windows/mounted production qualification is claimed by static rendering/portable tests.

## 6. Remaining gates

External diff review is next. Production availability flip is **not authorized** by this task and remains a distinct, separately reviewable final Slice 6 sub-step. The later Mac → Windows validation/commit workflow remains undecided. After separately authorized activation, real Windows/VeraCrypt/exFAT end-to-end production acceptance must pass. **exFAT V1 is not complete.** No production-enabled/unqualified main commit is assumed.

## 7. Final repository state / exact file inventory

All changes are unstaged. `M` denotes modified tracked files; `??` denotes new untracked files. Ordinary diff stat excludes those new files.

`git status --short --untracked-files=all`:

```text
 M README.md
 M backend/src/main/java/io/github/topher6835/mediacompare/catalog/WindowsExfatSourcePreparationService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/contentread/ExfatContentReadBundles.java
 M backend/src/main/java/io/github/topher6835/mediacompare/contentread/ExfatContentReadCatalog.java
 M backend/src/main/java/io/github/topher6835/mediacompare/filesystem/ExfatAuthorityWindowRegistry.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/ExfatScanBundles.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/IndexingRunAcceptance.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/IndexingRunService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/scan/Version2BackgroundIndexingService.java
 M backend/src/main/java/io/github/topher6835/mediacompare/web/CreateIndexingRunRequest.java
 M backend/src/main/java/io/github/topher6835/mediacompare/web/IndexingRunController.java
 M backend/src/main/java/io/github/topher6835/mediacompare/web/MediaMetadataRunController.java
 M backend/src/main/java/io/github/topher6835/mediacompare/web/SourceController.java
 M backend/src/main/java/io/github/topher6835/mediacompare/web/SourceResponse.java
 M backend/src/test/java/io/github/topher6835/mediacompare/IndexingRunApiTests.java
 M backend/src/test/java/io/github/topher6835/mediacompare/catalog/WindowsExfatPreparationLifecycleTests.java
 M backend/src/test/java/io/github/topher6835/mediacompare/contentread/MountedExfatSlice5AcceptanceTests.java
 M backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice4GuardsTests.java
 M backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice4TestSupport.java
 M backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice5FlowTests.java
 M docs/ARCHITECTURE.md
 M docs/DECISIONS.md
 M docs/STATUS.md
 M docs/WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md
 M frontend/src/api/exactDuplicates.ts
 M frontend/src/api/indexing.ts
 M frontend/src/api/mediaLibrary.ts
 M frontend/src/api/mediaMetadata.ts
 M frontend/src/api/sources.ts
 M frontend/src/duplicates/DuplicatePhysicalCopies.tsx
 M frontend/src/library/MediaLibraryCard.tsx
 M frontend/src/library/MediaLibraryItemDetailPage.tsx
 M frontend/src/library/RevealFileButton.tsx
 M frontend/src/library/mediaLibraryState.ts
 M frontend/src/library/useVisibleThumbnails.ts
 M frontend/src/sources/SourcesPage.tsx
 M frontend/src/sources/metadataWorkflow.ts
 M frontend/src/sources/useMetadataWorkflow.ts
 M frontend/tests/libraryDetailNavigation.test.mjs
?? backend/src/main/java/io/github/topher6835/mediacompare/catalog/SourceAuthorityProjection.java
?? backend/src/main/java/io/github/topher6835/mediacompare/filesystem/ExfatAuthorityUnavailableException.java
?? backend/src/main/java/io/github/topher6835/mediacompare/web/ReleaseSourceAuthorityRequest.java
?? backend/src/main/java/io/github/topher6835/mediacompare/web/ReleaseSourceAuthorityResponse.java
?? backend/src/test/java/io/github/topher6835/mediacompare/scan/ExfatSlice6IntegrationTests.java
?? docs/WINDOWS_EXFAT_SLICE6_REPORT.md
?? frontend/src/sources/SourceAuthorityControls.tsx
?? frontend/tests/exfatAuthority.test.mjs
?? frontend/tests/exfatRendering.test.mjs
```

`git diff --stat`:

```text
 README.md                                          | 10 ++-
 .../WindowsExfatSourcePreparationService.java      |  6 +-
 .../contentread/ExfatContentReadBundles.java       |  3 +-
 .../contentread/ExfatContentReadCatalog.java       |  3 +-
 .../filesystem/ExfatAuthorityWindowRegistry.java   |  7 +-
 .../mediacompare/scan/ExfatScanBundles.java        | 32 ++++++--
 .../mediacompare/scan/IndexingRunAcceptance.java   |  9 ++-
 .../mediacompare/scan/IndexingRunService.java      |  9 ++-
 .../scan/Version2BackgroundIndexingService.java    |  2 +-
 .../mediacompare/web/CreateIndexingRunRequest.java | 11 ++-
 .../mediacompare/web/IndexingRunController.java    |  6 +-
 .../web/MediaMetadataRunController.java            |  4 +-
 .../mediacompare/web/SourceController.java         | 37 ++++++---
 .../mediacompare/web/SourceResponse.java           | 10 +++
 .../mediacompare/IndexingRunApiTests.java          |  5 +-
 .../WindowsExfatPreparationLifecycleTests.java     | 41 ++++++++++
 .../MountedExfatSlice5AcceptanceTests.java         |  2 +-
 .../mediacompare/scan/ExfatSlice4GuardsTests.java  |  6 +-
 .../mediacompare/scan/ExfatSlice4TestSupport.java  |  5 +-
 .../mediacompare/scan/ExfatSlice5FlowTests.java    | 37 +++++++++
 docs/ARCHITECTURE.md                               |  8 +-
 docs/DECISIONS.md                                  |  7 ++
 docs/STATUS.md                                     | 24 +++++-
 docs/WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md          |  4 +-
 frontend/src/api/exactDuplicates.ts                |  2 +
 frontend/src/api/indexing.ts                       |  5 +-
 frontend/src/api/mediaLibrary.ts                   |  9 ++-
 frontend/src/api/mediaMetadata.ts                  | 12 ++-
 frontend/src/api/sources.ts                        | 33 ++++++++
 .../src/duplicates/DuplicatePhysicalCopies.tsx     |  7 +-
 frontend/src/library/MediaLibraryCard.tsx          |  6 +-
 .../src/library/MediaLibraryItemDetailPage.tsx     |  5 +-
 frontend/src/library/RevealFileButton.tsx          |  7 +-
 frontend/src/library/mediaLibraryState.ts          |  7 +-
 frontend/src/library/useVisibleThumbnails.ts       | 22 ++++++
 frontend/src/sources/SourcesPage.tsx               | 87 ++++++++++++++++++----
 frontend/src/sources/metadataWorkflow.ts           | 10 +++
 frontend/src/sources/useMetadataWorkflow.ts        | 52 +++++++++++--
 frontend/tests/libraryDetailNavigation.test.mjs    |  3 +-
 39 files changed, 478 insertions(+), 77 deletions(-)
```

`git rev-parse HEAD`:

```text
d547715d4f3cd44147b23e601e7012130542c3c7
```

Branch: `main`. `git stash list`: no output. No staging, commit or push.
