# Windows exFAT/VeraCrypt Investigation — 2026-10-01

Observation milestone reviewed against approved baseline `59cc9b4e944c6f4c29a65c7ec8ac1c8002c4e27c`, including one actual manual VeraCrypt dismount/remount. No production exFAT support, authority decision, NTFS change, schema change, commit, or push. Sampled remount continuity does not establish a stable legacy exFAT authority identity.

## Environment and isolation

- Current intentional checkout: `D:\Dev\projects\ai-projects\media-compare`, on NTFS. Current HEAD and approved baseline: `59cc9b4e944c6f4c29a65c7ec8ac1c8002c4e27c`.
- The original experiments initially ran at clean Windows checkout HEAD `dab40379ca61d2acaeb743a221ba4bc9d5e0dc60`. The investigation changes were subsequently reapplied cleanly onto `59cc9b4`; this final milestone is reviewed against that approved baseline. The earlier checkout mismatch is historical, not the current project state.
- Comparing `dab4037` to `59cc9b4` changed only `docs/STATUS.md` and `docs/WINDOWS_NTFS_ACCEPTANCE.md`, recording a manual NTFS UI smoke test and its observations. Backend/frontend trees are identical (`git diff --exit-code ... -- backend frontend` exited `0`). `WindowsNtfsNative`, `SessionSourceBoundary`, all filesystem/path authority code, tests, and dependency definitions are unchanged. No relevant production changes affected the diagnostic conclusions, so no affected diagnostics needed rerunning.
- Windows 10 Home 22H2, build `19045.6466`; Temurin Java `21.0.12.1`; default timezone `America/New_York`. Existing project: Spring Boot `4.1.1`, JNA/JNA Platform `5.17.0`. No software/dependency installation.
- Before creating fixtures, .NET `DriveInfo` reported `Z:\`, ready, Fixed, exFAT, empty label, total size `1099492491264`. `QueryDosDevice("Z:")` returned `\Device\VeraCryptVolumeZ`; VeraCrypt was running. These observations establish that the requested letter is currently a VeraCrypt-mounted exFAT volume; the underlying encrypted container/device was not inspected.
- `Z:\media-compare-authority-test` was absent before creation. All experiments used its disposable `Experiment` subtree. The NTFS alias controls used new `backend/target/exfat-alias-*` directories. Experimental files and aliases were removed, with aliases explicitly unlinked before tree cleanup. No real media or existing Windows test Session was inspected or modified; no Spring backend/catalog was opened.
- The marked `owner.txt`, `baseline.properties`, and `RemountEvidence/Stable.bin` (16 bytes) were retained for the completed manual remount. After preserving that output, the existing guarded cleanup command exited `0`; `Test-Path -LiteralPath 'Z:\media-compare-authority-test'` returned `False`. No fixture remains. Cleanup deleted only its named retained artifacts and root; no recursive deletion was used.

## Evidence and diagnostic

[Raw observations](evidence/windows-exfat-2026-10-01.log) preserve the initial run, focused follow-up, separate same-mount resume, wrong-serial guard rejection, actual post-remount output, and final guarded cleanup output. The existing `backend/target/exfat-remount.log` was appended verbatim under `===== exfat-resume-after-veracrypt-remount.log =====`; the copied text was compared exactly with the source, and earlier evidence was verified unchanged. Java/NIO records, native records, and the existing NTFS adapter's result have separate labels. Native success/error fields are captured immediately; errors printed after successful calls by the earlier PowerShell preflight were stale last-error values and are not failed API results.

`backend/diagnostics/WindowsExfatProbe.java` is a standalone Java source-launcher harness, outside production and the normal test suite. It uses existing compiled adapters and existing JNA jars. Before any fixture operation it requires Windows, `Z:`, the exact VeraCrypt DOS-device mapping, native and NIO exFAT type, and the caller-supplied expected serial. `run` refuses an existing fixture root; `resume` is observational; `followup` creates/removes only a new experimental subtree; `cleanup` deletes only named retained artifacts and refuses to recursively remove unknown occupants.

## Volume and API observations

| Interface | Actual result |
| --- | --- |
| NIO FileStore | `name=""`, `type="exFAT"`, text `(Z:)`, total `1099492491264`, `readOnly=false`; basic/dos views supported, acl/posix views unsupported |
| GetVolumeInformation | filesystem `exFAT`, empty label, 32-bit serial `98d16f05`, maximum component length `255`, flags `00020206` |
| GetVolumeNameForVolumeMountPoint | `\\?\Volume{5b22bd93-5d21-11ec-bf63-7085c24c9111}\` |
| GetFileInformationByHandle | Succeeded on created files/directories/root; returned serial `98d16f05`, a 64-bit file index, attributes and native FILETIMEs; link count `1` in the sampled objects |
| GetFileInformationByHandleEx(FileIdInfo) | Failed with error `87` for sampled exFAT files, directories, root, and exFAT descendants reached through an NTFS junction; no 128-bit identity was returned |
| GetFileInformationByHandleEx(FileAttributeTagInfo) | Also failed with error `87` on those exFAT objects |
| Existing WindowsNtfsNative adapter | Failed at its attribute-tag query with `Cannot inspect Windows file attributes (error 87)`; it did not establish exFAT identity |
| Native handle operations | CreateFile with backup/no-follow flags, legacy observation, GetFinalPathNameByHandle, direct SetFileTime, and CloseHandle worked in the successful experiments |

Volume serial and GUID stayed equal across the three original same-mount diagnostic processes and the actual manual dismount/remount below. Sixteen additional pairs of legacy-identity opens returned equal observations. These establish sampled same-mount stability and continuity across one remount of the same volume at the same letter. The serial, drive letter, VeraCrypt device name, and GUID have not been established as generally persistent or unique identity across volume replacement or arbitrary remounts.

## File, directory and root identity

NIO `BasicFileAttributes.fileKey()` was null for every sampled exFAT file/directory/root. File/directory classification, size, times, and real paths were available. The legacy indices below are unsigned numeric hexadecimal (`nFileIndexHigh` followed by `nFileIndexLow`), not 128-bit IDs or guaranteed persistent identities.

| Operation | File index64 | Directory index64 |
| --- | --- | --- |
| Create | `000000a586540000` | `000000a5865400c0` |
| Close/reopen | unchanged | unchanged |
| Short rename in same parent | unchanged | unchanged |
| Move into `Destination` in same volume | `000000a5865c0000` | `000000a5865c0360` |
| Delete/recreate at identical moved path | `000000a5865c0060` | `000000a5865c03c0` |

File size, modification time, creation time, and access time survived the short rename and move. The recreated file had different bytes (`first` → `other`) but the same path, length `5`, mtime `2026-10-01T23:11:36Z`, creation time `2026-10-01T23:11:34.49Z`, and access time. Creation time already matched before the harness explicitly restored all three times. Six further same-size/same-mtime recreations returned indices ending `00c0`, `0120`, `0180`, `01e0`, `0240`, `02a0`. A separate equal-size/equal-mtime replacement moved over the path retained its own index `000000a5865c0300`, distinguishing that replacement; its creation time became the destination's previous creation time.

The recreated directory accepted explicit time restoration, but its original mtime `2026-10-01T23:11:34.53Z` became `2026-10-01T23:11:36Z`; creation/access matched. Thus that directory case did **not** achieve identical full timestamps.

The follow-up short-to-long filename rename changed the index even within the same parent: `000000a586600000` → `000000a586600060`. After deleting it and waiting 16 seconds, recreation returned `000000a586600120`, with a new creation time. This wait is an experimental condition, not a proven explanation of Windows caching.

Index reuse was also demonstrated: after the original experimental tree was removed, the distinct retained `RemountEvidence/Stable.bin` received `000000a586540000`, previously used by the deleted `Experiment/Original.bin`. Consequently even volume serial plus legacy index is not an immortal object identifier. Same-path reuse with identical restored metadata was not demonstrated and remains unresolved.

The volume root returned index `0000000000000000`, directory attributes `00000010`, and all three times `1980-01-01T04:00:00Z`. It supplies no nonzero legacy root identifier. The retained directory's baseline was `000000a5864c00c0`; the retained file's baseline was `000000a586540000` with mtime `2026-10-01T23:11:38Z`. Both remained equal in same-mount reopen controls and after the single actual manual remount.

**Proven:** path + size + mtime, even with creation/access times added, did not distinguish the tested file replacement. Legacy index distinguished the sampled replacements but changed under some renames/moves and was reused after deletion. No stable native directory identity suitable for general Source-root continuity has been established.

## Case and path behavior

`MixedCaseDirectory/Example.jpg` was preserved by enumeration, `toRealPath`, `toRealPath(NOFOLLOW_LINKS)`, canonical File paths, and native final paths. Upper/lowercase variants of the file, directory, and drive letter opened successfully; `Files.isSameFile` returned true, and legacy identities matched.

Windows Java `Path.equals` returned true for the tested case variants. This is a Java host-path comparison observation, separate from the filesystem's successful alternate-case lookups. It does not change the project's exact-spelling `LocationPath`/`lk1` catalog comparison rules or prove equivalence for every Unicode case.

For later design: retain enumerated spelling for display; host-case comparison and real-path resolution can recognize sampled aliases/overlaps. Matching path spelling or case alone cannot prove stale-file or Source continuity. No global catalog-key folding was introduced.

## Timestamp round trips

All table values are UTC and share `2026-01-15T12:34:`. The harness set creation/access/mtime together, closed the setter's handle, then read NIO attributes and legacy native FILETIMEs through new handles.

| Requested time suffix | Creation returned | Modification/access returned |
| --- | --- | --- |
| `56.000000000` | `56.000` | `56.000` |
| `56.000000001` | `56.000` | `56.000` |
| `56.009999999` | `56.000` | `58.000` |
| `56.010000000` | `56.010` | `58.000` |
| `56.019999999` | `56.010` | `58.000` |
| `56.123456789` | `56.120` | `58.000` |
| `56.999999999` | `56.990` | `58.000` |
| `57.000000000` | `57.000` | `58.000` |
| `57.001000000` | `57.000` | `58.000` |
| `57.999999999` | `57.990` | `58.000` |

NIO and native reads agreed. Direct native SetFileTime reproduced the sampled `56 + 1ns`, `56.009999999`, `56.010`, `56.123456789`, and `57.123456789` results; conversion to the native request itself truncates below 100 ns. The two-second modification/access rounding was therefore reproduced without NIO setting the times. Observations show creation truncation to 10 ms and file mtime/access ceiling to a two-second boundary for these requests; they do not establish a universal precision contract for every operation.

Ordinary new-directory creation exposed fractional creation/mtime at 10 ms steps, whereas explicitly restoring directory mtime in the replacement test rounded it to two seconds. Do not flatten these context-dependent observations into one blanket exFAT precision claim.

Reading the 4-byte timestamp file did not alter any observed time, immediately or after 1.1 seconds. Same-length writing changed file mtime/access to the current rounded time and retained creation time/index. `Files.copy(..., COPY_ATTRIBUTES)` retained the sampled mtime but returned new creation/access times; the default copy also had new creation/access values. Default-copy mtime equaled the source's recent write time within the same coarse interval, so that case does not prove default-copy mtime preservation. The retained file's mtime survived the single actual remount; delayed access updates, timezone/DST changes, persistence of the timestamp-setting experiment across remount, and reboot behavior remain untested.

## Reparse objects and separation

- File and directory symlink attempts located on exFAT failed independently with `Incorrect function`. No symlink target was traversed through a successfully created exFAT symlink.
- `mklink /J` on exFAT exited `1`: `Local NTFS volumes are required to complete the operation.` No junction was created there.
- NTFS file/directory symlink controls pointing into exFAT both failed with `A required privilege is not held by the client`. No privilege or Windows configuration was changed. Successful symlink alias behavior remains untested.
- An NTFS junction pointing into exFAT succeeded twice (`mklink /J` exit `0`). NIO no-follow observation on the junction reported directory=true, other=true, symlink=false; native no-follow attributes were `00000410`, reparse tag `a0000003`, with NTFS native ID/serial. The existing NTFS adapter correctly reported reparse=true and no identity.
- Following that junction with `toRealPath` returned the `Z:` target, while `toRealPath(NOFOLLOW_LINKS)` retained the `D:` alias. `Files.isSameFile(alias,target)` returned true. Opening an ordinary descendant via the alias returned the exFAT serial/index and the same API limitations as direct exFAT paths.
- Only the created objects and attempted mechanisms were qualified. These failures do not prove that every conceivable exFAT reparse/alias mechanism is impossible.

The existing **unchanged** `SessionSourceBoundary` was invoked against disposable directories representing validated Session roots; these were structural checks, not exFAT Session/application acceptance. Both runs rejected equal exFAT paths, alternate casing, parent, missing child, drive-root ancestry, the cross-drive NTFS junction into the exFAT Session location, and a missing child below that junction. Separate sibling/different-volume paths were allowed.

For later authority design, a drive-letter difference is insufficient to establish separation before alias resolution. Real-path/nearest-existing-ancestor resolution handled the demonstrated junction, casing, missing leaf, and root cases already. No change to production separation is suggested by these samples alone; concurrent alias replacement, untested alias types, drive reassignment, and separation across remount remain unresolved. The retained identity remount check did not rerun the overlap experiments.

## Actual VeraCrypt remount and fixture cleanup

Windows exposed an ordinary exFAT drive with the VeraCrypt-specific DOS device name. Native handle APIs worked or returned the documented failures normally during the mounted experiments. No unencrypted exFAT control volume was tested, so these results cannot separate general exFAT behavior from VeraCrypt-specific effects.

The user completed one actual manual VeraCrypt dismount/remount of the same exFAT volume at `Z:` and reported that the post-remount diagnostic exited `0`. This continuation inspected the actual `backend/target/exfat-remount.log` and preserved it as raw evidence. No execution timestamp was emitted by the harness, and none has been inferred or added. The manual dismount was performed by the user, not by the diagnostic.

| Post-remount observation | Result |
| --- | --- |
| Device / filesystem | `\Device\VeraCryptVolumeZ` / exFAT, unchanged |
| Volume serial / GUID | `98d16f05` / `\\?\Volume{5b22bd93-5d21-11ec-bf63-7085c24c9111}\`, unchanged |
| Root legacy identity | serial `98d16f05`, index `0000000000000000`, equal to baseline |
| Retained directory | index `000000a5864c00c0`, equal to baseline |
| Retained file | index `000000a586540000`, equal to baseline; 16 bytes |
| Retained content / mtime | `expectedBytes=true`, `mtimeEqual=true`; mtime `2026-10-01T23:11:38Z` |
| NIO file keys | still null |
| FileIdInfo / FileAttributeTagInfo | still unavailable, error `87` |
| Existing NTFS adapter | still cannot establish exFAT identity; attribute query fails with error `87` |

**Empirical fact:** these sampled volume/root/directory/file values, retained bytes, and file mtime survived one real dismount/remount at the same drive letter. **Design implication:** this is useful continuity evidence for that sample, not proof of a stable legacy exFAT authority identity. Earlier movement/long-rename changes, index reuse, zero root index, null NIO keys, unavailable FileIdInfo, and same-metadata replacement remain decisive limitations. General remount/volume-replacement continuity and the authority decision remain unresolved.

The actual resume used this classpath and command from `backend`:

```powershell
$probeClasspath = "target/classes;$env:USERPROFILE/.m2/repository/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar;$env:USERPROFILE/.m2/repository/net/java/dev/jna/jna-platform/5.17.0/jna-platform-5.17.0.jar"
java -cp $probeClasspath diagnostics/WindowsExfatProbe.java resume 98d16f05 > target/exfat-remount.log 2>&1
$LASTEXITCODE
```

`resume` compared the stored root/directory/file legacy IDs and file mtime/content, with all three identity comparisons true. The device, type, serial, and GUID also matched the earlier log. The distinct earlier same-mount resume remains labeled separately and is not used as remount evidence. No different-letter, replacement-volume, reboot, or repeated-remount experiment was performed.

After recording and verifying the actual remount evidence, this continuation ran the existing guarded cleanup with that classpath:

```powershell
java -cp $probeClasspath diagnostics/WindowsExfatProbe.java cleanup 98d16f05
```

Cleanup exited `0` and printed `Removed marked remount evidence and fixture root`. The fixture root was then verified absent with `Test-Path`. Its output is preserved under `===== exfat-cleanup.log =====`. The source launcher used the existing Maven cache and compiled adapters; no Maven compile, package build, or dependency installation was needed. Since the retained baseline has been removed, `resume` can no longer operate on this completed fixture.

## Proven limits, candidates, and unresolved design

- No returned NIO file key or working FileIdInfo identity is available from the sampled objects. The current NTFS adapter cannot serve as an exFAT adapter unchanged, and its guarantees were preserved.
- Legacy serial/index can detect the sampled same-metadata replacements and support conservative same-mount observations. It is **not** a stable rename/move identity or a permanently unique object ID. Its safety for an operation-local before/after check under reuse races remains unqualified.
- A later design could investigate legacy handle observations combined with fresh root/volume/type/path checks and fresh byte validation, plus explicit continuity invalidation after ambiguous root/volume changes. These are candidates for further design/experiments, not approved or implemented authority.
- Creation time is mutable and was also inherited on immediate replacement; adding it to path/size/mtime does not repair the demonstrated replacement-detection failure. Recomputed content evidence can distinguish the tested differing bytes; it does not itself prove physical-file or Source identity. No content hash experiment or cache policy was implemented here.
- Still unresolved: general continuity beyond the one sampled remount, same-path legacy-index reuse with identical metadata, directory index recycling/long directory renames, held-handle identity during concurrent replacement, long-running access-time updates, other Windows/exFAT hosts, other alias types and successful symlinks, serial/GUID collision or clone behavior, another drive letter, and comparison against ordinary unencrypted exFAT. Source continuity and cached-analysis reuse need a separate design decision.

## Commands and results

From `backend`, using the classpath above (the executed paths used this machine's literal `C:/Users/sears` Maven cache):

| Actual diagnostic command | Result |
| --- | --- |
| `java -cp $probeClasspath diagnostics/WindowsExfatProbe.java run 98d16f05` | Exit `0`; initial lifecycle/case/timestamp/reparse/separation observations and retained baseline |
| `java -cp $probeClasspath diagnostics/WindowsExfatProbe.java followup 98d16f05` | Exit `0`; long rename, 16-second delayed recreation, native timestamp setter, NTFS symlink controls, repeated junction/separation observations, reopen controls |
| `java -cp $probeClasspath diagnostics/WindowsExfatProbe.java resume 98d16f05` | Exit `0`; separate process on the same mount; all three ID comparisons and file mtime/content checks true |
| `java -cp $probeClasspath diagnostics/WindowsExfatProbe.java resume deadbeef` | Java exit `1`, expected guard rejection reporting actual `98d16f05`; no fixture operation |
| `java -cp $probeClasspath diagnostics/WindowsExfatProbe.java resume 98d16f05 > target/exfat-remount.log 2>&1` | User-run after the actual manual dismount/remount; reported exit `0`; inspected log has all three identity comparisons and file mtime/content checks true |
| `java -cp $probeClasspath diagnostics/WindowsExfatProbe.java cleanup 98d16f05` | Continuation exit `0`; guarded fixture cleanup succeeded |
| `Test-Path -LiteralPath 'Z:\media-compare-authority-test'` | `False` after cleanup |

Baseline review: `git rev-parse HEAD` returned `59cc9b4e944c6f4c29a65c7ec8ac1c8002c4e27c`. `git diff --name-status dab40379ca61d2acaeb743a221ba4bc9d5e0dc60 59cc9b4e944c6f4c29a65c7ec8ac1c8002c4e27c` listed only the two documentation changes noted above. `git diff --exit-code` for the same baseline pair with `-- backend frontend` exited `0`, proving no relevant code change; no affected diagnostics or unrelated experiments were rerun. Appending the actual remount output was verified by exact text comparison, preserving earlier evidence unchanged.

Original preflight/read-only checks at `dab4037`: `.NET DriveInfo`, `Get-PSDrive -Name Z`, `Get-Process -Name VeraCrypt`, native QueryDosDevice/GetVolumeInformation/GetVolumeNameForVolumeMountPoint, Java version, Windows registry/build, repository reads, `git rev-parse HEAD`, and Git status succeeded. CIM queries were denied; native/.NET checks supplied the volume evidence instead. The earlier request pointed to an unavailable `api-test` checkout path and an object then absent from that checkout. Those historical checks do not describe the current intentional `ai-projects` path or approved `59cc9b4` baseline. An attempted PowerShell script wrapper was blocked and removed; Java was invoked directly without changing execution policy.

Final `git diff --check` passed after this continuation. The user performed the manual remount/resume; this continuation reviewed baseline differences, preserved the actual output, and ran guarded cleanup. No Maven/JUnit/backend suite, package build, or frontend lint/build/test gate was run. The Java source launcher compiled and executed the harness; the intentionally unavailable native/symlink/junction cases are observations, not passed support tests. The full project validation gate was **not** run or claimed. No commit or push was performed.
