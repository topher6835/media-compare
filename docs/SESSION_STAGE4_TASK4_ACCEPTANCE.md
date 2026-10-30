# Session Stage 4 Task 4 — Part 1 acceptance report

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
