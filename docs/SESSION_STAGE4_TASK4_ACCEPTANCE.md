# Session Stage 4 Task 4 — acceptance report

The Part 1 sections below preserve the 2026-10-29 task-time evidence and review state. Part 1 was subsequently independently approved and pushed at `ee1911abbe16798ca2d9c739fd37474d72addb90`. See the separate [Part 2 native Windows qualification](#part-2--native-windows-qualification) for the current execution and disposition; neither part grants final Stage 4 acceptance.

Latest local checkpoint: [Windows blocker corrections and resumed qualification](#windows-blocker-corrections-and-resumed-qualification-2026-10-30). The initial Part 2 failures below remain historical evidence; the follow-up records their authorized corrections, reruns and remaining limits.

Date: 2026-10-29. Scope: integrated Session startup/Open/New safety review, focused corrections, macOS acceptance and Windows qualification planning. **Recommendation: Stage 4 must remain pending.** The macOS scenarios below pass; native Windows Session workflow qualification, independent review of these changes and final owner acceptance are outstanding. No final acceptance is inferred from tests or the existing pushed commits.

## Baseline and environment

- Active clone: `media-compare-dev`, branch `main`, approved Task 3 SHA `684caf85e4504627a1e8ece647e66ba8a052a3c9`. HEAD, local `origin/main` and a fresh `git ls-remote origin refs/heads/main` matched; the initial working tree was clean. The initial remote lookup failed sandbox DNS; the permitted read-only retry succeeded. Task 3's uncommitted/unpushed statements in living documentation were stale and are reconciled with this verified baseline.
- Actual host: macOS 26.6.2 (25G83), Java 21.0.10, Node 24.17.0, Chrome 156.0.8078.73, Python 3.12.14. Real integration used the rebuilt executable JAR, a copy with spaces in its filename, current production React build, numeric loopback and a separate headless Chrome profile. Application storage checks admitted the disposable local APFS paths.
- Only generated temporary fixtures were used. No personal data Session was opened/modified. All fixture folders are retained, including failed harness runs; no automatic deletion/cleanup was added. Neither protected original production workspace was inspected or modified. `WindowsExfatSupport.PRODUCTION` remains `false`.
- Task 4 corrections, tests and documentation are local and uncommitted, awaiting independent summary and actual-diff review. Nothing was staged, committed or pushed. No new milestone began.

## Ranked findings and corrections

| Severity | Finding and reproducible evidence | Correction / remaining gate |
| --- | --- | --- |
| High — unsupported catalog storage | Shared Windows `requireSessionStorage` checked NTFS type alone. `WindowsVolumeProbe.classify` preserves that profile for a native remote/unknown drive type; thus Open/CLI could pass the storage gate even though New's additional evidence check refused it. Catalog locking/SQLite must not rely on this unsupported route. A deterministic native-observation fixture reproduced acceptance of an unknown drive before the fix; the same unchecked branch covered remote type 4. See `WindowsHostFileSystem.java:63`, `WindowsVolumeProbe.java:43`, `Session.java:40`. This is code/fixture evidence, **not a real SMB or Windows reproduction**. | Shared storage admission now requires ordinary drive syntax, native local fixed/removable type 2/3, NTFS and the matching drive root. The regression at `WindowsHostFileSystemTests.java:81` rejects remote/unknown types, absent native evidence and nested volume-root mismatch, and permits local fixed/removable fixtures. Native Windows local/remote/UNC checks remain mandatory. No Source profile/identity rule changed. |
| Medium — retry refresh loses a still-needed failure | After a failed RETRY_OPEN at t=0, reserve another retry at t=9 minutes and refresh at t=11. The root and new reservation survive, but the previous latest failure expired; React reads `root.latestAttemptId` before discovering the RESERVED retry and received 410. Deterministic monotonic-clock regression failed before correction with `OPERATION_EXPIRED`. See `SessionCreationOperations.java:244` and `creationWorkflow.ts:86`. This stranded refresh recovery; it did not replay creation or lose Session data. | Retain the latest failed retry while an unexpired RESERVED retry references it (`SessionCreationOperations.java:253`). Submission, supersession or reservation expiry ends this dependency pin; no retention chain or new journal is introduced. Regression at `SessionCreationOperationsTests.java:285` checks readable root/failure/reservation, no automatic launch and eventual complete expiry. |

Both fixes pass focused regressions. No additional concrete defect requiring an architectural/security/filesystem-authority redesign was identified within the reviewed trusted-local contract. This is a scoped review result, not proof against arbitrary OS/filesystem faults or hostile same-user mutation.

## Integration/safety review

The review read the Session creation/manifest/storage implementation; management browser, security, creation API/history/admission, supervisor, child command/control and forwarding; catalog ownership/configuration; React startup/browser/name/creation controller and connection clients; relevant tests; README, STATUS, DECISIONS, ARCHITECTURE and DATA_MODEL. Historical Stage 1–3/Task 1–3 evidence in STATUS remains historical evidence rather than newly claimed acceptance. Unrelated media/indexing/exFAT acceptance suites were not repeated.

| Boundary | Conclusion and code/evidence |
| --- | --- |
| Parent, storage and identity | Creation requires an existing absolute parent, rejects a linked selected parent and rechecks canonical ancestor/child directory identities, host profile and storage around writes (`SessionCreationService.java:49`, `SessionDirectoryEvidence.java:23`). Trusted ancestor aliases resolve under existing policy; retargeting is rejected. APFS real directory/link checks and controlled Windows native-observation/reparse cases passed. Finder alias files are ordinary non-directories, not interpreted as directory authority. Actual Finder alias UX and Windows junction/native behavior remain separate checks. |
| Collision and original-file preservation | Only `Files.createDirectory` plus a definite observed collision advances the suffix; manifest publication uses CREATE_NEW/NOFOLLOW_LINKS in an empty existing child (`SessionCreationService.java:64`, `Session.java:95`). Tests cover file/directory/live/dangling-link collisions, suffix exhaustion, permission/write failures and observed route replacement. Real UI preserved the colliding file and sibling original sentinel exactly. No creation error path deletes, overwrites, recreates a missing child or cleans up a partial result. |
| Shared admission and actual completion | `SessionLifecycleAdmission.java:15` excludes Open/New during admitted filesystem work and while any child lives, including FAILED/alive. Store and supervisor synchronization does not hold a supervisor monitor across creation IO. Replayed inputs remain immutable. Deterministic concurrency, active-worker expiry, interruption and uncooperative shutdown tests passed; real HTTP replay and real child ownership conflict/exit passed. |
| Catalog ownership and isolation | Child startup validates Session structure, takes OS catalog ownership before datasource/Flyway, and emits READY only after initialization (`CatalogChildApplication.java:29`, `CatalogConfiguration.java:58`, `CatalogOwnership.java:36`). Cross-JVM ownership tests passed. The real lock fixture caused OWNERSHIP_FAILED, retained the Session and required explicit retry after actual exit. Public generation and private launch credential remain separate. |
| Shutdown and stale launches | Supervisor `route` requires exact generation plus RUNNING/alive; replacement waits for actual exit (`CatalogProcessSupervisor.java:97`). STOP/EOF requests ordinary cleanup; no force-kill fallback exists. Real manager shutdown/reopen and a real unexpected child SIGTERM passed; helper JVM tests cover deadlines, failed/live and pipe behavior. Stuck OS/native IO and abrupt termination remain outside an orderly-completion guarantee. |
| Lost responses, refresh, expiry/restart | `creationWorkflow.ts:60` observes retained IDs without executing; reserve/submit are explicit. Unit/browser transport checks cover lost reservation/execution responses, active/retry refresh, storage failure, ambiguity, expired/foreign-runtime handles, partial/uncertain creation and exact latest retry. Real refresh during child initialization/retry and real management restart reject/recovery checks passed. No operation journal or automatic replay is added. |
| Readiness, routing and recovery UI | `sessionCreations.ts:145` requires a successful created operation, exact matching current generation and RUNNING/alive in operation and management status. `ApplicationStartup.tsx:42` attaches before New-only Sources navigation. Existing Open stays on its current route; known same-generation deep-link refresh survives. `creationWorkflow.ts:183` keeps manual Open subject to lifecycle/admission checks; the returned manual launch alone can attach. Real fallback passed with only discovery responses simulated. |
| Browser security and persistence | Creation routes pass the existing exact numeric Host, Origin/Fetch Metadata and capability checks (`LocalApiSecurity.java:37`, `ManagementServer.java:86`). Capability/security and strict bounded UTF-8/form tests passed. `CatalogApiForwarder.java:22` captures one ready launch route/credential and rechecks it before returning headers. Browser session storage holds operation/runtime/dismissed IDs and catalog generation, never paths, names or credentials; existing preview capability remains HttpOnly/SameSite=Strict. No new credential or browser persistence scheme was introduced. |

## Acceptance matrix

PASS is limited to the evidence named. Simulated exceptional conditions do not qualify native filesystem/process behavior.

| Acceptance area | Result | Evidence / limit |
| --- | --- | --- |
| New through real React UI; Home parent browser, editable suggestion, Unicode/spaces/punctuation | PASS | Real packaged Chrome run; prior Task 3 harness rerun on current pre-correction build, plus final new harness on corrected JAR. |
| Correct manifest and child-owned catalog initialization | PASS | Exact two-field manifest, all 10 successful Flyway migrations, SQLite integrity_check=ok, empty foreign_key_check; inspection after actual shutdown through an encoded read-only URI. |
| Sources only after matching live catalog readiness | PASS | Real lock failure/initialization mounted no Sources requests; explicit successful retry navigated to Sources with the exact RUNNING/alive generation. Historical/mismatched launch exclusion also passed deterministic transport checks. |
| Existing Session Open and data preservation | PASS | Real shutdown/reopen into a fresh manager preserved registered Source ID/name/path and an unknown Session file, used a new generation and stayed at home. This is representative catalog-data preservation, not an exhaustive populated-media-library replay. |
| Collisions and unrelated originals | PASS | Real UI collision with file and sibling original sentinel; prior real directory collision; unit live/dangling symlinks, exhaustion and write/replacement failure cases. |
| Refresh during creation/startup and after readiness | PASS (mixed evidence) | Real child OPENING/retry refresh and Sources/Library refresh. CREATING and partial/uncertain phases use deterministic unit/transport cases; no timing claim that the brief real CREATING phase was caught by browser refresh. |
| Duplicate click/delivery, concurrent admissions | PASS (mixed evidence) | Real double click produces one reservation/execution; eight concurrent real HTTP replays retain original identity/generation and create no replacement; stale failed-attempt retry returns 409. Separate controlled in-flight IO tests cover simultaneous Open/New and distinct creation requests, without relying on process-startup timing. |
| Opening failure, actual child exit, explicit Retry Opening | PASS | Real POSIX record-lock conflict after manifest publication causes OWNERSHIP_FAILED; Session retained, actual child exit observed, explicit retry reopens same folder after test lock release. Real child exit after readiness unmounts old-document routes and makes forwarding return 503. Existing real corrupt-catalog initialization/retry API test rerun passed. |
| Lost reservation/execution response and failed/live retry exclusion | PASS (simulated) | 52-test frontend gate and Task 3 real-Chrome/mocked-transport harness rerun; actual failure/exit separately qualified above. No automatic creation replay. |
| Unavailable/stale/expired/uncertain results | PASS (deterministic); abrupt-loss qualification UNVERIFIED | Unit clock, injected IO/transport and runtime identity checks, including the new retry retention regression. Actual management restart rejects an old creation handle with 409. No destructive disk-loss/kill-during-write experiment was performed. |
| Manual Open when creation discovery fails | PASS (mixed evidence) | New harness replaces only discovery HTTP responses with 503; real React requires acknowledgment, disables New, sends exactly one real Open, waits for its actual child and attaches at home. No creation POST. Detail-poll/liveness restrictions covered by focused unit tests. |
| Native macOS permission denial / every alias or external-volume configuration | UNVERIFIED natively | Permission and route replacement failures are controlled tests; APFS temporary storage is real. External/mounted/network-volume UX, Finder alias navigation and manual visual/keyboard usability were not qualified here. Network storage gains no support. |
| Windows/NTFS Session workflow | UNVERIFIED natively | Windows observations/drive/reparse fixtures are simulated on macOS. One real-Windows-only filesystem test skipped. Earlier native NTFS Source/preview acceptance is valid history, not acceptance of this new management/New/Open workflow. |
| Final independent review and owner acceptance | UNVERIFIED / outstanding | Current corrections/report await review; Stage 4 stays incomplete. |

## Commands and retained evidence

All commands below were run. From repository root unless a working directory is stated. No dependency installation or system-software change was performed. Local sockets/child JVM/Chrome runs received execution permission; Mockito uses the already-cached premain agent to avoid sandbox self-attachment.

```sh
git status --short --branch
git rev-parse HEAD origin/main
git ls-remote origin refs/heads/main
sw_vers
java -version
node -v
'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' --version
python3 --version

node --test frontend/tests/sessionCreation.test.mjs frontend/tests/sessionStartup.test.mjs frontend/tests/catalogConnection.test.mjs frontend/tests/catalogOperations.test.mjs frontend/tests/metadataWorkflow.test.mjs > /private/tmp/media-compare-stage4-task4-frontend.log 2>&1
npm --prefix frontend run build > /private/tmp/media-compare-stage4-task4-build.log 2>&1
npm --prefix frontend run lint > /private/tmp/media-compare-stage4-task4-lint.log 2>&1
node --check frontend/tests/sessionManagement.acceptance.mjs

# Earlier disposable harness, rerun against current Task 3 frontend/build before backend corrections:
node /private/tmp/media-compare-stage4-task3-smoke.mjs > /private/tmp/media-compare-stage4-task4-browser-baseline.log 2>&1

# New reviewable harness, final run against the rebuilt corrected JAR:
MEDIA_COMPARE_CHROME='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' node frontend/tests/sessionManagement.acceptance.mjs > /private/tmp/media-compare-stage4-task4-acceptance.log 2>&1
git diff --check
```

From `backend/`, initial focused regression: 102 tests, all passed. The final broader focused command below adds the two regression cases plus host/ownership coverage: **117 tests, 116 passed, zero failures/errors, one skipped** (`HostFileSystemTests.realWindowsNtfsFileCanBeCheckedWhenRunOnWindows`). Frontend: **52 tests, all passed**, production build and lint passed. Packaging passed after both corrections, before the final browser run.

```sh
# Initial gate (before corrections):
./mvnw -o -q '-Dtest=SessionCreationOperationsTests,SessionCreationApiTests,ManagementServerTests,ManagementSecurityTests,ManagementWorkflowTests,CatalogProcessSupervisorTests,FilesystemBrowserTests,SessionCreationServiceTests,SessionTests,SessionFilesystemPolicyTests,SessionStartupTests,SessionSourceBoundaryTests,SessionSourceAliasTests,SessionSourceIsolationTests' '-DargLine=-javaagent:/Users/chris/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar' test > target/stage4-task4-regression.log 2>&1

# Each failed before its correction; the failure logs are intentionally retained:
./mvnw -o -q '-Dtest=SessionCreationOperationsTests#reservedRetryRetainsLatestFailedAttemptNeededForRefreshUntilReservationExpires' '-DargLine=-javaagent:/Users/chris/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar' test > target/stage4-task4-retention-before.log 2>&1
./mvnw -o -q '-Dtest=WindowsHostFileSystemTests#sessionStorageRequiresQualifiedLocalNtfsDrive' test > target/stage4-task4-storage-before.log 2>&1

# Final gate after corrections:
./mvnw -o -q '-Dtest=SessionCreationOperationsTests,SessionCreationApiTests,ManagementServerTests,ManagementSecurityTests,ManagementWorkflowTests,CatalogProcessSupervisorTests,FilesystemBrowserTests,SessionCreationServiceTests,SessionTests,SessionFilesystemPolicyTests,SessionStartupTests,SessionSourceBoundaryTests,SessionSourceAliasTests,SessionSourceIsolationTests,WindowsHostFileSystemTests,HostFileSystemTests,CatalogOwnershipTests' '-DargLine=-javaagent:/Users/chris/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar' test > target/stage4-task4-final-regression.log 2>&1
./mvnw -o -q package -DskipTests > target/stage4-task4-package.log 2>&1
```

The repository acceptance harness uses Chrome DevTools Protocol through Node's built-in WebSocket and Python's standard-library fcntl/SQLite; no dependency is added. It copies the JAR, creates a separate browser profile and retains everything. Its real lock fixture acquires a record lock after the new manifest exists and releases only its own handle. It never removes a lock file/database, repairs a catalog or force-kills a process. Child-exit testing signals only the verified catalog child of its own generated manager. The simulated outage intercepts only the discovery route. The script deliberately refuses non-macOS execution.

Final native fixtures and per-process logs: `/private/var/folders/pz/b4070b651bvfc70h7cgvj8cr0000gn/T/media-compare-stage4-task4-yWONSd`. The baseline harness reports its separate fixture path in its log. These are local disposable evidence, not committed artifacts or guaranteed permanent storage. The repository script and commands make the new scenarios repeatable with fresh fixtures.

Initial new-harness runs exposed test-code issues (temporary-path canonicalization, signal-exit recognition, waiting for asynchronous OPENING, and percent-encoding SQLite paths containing `#`/`%`). These were corrected; their partial runs do not establish final acceptance. The first cleanup assertion left two identified test processes; both received ordinary SIGTERM and exited. Later runs completed cleanup. The final run passed all scenarios and browser-exception assertions; no application failure is hidden as a harness success. Final process inspection found no remaining matching acceptance processes. All 58 local documentation links/anchors and new-file whitespace checks passed, along with `git diff --check`; the Git index remains unchanged.

## Windows/NTFS checklist — separate execution required

Use an explicitly authorized Windows host, installed Java/Node/browser and a disposable folder on a verified local NTFS drive. Record OS/JDK/browser versions, filesystem/drive classification, baseline SHA plus reviewed Task 4 diff/build identity, exact commands, screenshots/results, processes and any skipped privileges. Never use either protected original production workspace or activate exFAT. Do not run the macOS-only harness and call its fixtures native acceptance.

1. Build React and package the reviewed JAR; run the same 17 focused suites using `mvnw.cmd`, a verified cached Mockito agent when required, and the five-file Node gate. The Windows-only test must actually execute. Do not install missing software/dependencies without authorization. Example PowerShell setup/commands from the repository (these examples were **not executed here**):

   ```powershell
   $fixtureRoot = Join-Path $env:TEMP ('media-compare-stage4-' + [guid]::NewGuid())
   New-Item -ItemType Directory -Path $fixtureRoot
   node --test frontend/tests/sessionCreation.test.mjs frontend/tests/sessionStartup.test.mjs frontend/tests/catalogConnection.test.mjs frontend/tests/catalogOperations.test.mjs frontend/tests/metadataWorkflow.test.mjs
   npm --prefix frontend run build
   npm --prefix frontend run lint
   # From backend/, with the cached agent path verified first:
   $agent = Join-Path $env:USERPROFILE '.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar'
   .\mvnw.cmd -o -q '-Dtest=SessionCreationOperationsTests,SessionCreationApiTests,ManagementServerTests,ManagementSecurityTests,ManagementWorkflowTests,CatalogProcessSupervisorTests,FilesystemBrowserTests,SessionCreationServiceTests,SessionTests,SessionFilesystemPolicyTests,SessionStartupTests,SessionSourceBoundaryTests,SessionSourceAliasTests,SessionSourceIsolationTests,WindowsHostFileSystemTests,HostFileSystemTests,CatalogOwnershipTests' "-DargLine=-javaagent:$agent" test
   .\mvnw.cmd -o -q package -DskipTests
   java -jar target/media-compare-0.0.1-SNAPSHOT.jar manage ../frontend/dist 0 --no-browser
   ```

2. Through the actual numeric-loopback browser URL, enumerate available drives/Home, enter a local NTFS parent, create a Unicode/space/punctuation Session, verify manifest/catalog/Sources readiness and exact generation, then preserve a representative Source/catalog record across ordinary shutdown and Open. Refresh during startup, retry and existing deep links; test a fresh tab and browser storage disabled. Double click and concurrently replay the same operation; ensure only one folder/launch. Verify browser auto-open separately without `--no-browser`.
3. Qualify the corrected local-storage rule on a fixed NTFS drive and, if available, a local removable NTFS drive. Verify explicit refusal of actual mapped network drives, UNC/device/extended paths, unsupported FAT/exFAT and unavailable/disconnected/unknown routes. If a device/share is unavailable, mark that scenario UNVERIFIED. Do not emulate a native pass by changing os.name or mocking native APIs. NTFS directory mount points with mismatched drive root should fail closed.
4. Exercise file/directory/case-insensitive collisions, live/dangling symlinks where privileges permit, selected-parent/child junctions and ancestor aliases/replacement. Confirm no target mutation. Record inability to create symlinks as a coverage gap; junctions are not a substitute. Confirm native volume serial/file IDs and reparse checks succeed/fail as intended with actual Windows paths/providers.
5. Check Windows device/reserved names, trailing spaces/dots, Unicode and component byte/unit limits, suffix near component limit and full paths near/beyond provider limits. Failure must remain bounded, preserve attempted locations and never try a new suffix after an uncertain write. Do not assume component validation guarantees full-path support. Include JAR/frontend and Session paths containing spaces and non-ASCII characters; verify direct ProcessBuilder invocation without shell quoting dependence.
6. Use disposable ACL-denied parent/manifest fixtures and safely controlled partial creation. Record exact ACL setup/restoration. Verify directory/manifest failures preserve all unrelated sentinels and make no automatic cleanup/replay. Original media must remain separate; Source/Session overlap rejection must still hold.
7. Use a real competing owner to force opening failure, wait for actual child exit and explicitly Retry Opening the exact same Session; also exercise corrupt disposable catalog failure without automatically deleting/repairing it. Verify a second manager cannot acquire the lock and cannot bypass the failed/live child gate. Unexpected child exit must unmount old routes; stale generations/private credentials must not attach to another child even with port reuse.
8. Qualify stdin STOP/EOF, manager close, startup failure, bounded startup/shutdown deadlines and actual child exit. With approved local tooling, verify directory/native observation handles, SQLite connections and lock ownership close after ordinary success/failure/retry, and that a stopped fixture can be renamed/moved/reopened. Never remove the sidecar to simulate lock release. A timeout/stalled worker must retain admission/ownership and report uncertainty; do not claim forced OS termination cleaned everything up.
9. Repeat lost-response/discovery-outage UI tests with explicitly labelled transport injection while keeping actual Windows startup/storage real. Confirm retained latest-failure/reservation recovery across retention, runtime restart rejection, partial/uncertain no-replay and manual Open after acknowledgment. Timing/clock injection alone does not establish native lifecycle behavior.

## Remaining limits and disposition

- Creation and Open remain pathname based in a trusted local environment. Rechecks detect observed changes, but creation-to-observation/check-to-open gaps and lock-file substitution races are not eliminated by handle-relative filesystem authority. Hard-link catalog aliases are not a supported ownership route. No adversarial filesystem-authority redesign was authorized or attempted; prior documented limits remain binding.
- History is bounded/in memory. Expiry or restart can lose recovery evidence; without retained identifiers there is no reliable reconstruction. A known created Session can be opened manually after inspection. No crash journal, cancellation/rollback, automatic retry, deletion, switching or Recents was added.
- Orderly shutdown cannot guarantee cleanup after abrupt JVM/OS termination, uncooperative workers or stalled native IO. Permission failures, route races, timeouts and exceptional transport conditions have controlled tests; those are not hardware-loss/native-closure acceptance.
- No full unrelated application suite, fresh ffprobe/preview/scan qualification, native Windows execution, external-volume qualification or manual visual/keyboard review was claimed. DATA_MODEL is unchanged. Historical reports were not rewritten to manufacture acceptance.

Submit this report, the two implementation corrections, their regressions, the new acceptance harness and living-documentation reconciliation for independent review. **Stage 4 remains incomplete. Stop for ChatGPT review and explicit next-step authorization; do not stage, commit, push or begin another milestone.**

## Part 2 — Native Windows qualification

Date: **2026-10-30, America/New_York**. **Initial-run disposition: FAIL / INCOMPLETE; further correction, prerequisite authorization and testing required.** This is actual Windows execution, with partial native storage/process evidence. The React/browser acceptance prerequisites were missing, and native regressions found startup/path problems. No production implementation was corrected during this initial run and no final acceptance is inferred. The separately authorized correction follow-up below supersedes its current-blocker state without replacing the evidence.

### Approved baseline, isolation and environment

- The explicitly designated `D:\Dev\projects\ai-projects\media-compare-dev` existed and was verified empty with `Get-ChildItem -LiteralPath ... -Force` immediately before cloning. The user expressly authorized cloning into this empty directory. `git clone --branch main --single-branch https://github.com/topher6835/media-compare.git .` created the fresh checkout directly, without a nested repository. The initial restricted-network attempt could not connect; the authorized host-access retry succeeded using existing authentication. `git rev-parse HEAD origin/main` returned **`ee1911abbe16798ca2d9c739fd37474d72addb90`** twice; branch was `main`, and initial `git status --short` was empty.
- The protected original Windows workspace was never inspected, modified or used as a build/dependency source. All repository work used this checkout. No staging, commit, push, dependency installation, OS security/registry/developer-mode change, Windows privilege elevation or exFAT activation occurred. Host-access retries removed the execution sandbox restriction; they did not request UAC/admin elevation.
- Actual host: **Microsoft Windows 10 Home, 10.0.19045, build 19045, 64-bit**. Read-only `Get-CimInstance Win32_OperatingSystem` and `Get-Volume -DriveLetter C,D` under ordinary host access confirmed both **C: and D: fixed, healthy NTFS**. Restricted-sandbox CIM/volume queries initially returned Access denied; `.NET DriveInfo` independently reported fixed NTFS before the host checks.
- Java: `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe`, **Temurin 21.0.12.1+1 LTS**. Surefire recorded `file.encoding=UTF-8`, `native.encoding=Cp1252`, `sun.jnu.encoding=Cp1252`. These values were observed, not changed.
- The default `node`/`npm` NVM shim reported no active version. The already installed **Node v24.17.0 / npm 11.13.0** were usable by prepending `C:\Users\sears\AppData\Local\Author Software\nvm\installs\v24.17.0` to the test process PATH only. No NVM activation or installation occurred.
- Available browsers: Chrome **156.0.8078.76** at `C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`; Firefox **157.0** at `C:\Program Files\Mozilla Firefox\firefox.exe`. Versions came from executable file metadata. Neither browser was used to qualify the application because the React build could not be established. Python **3.11.3** at `C:\Python311\python.exe` supplied read-only SQLite inspection.
- Cached Maven Wrapper distribution **3.9.16** and Mockito **5.23.0** were present. The agent JAR was verified as an existing 720,687-byte file at `C:\Users\sears\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar` before use. All Maven build/test commands used offline mode; no missing dependencies were fetched.
- Native Session fixtures were uniquely named JUnit directories under `C:\Users\sears\AppData\Local\Temp`, verified by the application's native probe as C: fixed NTFS. The new opt-in test uses `CleanupMode.NEVER` and retains fixtures, diagnostic logs and failed partial locations. It unlinks only its explicitly created junction entries and restores only its fixture ACLs. Existing baseline JUnit tests use their own disposable temporary fixtures. The separate pre-existing native identity suite uses disposable `backend/target/ntfs-acceptance-*` fixtures on D: NTFS. No personal files, real media or existing user Sessions were used.

### Commands and automated results

Exact final frontend commands, from the repository root, with stdout/stderr retained under `backend/target/`:

```powershell
$env:Path = 'C:\Users\sears\AppData\Local\Author Software\nvm\installs\v24.17.0;' + $env:Path
node --version
npm.cmd --version
node --test frontend/tests/sessionCreation.test.mjs frontend/tests/sessionStartup.test.mjs frontend/tests/catalogConnection.test.mjs frontend/tests/catalogOperations.test.mjs frontend/tests/metadataWorkflow.test.mjs *> backend/target/stage4-task4-windows-frontend.log
npm.cmd --prefix frontend run build *> backend/target/stage4-task4-windows-build.log
npm.cmd --prefix frontend run lint *> backend/target/stage4-task4-windows-lint.log
```

All three gate commands exited **1**. `frontend/node_modules` was absent. Two frontend test files could not import `typescript`; the remaining three files ran **21 tests, all passing**. Node reported **23 entries: 21 passes, 2 file-level load failures, zero skips**. This is **not** a 52-test run or a passing focused gate. Build could not find `tsc`; lint could not find `eslint`. The npm `unsafe-perm` user-config warning was incidental. An initial PowerShell `ErrorActionPreference=Stop` attempt stopped at that warning; final build/lint attempts used Continue and obtained the actual exit codes above. No dependency install was attempted. React build, full focused frontend gate, lint and all real browser workflow scenarios are **UNVERIFIED / prerequisite-blocked**.

The wrapper initially failed inside the sandbox with `Cannot index into a null array` before Maven ran. A diagnostic call to the already cached `mvn.cmd` also failed because sandbox Java defaulted to `C:\.m2\repository`. An explicit-cache wrapper retry inside the sandbox had the same wrapper failure. The actual qualifying backend runs below used `mvnw.cmd` with ordinary host access and explicit existing cache paths. These infrastructure failures are not counted as backend test failures and are not claims of application defects.

Exact 17-suite baseline command, from the repository root:

```powershell
$env:MAVEN_USER_HOME = 'C:\Users\sears\.m2'
$agent = 'C:\Users\sears\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar'
.\backend\mvnw.cmd -f backend/pom.xml -o -q '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-Dtest=SessionCreationOperationsTests,SessionCreationApiTests,ManagementServerTests,ManagementSecurityTests,ManagementWorkflowTests,CatalogProcessSupervisorTests,FilesystemBrowserTests,SessionCreationServiceTests,SessionTests,SessionFilesystemPolicyTests,SessionStartupTests,SessionSourceBoundaryTests,SessionSourceAliasTests,SessionSourceIsolationTests,WindowsHostFileSystemTests,HostFileSystemTests,CatalogOwnershipTests' "-DargLine=-javaagent:$agent" test *> backend/target/stage4-task4-windows-regression-host.log
```

Exit **1**. **117 tests: 98 passed, 3 assertion failures, 1 error, 15 skipped.** Original Surefire XML/text/dump evidence was copied to `backend/target/stage4-task4-windows-baseline-reports/` before additional runs.

| Exact suite | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: |
| SessionCreationOperationsTests | 13 | 0 | 0 | 0 |
| SessionCreationApiTests | 7 | 1 | 0 | 0 |
| ManagementServerTests | 7 | 0 | 0 | 1 |
| ManagementSecurityTests | 4 | 0 | 0 | 0 |
| ManagementWorkflowTests | 4 | 1 | 0 | 0 |
| CatalogProcessSupervisorTests | 11 | 0 | 0 | 0 |
| FilesystemBrowserTests | 8 | 0 | 0 | 3 |
| SessionCreationServiceTests | 28 | 1 | 1 | 4 |
| SessionTests | 12 | 0 | 0 | 3 |
| SessionFilesystemPolicyTests | 2 | 0 | 0 | 0 |
| SessionStartupTests | 3 | 0 | 0 | 0 |
| SessionSourceBoundaryTests | 1 | 0 | 0 | 1 |
| SessionSourceAliasTests | 1 | 0 | 0 | 1 |
| SessionSourceIsolationTests | 2 | 0 | 0 | 0 |
| WindowsHostFileSystemTests | 6 | 0 | 0 | 0 |
| HostFileSystemTests | 4 | 0 | 0 | 2 |
| CatalogOwnershipTests | 4 | 0 | 0 | 0 |

The 15 skips comprise one macOS-only check, two POSIX-permission checks and twelve unavailable symlink fixtures. In particular, `HostFileSystemTests.realWindowsNtfsFileCanBeCheckedWhenRunOnWindows` executed its native identity/replacement steps but then aborted at its symlink fixture with “Windows host cannot create a symbolic link.” **Its overall result is SKIP, not PASS.** No privilege or developer-mode workaround was used.

After preserving the baseline reports, these exact commands ran from `backend/` with the same `MAVEN_USER_HOME` and ordinary host access:

```powershell
.\mvnw.cmd -o -q '-Dmaven.repo.local=C:\Users\sears\.m2\repository' package -DskipTests *> target/stage4-task4-windows-package.log
.\mvnw.cmd -o -q '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-Dtest=WindowsNtfsNativeTests,WindowsSessionQualificationTests' '-Dmedia.compare.windows.qualification=true' test *> target/stage4-task4-windows-native.log
.\mvnw.cmd -o -q '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-Dtest=WindowsSessionQualificationTests' '-Dmedia.compare.windows.qualification=true' test *> target/stage4-task4-windows-session-final.log
```

- **Package PASS, exit 0:** `backend/target/media-compare-0.0.1-SNAPSHOT.jar`, 40,983,975 bytes, SHA-256 `d42677bae67599bdf0eb09f162bd6a07bc2f4459ab4d187d21bfc37f768f3440`. `-DskipTests` packaging is not a passing regression gate.
- **WindowsNtfsNativeTests PASS:** all **3** tests actually executed, zero failures/errors/skips. Native volume/file/directory identities and same-size/timestamp replacement detection passed; `fsutil file queryfileid` agreed with the native opaque-byte serialization. The isolated handle probe reported **398 before, 398 after successful opens, 398 after failures**. D: native volume serial was `50b08a4bb08a378c`. This qualifies that adapter/probe, not every application handle under crashes or stalled IO.
- Initial combined native run: **11 tests, 8 passed, 1 failure, 2 errors, zero skips**, exit 1. The new harness initially assumed child diagnostic output was complete UTF-8; actual Windows console bytes/partial writes caused `MalformedInputException`. Its final reader checks ASCII readiness frames without interpreting arbitrary console bytes as UTF-8. No product behavior was changed. The initial native XML was preserved under `backend/target/stage4-task4-windows-native-initial-reports/`.
- Final **WindowsSessionQualificationTests:** **9 tests, 6 passed, 2 failures, 1 error, zero skips**, exit 1. The final Unicode reproduction uses the packaged JAR copied to `Application with spaces.jar`; other real supervisor scenarios run the compiled application classpath. The class is opt-in on Windows with `-Dmedia.compare.windows.qualification=true`; it deliberately retains failing acceptance expectations and all fixtures. It requires the previously packaged JAR. The added tests introduce no framework/dependency and make no production changes.

### Native scenarios and limits

| Required area | Result | Actual evidence / limit |
| --- | --- | --- |
| Fixed local NTFS storage; drive root and identity | PASS | New fixtures on C: verified native driveType=3, NTFS and volumeRoot=`C:\`; native serial `f0349bdc349ba3de` agreed across root/parent/child, with distinct 128-bit directory IDs. D: identity suite separately passed. No injected Windows APIs or changed `os.name`. |
| Real React New/Open, parent browsing and matching-live Sources navigation | UNVERIFIED | Frontend dependencies/build unavailable. No browser was attached to a placeholder frontend. Existing macOS harness was not executed. Backend API/child tests do not establish UI behavior. |
| Native Unicode/space/punctuation creation and manifest | PASS, filesystem phase only | `Café 日本 + # % (2026)!` created the expected collision-resolved folder, exact v1 manifest and no catalog before child startup. Structural `Session.open` succeeded in the creating JVM. Japanese child startup fails separately below. |
| Real New/Open catalog startup with Japanese Session path | FAIL | Both baseline workflow/API cases reach `INVALID_SESSION` after actual child exit; New retains `sessionCreated=true`, `OPEN_FAILED`, exact created location and no implicit second creation. Packaged-child reproduction also fails with `InvalidPathException` after Japanese characters become `??`. |
| Catalog initialization, normal close and data preservation on supported ASCII launch path | PASS, backend scope | Actual child reached RUNNING/alive. Existing-schema `working_set` data `preserved café 日本` survived close, Session move and a fresh-generation reopen; no Source/media was scanned or registered. Read-only Python inspection after shutdown found exact two-field manifest, **10 successful migrations**, `integrity_check=ok`, empty `foreign_key_check`, and the preserved row. |
| File, directory, case-insensitive and junction-name collisions | PASS | Native fixtures preserved original file bytes, occupied-directory notes and junction target sentinel; only the expected ` (001)`/` (002)` child was initialized. No target manifest was written through a junction. Existing unit/native pathname-replacement cases passed except listed portability assumptions. |
| Selected-parent and selected-owned-child reparse rejection | PASS | Native `mklink /J` fixtures refused linked selected parent and linked Session `cache` child. Junction target sentinel stayed unchanged. Junction entries were unlinked in finally blocks, never recursively traversed. |
| Ancestor junction alias and retargeting | FAIL / UNVERIFIED | Existing Windows `unsafeElement` rejects a nonlinked selected parent beneath a junction before creation. Thus the positive generic trusted-ancestor-alias expectation fails and its subsequent retargeting step is **UNVERIFIED**. This is conservative refusal, not observed unauthorized mutation; actual ordinary directory replacement detection passed other native/baseline tests. |
| UNC and unsupported route syntax | PASS for lexical UNC rejection only | Fabricated `\\unavailable-test-host\share\session` was rejected at the ordinary-drive gate without accessing a server. A missing disposable parent was rejected without creating ancestors. This is not an accessible SMB-route test. |
| Extended input syntax | FAIL expectation | `Path.of("\\\\?\\" + disposablePath)` returned the ordinary C: path; shared storage admission then accepted that supported local path. No unsupported volume was admitted and no writes occurred. Raw API-input enforcement requires review before calling this checklist item passed. Device-path input was not separately qualified. |
| Actual accessible UNC/mapped network storage, unsupported filesystem media and directory volume-mount mismatch | UNVERIFIED natively | No controlled accessible share, mapped drive or different-filesystem/mounted-volume fixture was established. Deterministic WindowsHostFileSystemTests rejection is not native network/hardware evidence. No personal network routes or disks were modified. |
| Removable/external drive | UNVERIFIED / outstanding | Not required for this execution; no external drive was needed or tested. No exFAT volume was used. |
| Windows device/reserved names and trailing space/period | PASS for listed portable names | Actual C: service rejected `CON`, `nul.txt`, `LPT¹.log`, trailing ASCII space/dot and over-limit byte/unit inputs before writes. Existing broader name validation passed in the baseline. This does not claim every namespace alias. |
| Component limits and long paths | PASS for bounded failure/preservation; long-path success NOT qualified | 83 `界` characters (249 UTF-8 bytes) succeeded. A 249-character ASCII candidate collided and NIO created the 255-character suffixed child; native identity then failed with error 3. That empty child remains, no manifest and no ` (002)` replay. A **443-unit** existing parent was rejected before child creation. Component validity does not establish native full-path support. The baseline's unconditional long-component success test errored. |
| Actual permission-denied directory/manifest operations | PASS | On fixture ACLs only: prepend a DENY entry for the directory owner with `ADD_SUBDIRECTORY`, then restore the exact original ACL in finally; creation failed with no folder/suffix. At controlled BEFORE_MANIFEST, prepend DENY `ADD_FILE` on the new child; failure preserved its directory, no manifest/suffix. Restore that child's exact saved ACL in finally. Sentinel stayed unchanged and subsequent creation succeeded. No machine/security policy changed. |
| Single-child ownership, foreign test owner and failure/actual exit | PASS, backend native scope | All 11 CatalogProcessSupervisorTests and all 4 CatalogOwnershipTests passed: real child readiness, second-start exclusion, another JVM/owner exclusion, ownership conflict, invalid Session/corrupt catalog initialization and actual exit. Three of four ManagementWorkflowTests passed, including retained operation runtime isolation and invalid-selection/ownership/corrupt-catalog recovery. |
| Explicit Retry Opening without new Session | PASS, backend scope | `SessionCreationApiTests.failedRealCatalogInitializationPreservesCreatedSessionAndCanBeRecoveredWithExplicitRetry` passed with real Windows child exit and explicit retry of the same folder. The existing test explicitly deletes only its disposable corrupt database as test repair; production does not repair/delete it. No lock file was removed to claim release. Browser retry/refresh remains unverified. |
| Concurrency, duplicate delivery, retention and stale handles | PASS for named automated scope; browser/native timing still UNVERIFIED | All 13 SessionCreationOperationsTests passed, including concurrent duplicate submission, shared admission, failed/live exclusion, retained retries, expiry/foreign runtime and no recreation. Their controlled actions/clocks/supervisor mocks are deterministic evidence executed on Windows, not native IO-stall/browser qualification. The actual management interactive-operation replay/restart/stale-generation test passed. New's Unicode happy-path API test failed before its later replay assertions. |
| Shutdown, SQLite/lock/native-handle release and move/reopen | PASS for ordinary tested paths | Existing real STOP and EOF tests passed with exit code 0 and ownership reacquisition. The new fixture closed its real supervisor, moved the Session, separately renamed and restored both `catalog.db` and existing `catalog.db.lock`, then reopened under a new generation and read preserved data. Lock sidecar remained present. Adapter handle counts were stable separately. No force-kill was needed for these successful fixtures. |
| Refresh during startup/retry/after readiness, duplicate-click guard, no automatic replay and acknowledgment/manual Open | UNVERIFIED in native browser | Required React build could not run. Partial dependency-free frontend/client tests passed, but do not substitute for the 52-test gate or actual Windows browser integration. No transport injection was executed here; Part 1's simulations remain historical macOS evidence only. |
| Management restart and expired creation handles in real browser | UNVERIFIED | Deterministic runtime/clock tests and a real catalog-local operation restart check passed, but neither establishes the full management-runtime/browser recovery sequence. |
| Browser auto-open; JAR/frontend paths with non-ASCII characters; manual keyboard/visual review | UNVERIFIED | Packaged JAR path with ordinary spaces was used in the failing Unicode-Session diagnostic. No production React build or non-ASCII installation-path/browser-auto-open run was available. |
| Abrupt OS crash, forced termination, uncooperative native IO | UNVERIFIED / outside ordinary-shutdown guarantee | Controlled helper-process deadlines passed. They do not establish native cleanup after crashes or indefinitely stalled IO. No unrelated process was signalled or terminated. |

### Findings requiring owner review

1. **High functional acceptance blocker — native Unicode child launch.** Existing failing cases: `ManagementWorkflowTests.browserPathEntryOpeningRefreshAndForwardingStayOnTheSelectedGeneration` and `SessionCreationApiTests.createsCollisionResolvedSessionAndOpensRealChildWithMatchingGenerationAndRecoverableResult`. New failing reproduction: `WindowsSessionQualificationTests.unicodeSessionStartsRealChildWithDiagnosticOutputRetained`. A valid native Session containing Japanese characters can be created/opened structurally in the parent JVM, but the real child receives/resolves a path containing `??` and fails at `CatalogConfiguration.java:31` (`Path.of(selectedRoot)`), classified by `CatalogChildApplication` as INVALID_SESSION. The packaged reproduction log ends with the same `InvalidPathException`, not a SQLite/lock error. Relevant launch boundary: `CatalogProcessSupervisor.java:66` argument list, `CatalogChildCommand`, `CatalogChildApplication.java:30` and Spring property handoff. Cp1252 native encoding is a clue; the exact conversion point was not proven. Proposed next work: review a lossless cross-process Session-path transport and regression coverage without changing OS encoding/security settings. Do not blindly relax path validation. The created Session/manifest survives; no original-data mutation or replay was observed. Affected Unicode startup/browser success scenarios stop pending owner review.
2. **Medium qualification/test portability — maximum component versus native full-path limit.** `SessionCreationServiceTests.maximumLengthStillAllowsFinalSuffix` errors after NIO creates the child: `WindowsNtfsNative.java:25` reports `Cannot open Windows filesystem identity (error 3)`. The native retained fixture reproduces bounded partial failure, no manifest and no suffix replay. Existing design allows provider/full-path rejection, so this is not evidence of unsafe fallback. Review the unconditional positive regression expectation and separately decide whether long-path support needs an authorized native-adapter change.
3. **Medium test portability — missing NIO key is not missing Windows identity.** `SessionCreationServiceTests.unsupportedOrUncertainStorageAndMissingIdentityFailBeforeCreatingAnything` fails at line 179 because its mocked BasicFileAttributes has no NIO key but the real Windows native identity remains available. The implemented fallback intentionally supports that situation. Proposed correction: separately test absent APFS NIO identity and unavailable Windows native identity; do not remove the Windows fallback to satisfy the fixture. No production defect is established by this failure.
4. **Compatibility/policy clarification — ancestor junction refusal.** `WindowsSessionQualificationTests.trustedAncestorJunctionResolvesButRetargetingStopsBeforeMutation` fails early through `Session.normalizedRoot`/`WindowsNtfsHostFileSystem.unsafeElement` because real-path versus no-follow real-path differs through the ancestor junction. The application refuses before mutation. Review platform-specific alias expectations/documentation before any change to reparse authority; retargeting remains untested here.
5. **Input-policy clarification — extended-prefix normalization.** New `extendedPathSyntaxMustNotSilentlyBecomeAnOrdinaryPath` fails because Java Path parsing strips `\\?\` before the shared gate sees it. Review raw management/CLI string admission if explicit lexical rejection is required. Current evidence shows ordinary supported C: storage acceptance after normalization, not network/exFAT acceptance or an identity bypass. No security redesign is implemented under this task.

The final new test suite intentionally remains red for these unresolved positive/policy expectations. No test was disabled to manufacture acceptance. The initial diagnostic reader error was test tooling only and corrected; native application failures remain reproduced.

### Retained evidence, process ownership and changes

All repository-relative logs/build outputs below are local ignored artifacts, not a permanent evidence archive. Baseline XML is preserved separately; `surefire-reports/` also contains the final new suite and the non-skipped native identity suite. The opt-in fixture paths and results are emitted per testcase into XML and logs.

- Final Unicode packaged failure: `C:\Users\sears\AppData\Local\Temp\junit-9435198223932959436\child-diagnostic.log`; copied `Application with spaces.jar`; valid original Session `Café 日本 + # %` retained. Exact test-created process **31576**, parent Surefire JVM **29700**; the test retained its Process object, closed only its stdin to request EOF cleanup and observed actual exit. The earlier classpath reproduction was process **32332**, parent **31956**, fixture `junit-6831393315558862245`; its first reader error does not negate the separate retained application InvalidPathException.
- Final close/move/reopen: `C:\Users\sears\AppData\Local\Temp\junit-16153901493460100204\Moved Session cafe`. Native junction, ACL, names and long-path fixtures are respectively `junit-15221709469646075961`, `junit-10261621924461563811`, `junit-6106802362415709830`, `junit-3269553820696607395` under the same controlled Temp parent. Ancestor failure: `junit-10236667270517687940`; extended-prefix observation: `junit-7448419826625314061`.
- A read-only Python `sqlite3.connect((root/'catalog.db').as_uri()+'?mode=ro', uri=True)` check on the moved fixture ran `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, `SELECT count(*), sum(success) FROM flyway_schema_history WHERE version IS NOT NULL` and `SELECT id,name FROM working_set WHERE created_at_ms=1`. Results: `ok`, no FK violations, `(10,10)`, row `(1,'preserved café 日本')`. Its `catalog.db.lock` still existed. No custom table/migration was added; this is disposable data in the existing schema.
- Test process selection used exact returned Process objects/parent IDs and read-only Win32_Process ancestry/command-line inspection tied to this checkout, not process-name termination. The baseline Maven JVM was **26788**, parent **18676**, with `-Dmaven.multiModuleProjectDirectory=D:\Dev\projects\ai-projects\media-compare-dev\backend` and the exact 17-suite command. No unrelated Java/browser/user process was terminated; final process checks are recorded at task handoff.
- **Changed tracked files:** this report, `docs/STATUS.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `README.md`. **New untracked source:** `backend/src/test/java/io/github/topher6835/mediacompare/session/WindowsSessionQualificationTests.java`. No production source, dependency, schema, frontend application or exFAT policy changed. Generated ignored `backend/target` contains logs/reports, compiled classes and the JAR; disposable C: fixtures/logs/JAR copies are retained.

Final handoff checks: `git status --short` showed only the five documentation files above and the new untracked test; `git diff --check` passed. The untracked test's trailing-whitespace scan passed, and `git diff --cached --exit-code` confirmed no staged changes. HEAD remained the approved SHA on `main`. Read-only host inspection found **no remaining matching qualification Java processes**. Both final ACL-denial fixture directories had **zero DENY entries** after restoration.

**Required disposition:** Stage 4 is incomplete. Review the summary and actual diff, including the untracked native test; separately authorize prerequisite installation and any corrections, then complete required Windows/browser and unresolved native scenarios. Preserve historical Part 1 evidence. **STOP without staging, committing, pushing or starting another milestone.**

### Windows blocker corrections and resumed qualification (2026-10-30)

**Current disposition: authorized blocker corrections and focused qualification completed locally; independent review and final Stage 4 acceptance remain outstanding.** The continuation explicitly authorized the Unicode correction, platform-test corrections, diagnostic treatment of undecided path expectations, project-local `npm ci` and resumed Windows browser qualification. All six pre-existing changed/untracked files and the initial failure evidence were preserved. No clone/pull/reset/clean/stash/branch switch, protected-workspace access, dependency version change, global installation, Windows security/locale/registry change, staging, commit or push occurred. HEAD remains the approved `ee1911abbe16798ca2d9c739fd37474d72addb90` on `main` in the same D: checkout.

#### Proven conversion boundary and focused correction

Two small standalone Java probes, retained as source/classes/logs under ignored `backend/target`, established the boundary without Spring, Session validation or mocked native APIs:

- `WindowsArgumentProbe`: the parent constructs the Japanese characters in Java; its child immediately prints incoming argument code points. Parent `65e5 672c` becomes child `003f 003f`.
- `WindowsWideArgumentProbe`, using the already cached JNA 5.17.0: inside that same child, native `GetCommandLineW` still contains the exact original Unicode string, while Java `main(String[] args)` does not. Output: **`GetCommandLineW-contains-original=true`**, **`Java-main-arg-equals-original=false`**, exit 0. This isolates corruption between the OS wide command line and Java-main argument conversion on this Java 21/Cp1252 installation. It is not a Spring property, URI encoding, SQLite or NTFS identity failure. The probes do not identify a particular JDK C function or claim all Java installations behave identically.

Evidence: `stage4-task4-windows-corrections-argument-probe.log`, `stage4-task4-windows-corrections-wide-argument-probe.log`, `WindowsArgumentProbe.java`, `WindowsWideArgumentProbe.java` and their separate compiled directories under `backend/target`. The narrow probe commands used installed `javac/java`; the wide probe's only classpath dependency was the existing `C:\Users\sears\.m2\repository\net\java\dev\jna\jna\5.17.0\jna-5.17.0.jar`.

The correction changes three production files only:

| File | Exact behavior |
| --- | --- |
| `CatalogChildCommand.java` | Encode the existing absolute normalized Session path as `session-path-utf8-v1:` plus unpadded Base64URL of UTF-8 bytes. Require valid Unicode on encode and the expected tag, valid Base64URL and strict UTF-8 on decode. Raw-path fallback is refused at the private entrypoint. |
| `CatalogProcessSupervisor.java` | Supply that ASCII representation in the same third `catalog-child` command argument, using the existing no-shell ProcessBuilder list. |
| `CatalogChildApplication.java` | Decode before Spring startup and pass the exact decoded string to the unchanged in-JVM `--media-compare.session-root` argument. Existing Session/storage/identity/ownership validation still runs. |

No private launch-token, readiness-frame, STOP/EOF, generation, storage, filesystem-authority or catalog schema contract changes. Encoding is transport, not a credential or authority assertion. No public API/frontend application behavior changed. This is one argument representation, not a new pipe protocol or general process framework. Non-ASCII Java/JAR/classpath installation paths are not fixed or newly qualified by encoding the Session argument.

`CatalogChildCommandTests` covers exact ASCII/space/Japanese/emoji/punctuation round trips, ASCII-only transport, malformed tag/Base64/UTF-8 refusal and a real Windows child-main round trip. The existing supervisor fixture and EOF test now use the same encoded private argument. `WindowsSessionQualificationTests` launches the **rebuilt packaged JAR** for three separate cases: `ASCII`, `Session with spaces`, and `Café 日本 🎬 + # % ! & (2026)`. Each reaches actual READY, receives EOF and exits 0. Old failing packaged-JAR/initial report evidence was retained; it was not overwritten with successful results.

#### Platform assumptions and undecided policy

- `SessionCreationServiceTests.maximumComponentAllowsSuffixOrPreservesNativeFullPathFailureWithoutReplay` replaces the unconditional full-path-success expectation. It checks the suffix is a valid 255-unit component and verifies success when supported. A Windows native IO failure must preserve the attempted location/occupied original and stop without writing a manifest, deleting the partial directory or trying ` (002)`. Other-host failures are not silently accepted. The opt-in native fixture still reproduces actual long-path refusal/preservation on this host; **long-path success is not claimed**.
- Unsupported storage, missing APFS NIO identity and unavailable Windows native identity now have separate fail-closed cases. The Windows test explicitly includes unavailable NIO identity; it still requires native identity. Native NTFS successful creation/inspection separately demonstrates the fallback remains usable. Production identity code is unchanged.
- `ancestorJunctionRefusalPreservesBothTargets` verifies current conservative refusal before writes, including after deliberately retargeting the test-owned junction between two disposable targets. It does not require alias acceptance or claim to exercise an in-flight alias race. Supporting ancestor junctions remains undecided; no reparse check was weakened.
- `extendedPrefixNormalizationRetainsTheSameSupportedDirectoryIdentity` records Java stripping `\\?\`, verifies the parsed path equals the same ordinary disposable C: directory and native identity, and applies the existing storage gate. It does not require an unapproved lexical rejection rule or declare new raw input syntax supported. Raw-input syntax policy remains undecided.
- No test was newly disabled to obtain green results. The existing 15 skips remain visible: one macOS-only check, two POSIX-permission checks and twelve symlink-privilege fixtures. The original Windows smoke test still ends **SKIP** at its final symlink step; separate non-skipped native NTFS suites do not turn that skipped branch into PASS.

#### Exact commands and final automated outcomes

Installed Node/Java/Windows/browser/filesystem versions remain those recorded in the initial Part 2 environment. Only the process-local Node PATH was adjusted. The already cached Mockito agent path was rechecked in the prior run and reused unchanged. Maven remained offline with the same existing cache. Initial `surefire-reports` was copied to `backend/target/stage4-task4-windows-before-corrections-reports/` before executing tests; the earlier baseline/native-initial snapshots remain intact.

From the repository root:

```powershell
$env:Path = 'C:\Users\sears\AppData\Local\Author Software\nvm\installs\v24.17.0;' + $env:Path
npm.cmd --prefix frontend ci --cache backend/target/windows-blocker-npm-cache *> backend/target/stage4-task4-windows-corrections-npm-ci.log
node --test frontend/tests/sessionCreation.test.mjs frontend/tests/sessionStartup.test.mjs frontend/tests/catalogConnection.test.mjs frontend/tests/catalogOperations.test.mjs frontend/tests/metadataWorkflow.test.mjs *> backend/target/stage4-task4-windows-corrections-frontend.log
npm.cmd --prefix frontend run build *> backend/target/stage4-task4-windows-corrections-build.log
npm.cmd --prefix frontend run lint *> backend/target/stage4-task4-windows-corrections-lint.log
node --check frontend/tests/sessionManagement.windows.acceptance.mjs
```

All exited **0**. `npm ci` added **164** packages and audited **165** using the existing lockfile, project-local node_modules and a checkout-local cache. `package.json` and `package-lock.json` are unchanged. npm reported **four high-severity audit findings** in the locked dependency set; no audit fix, package update or global/NVM change was performed. Frontend: **52 tests passed, zero failures/skips**; TypeScript/Vite production build and ESLint passed. The opt-in `.mjs` tooling also passed Node syntax checking and real execution.

From `backend/`, with `$env:MAVEN_USER_HOME = 'C:\Users\sears\.m2'`:

```powershell
.\mvnw.cmd -o -q '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-Dtest=CatalogChildCommandTests,SessionCreationServiceTests' '-DargLine=-javaagent:C:\Users\sears\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar' '-Dsurefire.reportsDirectory=target/surefire-corrections-targeted' test *> target/stage4-task4-windows-corrections-targeted.log
.\mvnw.cmd -o -q '-Dmaven.repo.local=C:\Users\sears\.m2\repository' package -DskipTests *> target/stage4-task4-windows-corrections-package.log
$agent = 'C:\Users\sears\.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar'
.\mvnw.cmd -o -q '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-Dtest=SessionCreationOperationsTests,SessionCreationApiTests,ManagementServerTests,ManagementSecurityTests,ManagementWorkflowTests,CatalogProcessSupervisorTests,FilesystemBrowserTests,SessionCreationServiceTests,SessionTests,SessionFilesystemPolicyTests,SessionStartupTests,SessionSourceBoundaryTests,SessionSourceAliasTests,SessionSourceIsolationTests,WindowsHostFileSystemTests,HostFileSystemTests,CatalogOwnershipTests' "-DargLine=-javaagent:$agent" test *> target/stage4-task4-windows-corrections-regression.log
.\mvnw.cmd -o -q '-Dmaven.repo.local=C:\Users\sears\.m2\repository' '-Dtest=WindowsNtfsNativeTests,WindowsSessionQualificationTests' '-Dmedia.compare.windows.qualification=true' test *> target/stage4-task4-windows-corrections-native.log
```

All exited **0**. The targeted command's report-property argument did not relocate the reports in this build; relevant XML/text was explicitly copied from `surefire-reports` to `stage4-task4-windows-corrections-targeted-reports/`. The full-run raw report snapshot is `stage4-task4-windows-corrections-full-reports/`; it also contains leftover XML for suites not selected by that command, so totals below include **only the exact 17 named suites**. The final native XML remains in `surefire-reports/`.

| Gate | Tests | Passed | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: | ---: |
| Targeted `CatalogChildCommandTests,SessionCreationServiceTests` | 33 | 29 | 0 | 0 | 4 |
| Exact 17-suite focused gate | **119** | **104** | **0** | **0** | **15** |
| `CatalogChildCommandTests` (included in targeted run) | 3 | 3 | 0 | 0 | 0 |
| `WindowsNtfsNativeTests` | 3 | 3 | 0 | 0 | 0 |
| `WindowsSessionQualificationTests` | 11 | 11 | 0 | 0 | 0 |

Every suite's count in the initial 17-suite table remains the same except `SessionCreationServiceTests`, now **30 tests, zero failures/errors, four skips**. The two real Unicode workflow/API failures are now passing. The 119-test gate includes real ownership conflict, corrupt-catalog failure/explicit retry, STOP/EOF, actual child exit, generation/runtime isolation, collision/partial failure and controlled retention/concurrency regressions. Deterministic mock/clock/IO gates retain their original evidence limits; executing them on Windows does not make injected failures native hardware tests. No unrelated exFAT/media-analysis suite was rerun.

Packaging passed with tests skipped only for that packaging command. Corrected executable: **40,985,096 bytes**, SHA-256 **`f6f2e50ef8cd415e9b61534a82597014f8234fd25865b83b40fc5a76429b7cce`**. Separate native NTFS handle probe: **391 before, 391 after successful opens, 391 after failures**; fsutil serialization and replacement identity checks passed. Native Session test logs retain actual C: drive type/root/serial and per-test fixture paths. Packaged Unicode success fixture: `C:\Users\sears\AppData\Local\Temp\junit-8812770917676303697`, child **34940**, parent **20928**; ASCII/space children **34792/35760** also exited 0. Console rendering of Unicode is not the path-equality evidence: strict UTF-8 XML, in-JVM/path assertions and actual catalog readiness are.

#### Real Windows browser qualification

From the repository root, with the Node PATH above:

```powershell
$env:MEDIA_COMPARE_CHROME = 'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe'
$env:MEDIA_COMPARE_PYTHON = 'C:\Python311\python.exe'
node frontend/tests/sessionManagement.windows.acceptance.mjs *> backend/target/stage4-task4-windows-corrections-browser-first.log
# After adding the separately labelled lost-response scenario and robust log-preserving cleanup:
node frontend/tests/sessionManagement.windows.acceptance.mjs *> backend/target/stage4-task4-windows-corrections-browser-final.log
```

Both exited **0**. Final fixtures/logs: **`C:\Users\sears\AppData\Local\Temp\media-compare-stage4-task4-windows-5aZ9HA`**. The preceding successful run is retained at `media-compare-stage4-task4-windows-BCvoex`. Both use real fixed local C: NTFS confirmed by the native application probe, the rebuilt packaged JAR copied to `Application with spaces.jar`, current production React dist, isolated Chrome profiles and real numeric-loopback URLs.

`WindowsManagementAcceptanceDriver` is a test-only class compiled with existing backend test dependencies and copied beside the JAR. Spring Boot's existing PropertiesLauncher loads that helper beside the packaged product classes. For management it calls the unmodified `ManagementApplication.main`; EOF requests ordinary `System.exit(0)`, invoking the existing product management shutdown hook. `java.class.path` still identifies the packaged JAR, so supervised catalog children use the actual executable JAR and production child entrypoint. Its lock mode holds only its own native `CatalogOwnership` until EOF. This is test shutdown/lock control, not a new production endpoint, Windows service or simulated supervisor. No process-name kill, Windows TerminateProcess fallback or lock-file deletion is used. Browser cleanup uses CDP Browser.close and observes the returned Chrome process exit. All logs/fixtures survive assertions or cleanup failures.

| Scenario | Final result and precise evidence |
| --- | --- |
| React New, Unicode/spaces/punctuation, parent browser, correct result | **PASS native.** React created `Café 日本 + # % (001)` after preserving the occupied file. Exact manifest/result path and matching RUNNING/alive operation/management generation were verified. |
| Sources readiness and refresh | **PASS native.** No Sources requests while actual child was OPENING; refresh during OPENING recovered observation, then New navigated to Sources after matching readiness. Ready Sources and existing Library deep-link refresh passed. The brief CREATING phase was not independently caught. |
| Duplicate clicks and concurrent delivery | **PASS native.** Double click produced one reservation plus one submission. Eight concurrent real HTTP deliveries replayed that operation/generation with no replacement Session. Original collision/media sentinels were unchanged. |
| Normal manager/child shutdown, SQLite and native handles | **PASS ordinary tested paths.** EOF to the test driver invoked the actual shutdown hook; manager exited 0 after product cleanup. Read-only SQLite inspection found ten successful migrations, integrity=ok, no FK violations and the preserved disposable Source. Session, `catalog.db` and the existing lock sidecar were renamed/moved and restored without unlinking the lock. |
| Open Existing and preserved data | **PASS native.** React opened moved `Moved Session café 日本`, preserved Source ID/name/path and unknown `notes.txt`, used a new generation and stayed at home. Stale previous generation was rejected with 409. Only disposable Source data was registered; no scans or original-media processing ran. |
| Real opening failure and explicit Retry Opening | **PASS native.** An independent Java process obtained the real Windows catalog lock after manifest publication. The real child failed OWNERSHIP_FAILED and actually exited. No Sources request mounted. After only the test owner's lock release, explicit retry opened the same folder, survived refresh during retry and navigated to Sources under a new matching generation. Four mutation POSTs total (reserve/create, reserve/retry), one Session folder; stale failed-attempt retry returned 409. No creation replay or catalog repair was needed. |
| Management restart / foreign creation handle | **PASS native.** New manager rejected the prior runtime-qualified creation handle with 409 MANAGEMENT_RUNTIME_CHANGED, without execution; deliberate Open then succeeded. Actual ten-minute creation-handle expiry was not timed in browser; clock-based expiry regressions passed separately. |
| Manual Open after discovery failure | **PASS mixed, explicitly simulated transport.** CDP returned 503 only for discovery. Real React required acknowledgment, kept New disabled, sent one real Open POST, waited for its actual native child and attached at home without New-only navigation. |
| Lost creation response / no replay | **PASS mixed, explicitly simulated transport.** CDP dropped one execution response after the real HTTP request executed. Actual Windows creation/child/catalog continued; React observed its retained handle, issued no second submission and attached matching Sources. Exactly one reservation/submission and one resulting folder were verified. This is not an actual failing network adapter. |

Final test-owned process identities: manager **35728** (New), **35188** (Open), **30512** (Retry), **34088** (manual recovery), **34904** (lost response); native competing owner **25828**; Chrome **34948**. Each was created/tracked directly by the harness, with its exact executable/arguments fixed to the copied artifact/profile. Returned Process objects and actual exit checks govern cleanup; final host process/ACL verification is recorded at handoff. Management logs retain their native NTFS probe and numeric-loopback URL. No original workspace or unrelated Java/browser process was used or terminated.

#### Remaining limits, files and review disposition

**No application failures remain in the executed correction gates/scenarios.** The following are still **UNVERIFIED or undecided**, not implied PASS: privileged file/directory symlinks (original smoke test still skipped), accessible UNC/mapped network routes, unsupported/removable/external filesystem hardware, directory volume mounts, device syntax beyond existing deterministic tests, browser auto-open/manual keyboard/visual UX, non-ASCII Java/JAR/frontend installation paths, actual timed browser expiry, native CREATING/partial-write refresh, hostile in-flight junction races, OS crashes/uncooperative native IO, and a fresh macOS execution of the changed private transport. External testing was not required for this execution. Existing supported-storage restrictions and production exFAT=false remain binding. Long-path success remains unsupported by the observed native adapter; safe bounded refusal/preservation was qualified. Ancestor-junction support and raw extended-prefix policy await owner decision; current conservative behavior remains intact.

**Modified production files:** `CatalogChildCommand.java`, `CatalogProcessSupervisor.java`, `CatalogChildApplication.java` under `backend/src/main/java/io/github/topher6835/mediacompare/management/`.

**Modified existing tracked tests:** `management/CatalogProcessFixture.java`, `management/CatalogProcessSupervisorTests.java`, `session/SessionCreationServiceTests.java` under the matching backend test package. **Pre-existing untracked test updated:** `session/WindowsSessionQualificationTests.java`. **New files:** `management/CatalogChildCommandTests.java`, `management/WindowsManagementAcceptanceDriver.java` and `frontend/tests/sessionManagement.windows.acceptance.mjs`. **Documentation:** `README.md`, `docs/STATUS.md`, this report, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`. All are part of the same independent review. No frontend application, catalog schema, dependency version/lockfile, Source authority, filesystem policy or exFAT production file changed. Ignored build/npm cache/report artifacts and disposable C: fixtures are retained locally.

Final handoff verification: read-only native process inspection found **no matching qualification Java/Chrome processes** for the exact checkout and fixture paths. Both final ACL fixture directories had **zero injected DENY entries**. All five modified documentation files decoded strictly as UTF-8, with **zero U+FFFD characters both in HEAD and the working copy**; all four untracked source files passed strict UTF-8, final-newline and trailing-whitespace checks. `git diff --check`, unchanged frontend manifest/lockfile checks and `git diff --cached --exit-code` passed. Final `git status --short` lists eleven modified tracked files and the four untracked files identified above; no staged changes. HEAD/branch remain the approved SHA/`main`.

The original Part 1 and initial Part 2 failures remain intact above and in their retained evidence. Console/clipboard mojibake alone is not evidence of corrupt documentation. **Stage 4 remains explicitly incomplete and final acceptance is not granted. Stop for independent summary and actual-diff review; do not stage, commit, push or begin another milestone.**
