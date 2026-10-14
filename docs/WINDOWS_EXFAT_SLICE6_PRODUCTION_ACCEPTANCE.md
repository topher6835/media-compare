# Windows exFAT Slice 6 — production acceptance checkpoint

Recorded 2026-10-13 from the user's completed Windows acceptance session and the subsequent repository review. This records completed checks and remaining gates; **Slice 6 is not fully accepted or production-approved**.

## Repository and environment

- Branch `main`, HEAD `6b70028` (`Integrate Windows exFAT Slice 6 pre-activation workflow`).
- `WindowsExfatSupport.PRODUCTION = true` is a local, uncommitted and unpushed activation. Before this documentation task, it was the only working-tree change. No production code or tests were changed by this task.
- Source directory: `Z:\MediaCompare-Slice6-Acceptance-20261009`, dedicated disposable JPEG/PNG fixtures on a real VeraCrypt-mounted exFAT volume.
- Session: `D:\Dev\media-compare-slice6-session-20261009`, on NTFS.
- Source ID 1: `READY`, profile `EXFAT`, location revision 1.
- Last reported runtime checkpoint: no live authority or active Jobs; Scan 3 and Metadata Job 5 `COMPLETED`. The backend was running on port 8080 and VeraCrypt remained mounted as `Z:`.

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

These results remain valid historical qualification; they are not new observations of the production-enabled public workflow. **Native handle closure was not independently verified during the Slice 6 production session recorded here.** API release success, `live=false` and terminal Job status alone do not supply that observation. The old [Slice 6 report](WINDOWS_EXFAT_SLICE6_REPORT.md) records pre-activation integration and automated validation, not this production session.

## Mandatory remaining gates and evidence gaps

Sections 12 (real production acceptance) and 13 (Slice 6 gate) of the [approved plan](WINDOWS_EXFAT_IMPLEMENTATION_PLAN.md) remain in force. Nothing in this checkpoint waives them.

| Gate | Remaining work / evidence boundary |
| --- | --- |
| Active-work release and native closure | Demonstrate release through the production public endpoint while work is genuinely active, cancellation/draining and actual closure of the relevant native handles. Public RUNNING status is not a protected-read barrier. Existing deterministic hash/decode/publication race tests and earlier mounted qualification cover the mechanism, subject to validating their evidence; no need to recreate every race manually. Immediate `RELEASED` can be correct: observing a prolonged `DRAINING` response is not itself mandatory. |
| Enabled-workflow dismount/remount | After confirmed release/closure, observe normal user-managed VeraCrypt dismount/remount, unavailable old authority and explicit fresh acquisition through the enabled workflow. Earlier Slice 5 remount results do not complete this gate. Never force dismount. |
| Overlapping Sources/shared observation | No production-session result supplied. Validate any existing applicable evidence; otherwise complete the approved real-production check using disposable data. Portable overlap tests do not alone demonstrate this mounted public scenario. |
| Interrupted Jobs/crash recovery | Clean restart/reacquisition passed. Recovery of genuinely unfinished work, without stale-window resume or false MISSING, still lacks production-session evidence. Validate earlier applicable records or complete the missing check. |
| Source/Session separation, casing/junctions | Separate exFAT Source and NTFS Session paths are established. Negative boundary and alias/reparse checks in this production workflow are not evidenced by that fact alone. Reconcile existing native/automated results and record remaining supported-host checks and privilege limits. |
| Physical-action denial | Library capability projection passed. Direct reveal denial and cleanup preflight `BLOCKED`/`AUTHORITY_UNAVAILABLE`, including keeper handling, lack supplied production-session evidence. Preserve the existing automated no-filesystem/process-work checks. |
| Production-enabled backend regressions | No new enabled-suite result supplied. Review/correct obsolete disabled-policy assumptions, then validate the enabled tree without weakening authority guards or test isolation. |
| Windows NTFS / macOS APFS | Validate the required host regression gates against the final tree and distinguish genuine passes from host/privilege skips. Existing reports are earlier evidence, not newly executed enabled-tree results. |
| Frontend | Validate applicability of prior Node tests, lint and build results; rerun only if evidence does not cover the unchanged frontend. The pre-activation report's correction pass records 75 Node passes, superseding its earlier 68-test result. Browser acceptance of live-state/release feedback and physical-action restrictions is not established by API results or rendering tests alone. |
| Final review and approval | Review the activation diff and final evidence, update durable status, and obtain explicit commit approval. Activation remains uncommitted/unpushed; no production approval is claimed. |

### Known disabled-policy test assumptions

Read-only review found obsolete production-disabled assertions or expectations in:

- `WindowsHostFileSystemTests` (including `unsafeElement` and default native-adapter expectations).
- `WindowsDurableEvidenceDispatchTests`.
- `WindowsExfatPreparationLifecycleTests` (including public Prepare denial).
- `ExfatObservationReceiptCodecTests`.
- `ExfatSlice4FlowTests` and `ExfatSlice4GuardsTests`.
- `ExfatSlice5FlowTests` (including production metadata/preview admission denial).
- Opt-in `MountedExfatSlice5AcceptanceTests` and helper `MountedSlice5Fixture`, if that historical harness is rerun.

Some assertions are incidental to unrelated behavior; others explicitly test the old closed gate. Do not simply invert assertions or let tests pass through an unrelated invalid lifecycle state. Preserve disabled-policy checks through existing injection seams where applicable, enabled exact-owner admission/rejection coverage, and disposable/fake native isolation. No corrections or test runs occurred in this documentation task.

## Smallest remaining sequence

1. Reconcile available evidence with the gaps above and correct obsolete test assumptions in a separately authorized implementation task. Do not repeat completed production scans, metadata, preview, identity, SQL, authority-rejection or replay checks solely to reproduce this checkpoint.
2. Validate the final enabled backend on Windows and the required macOS/APFS gate; validate frontend test/lint/build evidence and the remaining browser behavior. Avoid redundant focused runs after successful encompassing validation.
3. Combine public active-work release, native closure, normal dismount/remount and reacquisition into one controlled acceptance sequence. Complete other explicitly required production checks only where applicable evidence is missing. Use only dedicated disposable fixtures; add no production delays, weakened checks or test-only activation behavior.
4. Record final outcomes and residual supported-host limits, review the activation, and seek commit approval. Slice 6/exFAT V1 remains incomplete until mandatory gates are satisfied.

Different-letter relocation, forced/surprise removal, exFAT Session storage, additional decoders, Explorer/destructive cleanup and unavailable privileged symlink cases remain outside the qualified scope or explicitly limited. They do not excuse failure of ordinary release/closure, supported junction denial or other mandated checks. No architecture, schema or approved identity decision changed.
