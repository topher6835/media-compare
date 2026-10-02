# Windows exFAT retained-handle qualification — 2026-10-02 UTC

**Completed: the retained-handle mechanism is empirically qualified for this tested Windows/VeraCrypt/exFAT scenario. The restricted V1 authority design is frozen; production support remains unimplemented.** Approved baseline and starting clean HEAD: `725d6697140b72bafe4a687933836f4f5ab040c5`. Actual raw events use UTC; the Windows local date was 2026-10-01, America/New_York.

## Evidence, isolation and diagnostic

[Raw evidence](evidence/windows-exfat-retained-handle-2026-10-02.log) contains the initial attempt, its diagnostic assertion failure, guarded cleanup, the corrected rerun, checkpoint, explicit release, labeled user-reported normal dismount/remount, fresh guarded acquisition/comparisons and final cleanup. Earlier output was verified preserved verbatim, including the entire pre-continuation byte prefix. The prior investigation report/log remain unchanged. No production code, dependency, schema, NTFS/APFS behavior or existing Session/catalog was changed or opened. No commit/push, tool installation or automatic VeraCrypt action occurred.

The standalone [probe](../backend/diagnostics/WindowsExfatRetainedHandleProbe.java) and [native calls](../backend/diagnostics/RetainedHandleNative.java) compile with Java 21 and existing JNA/JNA Platform 5.17.0. They are outside Spring and the normal test suite. Independently opened Win32 pathname operations run in the diagnostic process; they do not reuse the protected handle for mutation. Each failing native call's error is captured immediately.

The only volume fixture is the newly created `Z:\media-compare-retained-handle-test`, with an exact `owner.txt` marker, `Source\Intermediate\ProtectedA.bin`, `ProtectedB.bin` and an empty `MoveTarget` control directory. The files are distinct 256-byte disposable BMP payloads; their `.bin` names do not influence the tested decoder. The probe refuses an existing fixture for both initial run and retry. Cleanup requires the marker, rejects unexpected occupants/links, requires the prior process to be dead, and deletes individually named files/directories without recursion. NTFS build copies, process output and control/checkpoint files reside under ignored `backend/target`.

Initial and rerun guards passed before fixture mutation: Windows 10, Java 21.0.12.1, existing `Z:`, native and NIO `exFAT`, expected serial `98d16f05`, DOS device `\Device\VeraCryptVolumeZ`, and GUID `\\?\Volume{5b22bd93-5d21-11ec-bf63-7085c24c9111}\`. These are environment/route checks, not durable physical identity.

## Completed observations

| Stage | Actual result |
| --- | --- |
| A: held directory chain | `GENERIC_READ`, `OPEN_EXISTING`, backup/no-follow flags, share READ + WRITE, no DELETE sharing, non-inheritable. Held drive root, fixture root, Source and Intermediate. |
| A: Source/Intermediate rename, move, delete | Every protected attempt returned error **32**, including empty-directory deletion after removing only the two named disposable files. Empty-directory controls isolate sharing protection from nonempty-directory restrictions. |
| A: controls after closure | Source/Intermediate rename, move and empty-directory delete succeeded; rename/move paths were restored, and deleted test directories/files were recreated. Native closes succeeded. |
| B: protected file | `GENERIC_READ`, `OPEN_EXISTING`, no-follow, share READ only, no WRITE/DELETE sharing, non-inheritable. |
| B: overwrite, truncate/write, delete, rename, move | All denied with error **32**. Protected path, retained observations and original bytes remained coherent. |
| B: replacement | Moving distinct B over A with `MOVEFILE_REPLACE_EXISTING` was denied with error **5** (access denied). A/B had equal size and the same explicitly set mtime before the protected attempt. No target replacement occurred. |
| B: controls after closure | All six equivalent mutation operations succeeded; known fixture contents and paths were restored. |
| C: existing writer | A separately opened GENERIC_WRITE handle allowed all sharing; protected read acquisition still failed with error **32**. |
| C: writable mapping | PAGE_READWRITE mapping + FILE_MAP_WRITE view were established. Protected acquisition failed with error **32**, both with the original writer open and after closing that writer while retaining the mapping/view. Unmap and mapping CloseHandle succeeded; protected acquisition then succeeded. |
| D: readers/hash | Another native read-only handle and an ordinary NIO input stream read all 256 original bytes. Full SHA-256 computed via ReadFile on the **same retained protected native handle** matched both controls and the known fixture digest. |
| D: decoder | JDK ImageIO pathname reopening decoded the BMP while protection remained held: 2 × 2 pixels, expected RGB `112233`. The subsequent protected read still matched original bytes. This qualifies that reader, not every future decoder. |
| E: observations | Native final path, serial, legacy index, attributes, size and exact FILETIMEs were logged before/after; NIO paths/classification/times were recorded separately. Protected observations remained coherent. Legacy equality is contradiction checking only, never durable identity. |
| Handle cleanup before F | Every tested file/directory CloseHandle succeeded; tracked handles reached zero between completed stages. Writer, mapping and view controls were closed. |
| F: normal dismount while held | User reports VeraCrypt blocked normal dismount with `Volume contains files or folders being used by applications or system. Force dismount?`. User declined force. This is a user-visible outcome, not an invented Win32 error or native dismount trace. |
| F: release and normal dismount | At `2026-10-02T01:22:34.925884Z` the diagnostic irrevocably revoked the old UUID. All six native CloseHandle calls succeeded, tracked count reached zero, checkpoint became DEAD and process exited successfully. User reports normal dismount then succeeded and the same volume was remounted at Z:. |
| G: fresh acquisition | `resume` exited 0 after repeating original Windows/volume guards, confirming DEAD checkpoint/dead old process/zero live handles, and acquiring a fresh six-handle chain under a different window UUID. No old serialized value became authority. |
| G: bytes and observations | Both files read exactly the 256 expected bytes from new protected handles; full SHA-256 matched. Exact bytes are preserved as Base64. Directory/file paths, serial, legacy index, classification, sizes and FILETIMEs matched the earlier snapshots; these comparisons were explicitly empirical, never acquisition authority. Fresh before/after reads stayed coherent. |
| Final cleanup | Fresh handles closed to zero. Guarded named cleanup exited 0 after volume/marker/occupant checks; the fixture no longer exists. No recursive deletion or unknown occupant removal occurred. |

Known full SHA-256 values:

```text
A: 8973eb01f650b8bd43f628944d8ba63d9b0bb345741fc66938b13bf468e4eda2
B: efafc01df4922033a06038649683cc1ac569e5ea4b183cba606a7021428fa1f4
```

The first attempt stopped because the harness incorrectly required error 32 for every denial; replacement actually returned error 5. That denial was preserved, not relabeled as a successful mutation. All handles closed to zero. The exact marked fixture was removed through guarded named cleanup and recreated for the rerun. The corrected assertion accepts errors 32 or 5 while still requiring unchanged paths/bytes and successful unprotected controls. The rerun reached the checkpoint without an authority-mechanism failure. Both attempts and cleanup remain in raw evidence.

All required authority-bearing criteria passed for the bounded, ordinary-operation/normal-dismount scenario. The mechanism is **empirically qualified for this tested Windows/VeraCrypt/exFAT scenario**, and the restricted V1 authority design can be frozen. This is diagnostic qualification, not production acceptance or a claim of durable/cross-scan physical identity. No forced dismount, surprise device loss, adversarial/raw-device mutation, different-letter remount, universal exFAT behavior, arbitrary decoder support or exFAT Session-storage qualification is established.

## Manual checkpoint, release and actual remount

At `2026-10-02T01:03:03.339119100Z`, process **13316** printed `READY FOR MANUAL VERACRYPT DISMOUNT TEST`. It held six native handles under runtime window `df01132f-8d21-45ab-b3d0-6dae96620671`:

| Retained object | Native handle value | Sharing |
| --- | --- | --- |
| `Z:\` | 1324 | READ + WRITE; no DELETE |
| `Z:\media-compare-retained-handle-test` | 1300 | READ + WRITE; no DELETE |
| `...\Source` | 1248 | READ + WRITE; no DELETE |
| `...\Source\Intermediate` | 1328 | READ + WRITE; no DELETE |
| `...\ProtectedA.bin` | 1292 | READ only |
| `...\ProtectedB.bin` | 1336 | READ only |

The checkpoint/baseline is `backend/target/retained-handle-control-2026-10-02/checkpoint.properties`. It recorded the exact observations, hashes, GUID, PID, UUID and bounded 90-minute deadline. Explicit release occurred before that deadline. Its phase is now **DEAD**, process 13316 has exited, and no handles remain. This serialized history cannot recreate a live window.

The user completed the requested sequence: normal dismount was blocked while held, force was declined, the diagnostic was explicitly released, normal dismount succeeded after release, and the same volume was remounted at Z:. Native release/close timestamps are in the process output. Exact manual-action timestamps were not supplied; evidence labels the outcomes as user reports recorded during continuation rather than fabricating event times. No force or different-letter cycle was performed. Normal dismount blocking is the observed operational cost of retaining these handles.

The diagnostic's release command was an NTFS control-file instruction; it never dismounted or remounted VeraCrypt. After release it logged six successful closes, zero tracked handles and a successful process exit. Old native handles were closed before the actual dismount, so this experiment does not claim observations of handles surviving a forced or successful dismount while still held.

## Post-remount acquisition and comparison

At `2026-10-02T01:29:29.634800100Z`, fresh guarded acquisition succeeded under window **`9f663b78-2734-470b-bf89-2762e82648f0`**, in new process 22236. The old UUID remained permanently revoked. The code requires DEAD checkpoint/dead prior process/zero current retained handles before new acquisition and never reconstructs handles from stored values. The checkpoint contributes comparison history only; today’s guarded native opens and protected reads supply the new diagnostic authority. This demonstrates the selected lifecycle in the standalone diagnostic; production runtime guards are still future implementation.

Post-remount device, native/NIO exFAT, serial `98d16f05` and volume GUID matched the original guards. All six final paths matched their configured direct routes. File A/B remained 256 bytes with the exact hashes above; their Base64 bytes are preserved in evidence. Both file observations compared equal to the stored before snapshots, including attributes `00000020`, legacy indices `000000a586580060` / `000000a586580180`, creation `2026-10-02T01:03:03.100Z`, access/mtime `2026-10-02T01:03:04Z` and final paths. Directory comparisons also matched: drive index zero, fixture `00000000012c0440`, Source `000000a5864c0180`, Intermediate `000000a586540140`, with unchanged recorded attributes/times/paths. Equality is sampled evidence only; none of these fields establishes durable identity or resurrects the old window.

The fresh window closed at `2026-10-02T01:29:29.639806900Z`; all six closes succeeded and tracked handles reached zero. Final cleanup at `2026-10-02T01:30:35.416314800Z` confirmed `fixtureExists=false`, and a separate PowerShell Test-Path also returned False. Only named owned occupants were removed. Repository-side evidence, diagnostic source and ignored comparison/checkpoint artifacts remain; there is no retained fixture or live diagnostic authority on Z:.

## Qualification criteria and remaining limits

| Required criterion | Result |
| --- | --- |
| Directory-chain rename/move/delete protection | PASS |
| File write/delete/rename/move/replacement protection | PASS; denial codes 32 and 5 preserved |
| Incompatible writer/writable mapping rejection | PASS, including mapping retained after writer handle closure |
| Compatible read-only consumers | PASS for tested native/NIO readers and JDK BMP decoder |
| SHA-256 from the same protected observation handle | PASS |
| Coherent before/after protected evidence | PASS |
| Ordinary mutations succeed after handle closure | PASS |
| Normal dismount blocked while six handles held | PASS, user-reported exact dialog |
| Normal dismount succeeds after controlled release | PASS, user-reported |
| Old runtime window remains dead after remount | PASS: revoked, six closes, dead process/checkpoint; no serialized replay |
| Fresh guarded acquisition after remount | PASS, different UUID and new native handles |
| Bounded handle/fixture cleanup | PASS: zero handles, named cleanup, fixture absent |

The frozen restricted profile retains the documented costs: independent positive observations after file-handle closure create new physical occurrences and distinct ContentRecords even for equal bytes; full fresh SHA-256 remains mandatory; loss of authority is not absence; physical reveal/cleanup preflight remains blocked for persisted exFAT entries after their handles close. ExFAT Session storage, broader decoder/provider qualification and stronger physical-identity guarantees remain outside this result. Product acceptance of occurrence churn and physical-action restrictions is still required before declaring full Windows V1 complete. NTFS/APFS behavior and storage remain unchanged. Later production implementation, runtime/API/UI guards and the occurrence/receipt migration need their own scoped work and acceptance; none was implemented here.

## Commands and validation

The initial compiler invocation against the user Maven cache printed an archive-close AccessDeniedException despite reporting exit 0. That invocation is not treated as a clean compile. Copying the two existing jars into the ignored build directory avoided the issue. The corrected compile completed with exit 0 and no diagnostic output:

```powershell
$build = Join-Path (Get-Location) 'backend\target\retained-handle-classes'
$cp = $build + ';' + (Join-Path $build 'jna-5.17.0.jar') + ';' + (Join-Path $build 'jna-platform-5.17.0.jar')
javac -proc:none -cp $cp -d $build backend/diagnostics/RetainedHandleNative.java backend/diagnostics/WindowsExfatRetainedHandleProbe.java
```

The standalone `run` was launched in a hidden background process, with stdout/stderr under the NTFS control directory. After its assertion abort, guarded `cleanup` exited 0 and confirmed fixture absence. The corrected `retry` was launched the same way; it refused an existing fixture and appended evidence rather than overwriting earlier output. Its completed output ends with successful release and zero tracked handles. The continuation added only byte/comparison reporting to `resume`, recompiled cleanly (exit 0), and did not repeat the earlier sharing tests.

Actual continuation commands, both exit 0:

```powershell
$evidence = Join-Path (Get-Location) 'docs\evidence\windows-exfat-retained-handle-2026-10-02.log'
$control = Join-Path (Get-Location) 'backend\target\retained-handle-control-2026-10-02'
java -cp $cp WindowsExfatRetainedHandleProbe resume $evidence $control
# Run after fresh-acquisition evidence was safely recorded and reviewed:
java -cp $cp WindowsExfatRetainedHandleProbe cleanup $evidence $control
```

The continuation verified the previous evidence prefix byte-for-byte and compared directory snapshots without using them as authority. `git diff --check` passed. Production/backend/frontend test gates were not run. Baseline comparison confirmed production trees, schema/dependency definitions and prior empirical evidence unchanged; authority-design/status documentation now records qualification and the restricted freeze. No production exFAT support, migration, commit or push was introduced.
