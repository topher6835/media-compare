# Windows exFAT Slice 6 — production acceptance checkpoint

Recorded 2026-10-13 from the user's acceptance conversation and repository review, updated with backend/frontend validation and normal VeraCrypt dismount/remount results. This records completed checks and remaining gates; **Slice 6 is not fully accepted or production-approved**.

## Repository and environment

- Current checkout: branch `main`, HEAD `6657eec`. Slice 6 integration was committed at `6b70028` (`Integrate Windows exFAT Slice 6 pre-activation workflow`).
- `WindowsExfatSupport.PRODUCTION = true` remains a local, uncommitted and unpushed activation. Corrected gate-dependent tests also remain uncommitted. At the initial acceptance checkpoint, activation was the only working-tree change. This documentation update changed no production code or tests.
- Source directory: `Z:\MediaCompare-Slice6-Acceptance-20261009`, dedicated disposable JPEG/PNG fixtures on a real VeraCrypt-mounted exFAT volume.
- Session: `D:\Dev\media-compare-slice6-session-20261009`, on NTFS.
- Source ID 1: `READY`, profile `EXFAT`, location revision 1.
- Last reported runtime checkpoint: Source 1 remains READY/EXFAT/revision 1 with no live authority after post-remount release; no other windows remain. Scan 3 and Metadata Job 5 remain the completed indexing/metadata checkpoint. The backend was running on port 8080 and the same VeraCrypt volume was remounted as `Z:`.

These paths identify the acceptance environment, not application configuration defaults. No personal media or forced dismount is part of this acceptance.

## Completed production evidence

The following results were observed and captured in the user's acceptance conversation, including detailed API responses, request keys, authority-window UUIDs, SQL results, occurrence UUIDs and timestamps. Those raw captures have not been independently archived as repository evidence; this report summarizes the conversation evidence without reproducing or reconstructing it. This documentation task did not repeat API calls, SQLite queries, builds, tests or mounted-volume operations.

### Public API and application observations

| Check | Reported result |
| --- | --- |
| Initial Prepare/Accept | Production preparation and live authority acquisition succeeded. |
| Release/reacquisition | Release and fresh acquisition succeeded without durable Source revision changes. |
| Application restart | Durable Source configuration survived; live authority was not restored. Explicit reacquisition succeeded. This is not evidence of recovery from an interrupted active Job. |
| Indexing | Scan IDs 1, 2 and 3 completed. Each freshly hashed all three fixtures, with zero failures and zero cached hashes. |
| Metadata | JPEG/PNG extraction succeeded. Scan 3 handed its authority to Metadata Job 5; Job 5 completed with 3 available results and no failures. Its durable `job.scan_run_id` remains null by design; the handoff association is a public workflow observation, not a durable SQL link. |
| Initial thumbnails | Three thumbnails were generated and published; served assets returned HTTP 200 and `image/png`. |
| Cache reuse | Cached thumbnails remained usable without live exFAT authority. |
| Missing/released authority | Indexing rejected missing authority and an actual released UUID with HTTP 409. |
| Stale authority/release | An old UUID was rejected with HTTP 409 while replacement authority remained available. Stale release did not revoke the replacement. |
| Natural expiry | Prepared-window expiration was observed; indexing with the expired UUID returned HTTP 409. |
| Request-key replay | Returned the original completed execution without creating new work. |
| Follow-up checks | No unexpected Jobs or durable Source revision changes were observed. |
| Physical capabilities | Library projections showed historical exFAT physical actions unavailable. This does not independently demonstrate direct reveal or cleanup-preflight endpoint denial. |

Rejection checks require fresh request keys. Captured request keys and authority-window UUIDs are part of the acceptance conversation evidence, not an independently archived repository artifact. Replay is separate evidence because an existing key returns the original execution before new-window admission. This summary asserts only the scan and metadata Job identifiers listed above.

### Direct SQLite verification

Historical catalog verification reported **9/9 integrity PASS**:

- Nine distinct occurrence tokens, nine FileEntries, nine ContentRecords and nine completed SHA-256 records across the three scans.
- Source 1 memberships: 3 `ACTIVE/PRESENT`, 6 `RETIRED/PRESENT`, 0 `MISSING`.
- Retired records remained available and did not inflate current physical exact-byte duplicate counts.

This establishes the verified nine-record checkpoint. A later interrupted acceptance scan may add committed positives or retire memberships; it must preserve those historical records, but need not preserve the current totals unchanged.

### Earlier native qualification — separate evidence

The committed [Slice 5 mounted report](WINDOWS_EXFAT_SLICE5_REPORT.md) records real protected reads, in-flight release with `DRAINING`, eventual `RELEASED` with zero counted native handles, publication/commit/close ordering, and normal user-managed VeraCrypt dismount/remount. Production activation was false in that harness, which used injected ownership/observation seams. The [retained-handle qualification](WINDOWS_EXFAT_RETAINED_HANDLE_QUALIFICATION.md) is earlier mechanism evidence as well.

These results remain valid historical qualification; they are not new observations of the production-enabled public workflow. **Native handle closure during active work was not independently verified in the Slice 6 production session.** API release success, `live=false` and terminal Job status alone do not supply that observation. The normal idle dismount/remount result below is separate evidence. The old [Slice 6 report](WINDOWS_EXFAT_SLICE6_REPORT.md) records pre-activation integration and automated validation, not this production session.

### Completed backend and frontend validation

| Gate | Result |
| --- | --- |
| Windows backend | 1,546 tests: 1,255 passed, 291 skipped, zero failures/errors; BUILD SUCCESS. Skip audit found 52 explicit Windows exclusions, 226 macOS/APFS runtime assumptions, 12 symbolic-link limitations and 1 opt-in mounted test. No unexpected production-gate exclusions. |
| macOS backend | 1,544 tests: 1,528 passed, 16 skipped, zero failures/errors; BUILD SUCCESS. All skips were explained by Windows-only coverage, platform assumptions or opt-in acceptance. |
| Frontend | Node tests, ESLint and the TypeScript/Vite production build passed. |

The Windows audit read the reasons in the XML `<skipped>` text bodies where `message` attributes were blank. It isolated the latest Surefire run's 134 suites, excluding four stale XML reports containing 45 older tests. Windows NTFS native/execution/protected-access suites and the enabled exFAT admission suites passed. Junction rejection and case/junction Session separation passed; privileged symbolic-link cases remain limited. One mixed APFS/exFAT integration case and two macOS-only exFAT separation cases were intentionally excluded on Windows; they are not evidence of mixed NTFS/exFAT coverage on that host. No skip was attributed to the obsolete disabled production gate.

These results were supplied in the acceptance conversation; the Windows skip audit additionally inspected repository reports and source annotations. No validation was rerun by this documentation update. Automated frontend success does not independently establish the remaining browser behavior checks.

### Normal VeraCrypt dismount/remount — enabled public workflow

With the backend running and Source 1 READY/EXFAT/revision 1, live authority was false before normal VeraCrypt dismount. Dismount succeeded without force; the same volume remounted successfully as `Z:`. Source 1 remained READY at revision 1 with live authority false. Explicit Prepare acquired the fresh UUID `c6ed6c91-2715-4de2-ac05-aff3f1e9473e`. Exact-window release returned `RELEASED`; afterward live authority was false, no other windows remained and revision stayed 1.

This completes the recorded normal idle dismount/remount, fresh acquisition and release check for the enabled public workflow. **It does not verify release or native handle closure during active work.** No independent native handle count is claimed for this sequence.

## Mandatory remaining gates and evidence gaps

Sections 12 (real production acceptance) and 13 (Slice 6 gate) of the [approved plan](WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md) remain in force. Nothing in this checkpoint waives them.

| Gate | Remaining work / evidence boundary |
| --- | --- |
| Active-work release and native closure | Demonstrate release through the production public endpoint while work is genuinely active, cancellation/draining and actual closure of the relevant native handles. Public RUNNING status is not a protected-read barrier. Existing deterministic hash/decode/publication race tests and earlier mounted qualification cover the mechanism, subject to validating their evidence; no need to recreate every race manually. Immediate `RELEASED` can be correct: observing a prolonged `DRAINING` response is not itself mandatory. |
| Overlapping Sources/shared observation | No production-session result supplied. Validate any existing applicable evidence; otherwise complete the approved real-production check using disposable data. Portable overlap tests do not alone demonstrate this mounted public scenario. |
| Interrupted Jobs/crash recovery | Clean restart/reacquisition passed. Recovery of genuinely unfinished work, without stale-window resume or false MISSING, still lacks production-session evidence. Validate earlier applicable records or complete the missing check. |
| Source/Session separation, casing/junctions | Separate exFAT Source and NTFS Session paths are established. Negative boundary and alias/reparse checks in this production workflow are not evidenced by that fact alone. Reconcile existing native/automated results and record remaining supported-host checks and privilege limits. |
| Physical-action denial | Library capability projection passed. Direct reveal denial and cleanup preflight `BLOCKED`/`AUTHORITY_UNAVAILABLE`, including keeper handling, lack supplied production-session evidence. Preserve the existing automated no-filesystem/process-work checks. |
| Browser acceptance | Automated frontend tests/lint/build passed. Browser acceptance of live-state/release feedback and physical-action restrictions still lacks explicit recorded evidence; API results or rendering tests alone do not establish it. |
| Final review and approval | Review the activation diff and final evidence, update durable status, and obtain explicit commit approval. Activation remains uncommitted/unpushed; no production approval is claimed. |

### Corrected disabled-policy test assumptions

The earlier review identified obsolete production-disabled assertions or expectations in:

- `WindowsHostFileSystemTests` (including `unsafeElement` and default native-adapter expectations).
- `WindowsDurableEvidenceDispatchTests`.
- `WindowsExfatPreparationLifecycleTests` (including public Prepare denial).
- `ExfatObservationReceiptCodecTests`.
- `ExfatSlice4FlowTests` and `ExfatSlice4GuardsTests`.
- `ExfatSlice5FlowTests` (including production metadata/preview admission denial).
- Opt-in `MountedExfatSlice5AcceptanceTests` and helper `MountedSlice5Fixture`, if that historical harness is rerun.

A separate test-only correction removed incidental global-flag assertions, retained disabled native/Prepare policy checks through injection, and checked enabled exact-owner admission/rejection with valid lifecycle setup and disposable/fake native isolation. The corrected tests remain uncommitted; the backend validation above passed with the documented exclusions. The mounted harness remains opt-in and was skipped in the normal Windows run. No test correction or execution occurred in this documentation update.

## Smallest remaining sequence

1. Preserve completed production checks, backend/frontend validation and the normal idle dismount/remount result. Do not repeat them solely to reproduce this checkpoint.
2. Demonstrate public active-work release, cancellation/draining and native closure using dedicated disposable fixtures. Add no production delays, weakened checks or test-only activation behavior. Idle remount success does not close this gate.
3. Reconcile applicable evidence for overlap, interrupted-work recovery, separation/casing/junctions, direct physical-action denial and browser behavior; complete only the explicitly required checks still missing.
4. Record final outcomes and residual supported-host limits, review the activation, and seek commit approval. Slice 6/exFAT V1 remains incomplete until mandatory gates are satisfied.

Different-letter relocation, forced/surprise removal, exFAT Session storage, additional decoders, Explorer/destructive cleanup and unavailable privileged symlink cases remain outside the qualified scope or explicitly limited. They do not excuse failure of ordinary release/closure, supported junction denial or other mandated checks. No architecture, schema or approved identity decision changed.
