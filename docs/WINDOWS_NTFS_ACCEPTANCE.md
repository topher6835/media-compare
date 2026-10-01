# Windows/NTFS Acceptance — 2026-10-01

## Baseline and environment

- Starting HEAD: `e6489b5c147d74f782ee10a9ab42efc20f3309b8`; working tree clean before testing.
- Checkout: `D:\Dev\projects\api-test\media-compare`; `D:` verified as NTFS.
- Microsoft Windows 10 Home x64, version `10.0.19045`.
- Temurin OpenJDK `21.0.12.1+1-LTS`, Eclipse Adoptium, 64-bit; Maven `3.9.15`.
- Node `24.17.0`; npm `11.13.0`.
- Maven and Node needed execution outside the sandbox to use the user's existing tool configuration. No system software was installed or changed.
- Frontend dependencies were initially absent locally. `npm ci` restored the existing lockfile dependencies without changing the manifest or lockfile. Its audit reported one high-severity advisory; dependency upgrades were outside this milestone.

## Unchanged first run and defects

The existing backend suite was run unchanged with `mvn -q test` before any production/test edit. Surefire reported **1,283 tests, 19 failures, 264 errors, 18 skips** (including class teardown errors). The Windows-specific existing suites executed:

- `WindowsNtfsBindingWriterTests`: 1 passed.
- `WindowsNtfsScanAuthorityTests`: 4 passed.
- `WindowsNtfsExecutionTests`: 1 error during discovery.
- `HostFileSystemTests`: 2 passed, 2 skipped. The Windows native/case/replacement assertions ran before that test aborted at unavailable symlink creation; its overall result is a skip, not a pass.

The production defect was in `Version3DiscoveryWalker.walkWindows`: `root.relativize(root)` yields a Path whose iterator includes an empty segment. Appending that segment to a structured LocationPath is invalid. The caught exception made the root an uncertain storage boundary, skipped its subtree, and failed discovery with `CHILD_STORAGE_UNCERTAIN`. The Windows directory branch now uses the captured Source-root location directly when visiting the root. The APFS branch is unchanged.

Other failures were test defects, not evidence of missing NTFS identity:

- Real Windows temporary paths were parsed as Unix/APFS locations in scan/API, metadata, and thumbnail fixtures. Some lifecycle tests also demanded current-host admission/publication from APFS bindings on Windows. Those fixtures/cases now explicitly require macOS; their assertions remain intact. Pure persistence and authority checks still run where independent of host admission.
- The injected APFS logical-anchor mount fixture keyed observations by Unix text but received host Path separators. Its fake inspector now converts its ordinary fixture separators. The Finder command test now compares the argument to the input Path's rendered text. Both suites run on Windows without invoking macOS tools.
- Spring cached the file-backed test contexts past JUnit TempDir cleanup. Windows refused deletion of still-open SQLite databases. `SourceApiTests` and `SessionSourceIsolationTests` now close their contexts after the class. The first post-fix full run exposed both teardown errors; focused reruns verified closure.

## Real NTFS observations

New acceptance fixtures are disposable directories under `backend/target` on the checkout's drive. Application Sources are siblings of the disposable Session, outside its workspace/cache. No user media was used.

| Case | Observation |
| --- | --- |
| Native file, directory, drive root | Stable native volume serial and 128-bit IDs across repeated observations; correct directory/file classification. 512 rounds exercised all three objects, plus 64 failed opens. Handle counts showed bounded JVM variation, not per-open growth (476→476 in a focused run; 539→542 in the full run). |
| Native interop | Actual `CreateFile` with backup/no-follow flags, 8-byte `FileAttributeTagInfo`, 24-byte `FileIdInfo`, and handle closing succeeded. Junction observations returned reparse classification with no identity. |
| Serialization | Observed D: volume serial `50b08a4bb08a378c`; its low 32 bits agree with `GetVolumeInformation`. An example raw file ID `46850200000004000000000000000000` matched `fsutil` display `0x00000000000000000004000000028546`. Persisted IDs remain opaque bytes in API return order; serials are numeric lowercase hex. IDs of disposable files vary each run. |
| Case-only paths | Different file and directory casing resolves the same native identity. Stored `MixedCaseSource`, `Photo.JPG`, `Nested`, and `Alpha.PNG` spelling is retained. Alternate drive-letter casing cannot create a second active context. `lk1` keys preserve exact spelling; no global folding was added. |
| Replacement | Replacing a file at the same pathname with equal size and exact mtime changes its native ID. A directory moved away and recreated also gets a different ID. Before/after host checks distinguish them. |
| File/directory symlinks | Creation was attempted independently; both acceptance cases skipped because the Windows account lacks symlink creation privileges. Rejection policy was not weakened. |
| Junction | Creation succeeded. Native/path inspection, Source preparation, and traversal rejected it; external target files never became trusted candidates. Aliases were removed explicitly before temporary-directory cleanup. |
| Source preparation/binding | Normal registration and preparation reached READY and repeat preparation was idempotent. Stored `win-drive` paths, canonical exact-spelling keys, accepted drive-root context, Source-root IDs, Source/context revisions, and one open binding period matched actual native observations. No APFS evidence was written. Unbound scan admission was rejected. |
| Unbind/rebind | Unbind retired active memberships and closed the period; fixed-root NTFS rebind advanced the Source revision, preserved root/key, and opened a second period. One old closed period and one current open period remained. A subsequent scan reused FileEntries and memberships. |
| v3 scan | The normal indexing service/background workflow completed discovery, reconciliation, assignment, and hashing for a JPEG, its exact copy in a nested directory, a PNG, and a text file. All four were cataloged/hashed; structured Windows paths were persisted. Repeated scanning retained four FileEntries, four ContentRecords, and four memberships and reused hashes. Descendant checks ran against the accepted NTFS volume. |
| Exact semantics/preflight | The two JPEG copies formed a two-physical-copy Exact group. Fresh Windows cleanup preflight returned READY with the existing service; no persistent review or file mutation was added. |
| Hashing | Normal v3 hashing succeeded. Replacement during an open read, with equal size/mtime, was rejected by the post-read native identity check. |
| Metadata | Real JPEG/PNG analysis returned AVAILABLE for the three images; the text file returned UNSUPPORTED. Metadata's post-read check rejected the same-metadata replacement. |
| Thumbnails | JPEG and PNG SMALL_THUMBNAIL generation produced readable PNG assets under the selected Session's `cache/previews`; repeat generation reused them. Original JPEG bytes and mtime were unchanged. No originals were copied into the Session. |
| Stale root | The existing prepared/bound Source was moved aside and a new directory created at the exact same pathname. Fresh authority capture returned STALE. The successful pre-fix-to-post-fix scan test retained this mandatory assertion. |
| Source/Session separation | Equal, parent, child, alternate-case, junction-alias, and missing-leaf-under-alias overlaps were rejected. A separate missing Source path remained allowed. The Session manifest and external junction target remained intact. |

## Changed files

Production: only `backend/src/main/java/io/github/topher6835/mediacompare/scan/Version3DiscoveryWalker.java`.

Acceptance tests: expanded `scan/WindowsNtfsExecutionTests`; added `filesystem/WindowsNtfsNativeTests`, `scan/WindowsNtfsReparseTests`, and the test-only `filesystem/CheckoutTempDirFactory`.

Fixture corrections: `scan/V3TestHost`, `preview/ThumbnailCatalogFixture`, `ImageIoMediaMetadataAnalyzerTests`, `MediaMetadataEvidenceAndPublicationTests`, `MediaMetadataJobTests`, `SourceApiTests`, `SourceBindingServiceTests`, `SourceMembershipAuthorityTests`, `SourcePreparationServiceTests`, `SourceUnbindingServiceTests`, `scan/Version3ExecutionTests`, `scan/authority/Version3DiscoveryWalkerTests`, `location/MacOsApfsLogicalAnchorResolverTests`, `matching/FinderRevealProcessTests`, and `SessionSourceIsolationTests`. Existing APFS assertions were preserved; host assumptions, injected path rendering, and context cleanup changed.

Documentation: this report, `STATUS.md`, `README.md`, and current Windows acceptance statements in `ARCHITECTURE.md` and `DECISIONS.md`. No schema/data model changed.

## Validation gate

Final `mvn -q test` passed with exit code 0: **1,289 tests, 1,002 passed, zero failures/errors, 287 skips**. The skipped APFS fixtures and unavailable symbolic-link cases are explicit coverage limits, not successful Windows validation. All three native tests and three execution/read tests passed; two of four reparse/separation cases passed and two independently attempted symlink cases skipped.

`mvn -q package -DskipTests` passed with exit code 0. Frontend `npm run lint`, `npm run build`, and `node --test tests/*.test.mjs` passed with local lockfile dependencies; all 59 Node tests passed without skips. Repository-root `git diff --check` passed; `git status --short` was inspected. No commit or push was performed.

## Manual frontend/backend smoke test

This was a later manual UI run, separate from the automated acceptance gate above. On Windows 10, the real frontend in a browser communicated with the real backend jar running from NTFS and using a disposable Session on NTFS. Through the normal UI, an existing NTFS folder was registered as a Source, prepared, and analyzed. About 484 files completed analysis in a few minutes. The Library populated correctly; item thumbnails/previews, Exact badges/groups, and general browsing looked correct. This was not a performance benchmark.

The Session remains reusable: starting the backend later with the same Session path reopens it. Original Source files were neither modified nor copied into the Session; only derived Session-owned assets such as cached previews were created.

During the run, the frontend displayed `Current indexing progress could not be refreshed. Polling will continue.` a few times. The run still completed successfully. This is a non-blocking observation, not a diagnosed defect; no cause is known. Investigate it if it becomes reproducible or disruptive.

The Windows Library/item UI still displays `Reveal in Finder`, and the action does not work on Windows. Reveal remains macOS/Finder-only in the current implementation. The planned Windows reveal milestone should provide platform-aware behavior, with `Reveal in Finder` on macOS and `Show in File Explorer` or equivalent on Windows, and no Finder-specific action exposed as usable Windows functionality. Explorer reveal was not implemented in this milestone.

Two exact duplicate physical files appeared as two separate cards in Items. This is intentional: Items represent physical FileEntries, while the Exact badge communicates content grouping and Exact/group views may use one representative card. Any future optional “Collapse exact duplicates” presentation should remain display-only and preserve distinct FileEntries. No Library behavior was changed.

## Limits and next work

- Symbolic-link rejection still needs a real run with symlink creation privileges. Junction rejection was exercised.
- The macOS-only fixture skips are explicit coverage limits on this Windows run. No real macOS/APFS run was possible here; the APFS production path and evidence formats were unchanged.
- Java NIO/native pathname observations do not provide atomic handle-relative traversal. Concurrent intermediate-path replacement remains a residual race; this milestone does not claim to solve every filesystem race.
- Whole-volume replacement, remount/reboot continuity, other physical NTFS drives, long/device/UNC paths, and every reparse tag were not empirically qualified. No automatic remount identity was inferred.
- Real Windows ffprobe `fd:` acceptance remains separate work. The manual UI smoke test above does not replace broader Windows UI acceptance.
- exFAT/VeraCrypt support remains unimplemented; actual mounted-volume investigation and authority design is the next filesystem milestone. NTFS guarantees were not weakened.
- Explorer reveal, packaging/installers, Variant V1.1, Variant matching/grouping, and Semantic/AI remain unimplemented.
- No commit or push was performed.
