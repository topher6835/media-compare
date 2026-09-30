# Media Compare

Media Compare is an early-stage application for media comparison workflows. V6 moves Source/FileEntry relationships and presence to SourceMembership and routes new indexing through a four-stage v3 SCAN. Historical V5 FileEntries remain separate and unresolved after migration; trusted overlapping Sources can share one resolved physical FileEntry. The Sources UI follows successful indexing with the existing media-metadata Job so a fresh Session can reach Library without a manual API call. Cataloged images remain visible when metadata or previews are unavailable. Session-only exact-duplicate cleanup planning with frontend read-only cleanup preflight is available; broader comparison and filesystem cleanup remain deferred.

## Stack

- Backend: Java 21, Spring Boot 4.1.1, Maven, Spring JDBC, Flyway, and SQLite
- Frontend: React, TypeScript, Vite, React Router, and ESLint
- Integration direction: Java NIO, FFmpeg/ffprobe, REST APIs, Server-Sent Events (SSE), and pluggable local/cloud AI providers

## Repository Structure

- `backend/` — Spring Boot application, Maven wrapper, local data directory, and Flyway migration directory
- `frontend/` — React and TypeScript application built with Vite
- `docs/` — architecture, data model, decisions, and current project status
- `AGENTS.md` — repository operating guidance for AI agents

## Prerequisites

- Java 21
- Node.js and npm

Maven does not need to be installed separately because the backend includes Maven wrappers for macOS/Linux and Windows. The bounded ffprobe runner exists but is not yet connected to durable video analysis, so ffprobe is not required for the current indexing and image-metadata workflows. When the runner is exercised, it uses an absolute path supplied through the optional `media-compare.ffprobe.executable` property or, when the property is absent, the `ffprobe` command from PATH.

## Run Locally

Build the backend once from `backend/`, then use the one-shot Session command to create a workspace. It exits without starting Spring or Flyway.

macOS/Linux:

```sh
./mvnw -q package -DskipTests
java -jar target/media-compare-0.0.1-SNAPSHOT.jar session create "/path/to/My Session"
```

Windows:

```bat
mvnw.cmd -q package -DskipTests
java -jar target\media-compare-0.0.1-SNAPSHOT.jar session create "C:\path\to\My Session"
```

The command creates this minimal `session.json` in a new or empty folder:

```json
{
  "type": "media-compare-session",
  "formatVersion": 1
}
```

Select that existing folder through the `MEDIA_COMPARE_SESSION_ROOT` environment variable. The backend fails startup if no valid Session is selected. Start the backend from `backend/`.

For disposable real-data testing, register and scan an external Source after startup, then stop the backend before deleting the Session. A Source root cannot contain the Session or be inside it; registration returns `400` with `SOURCE_OVERLAPS_SESSION` if they overlap. Original media stays outside the Session. Delete with `java -jar target/media-compare-0.0.1-SNAPSHOT.jar session delete "/path/to/My Session"` (use `target\...jar` and a Windows path on Windows). The command refuses an active catalog, removes only recognized Session-owned data, and leaves any unknown files placed directly in the Session folder untouched. It reports an error if those files prevent folder removal. There is no frontend Session picker yet.

macOS/Linux:

```sh
export MEDIA_COMPARE_SESSION_ROOT="/path/to/My Session"
./mvnw spring-boot:run
```

Windows:

```bat
set "MEDIA_COMPARE_SESSION_ROOT=C:\path\to\My Session"
mvnw.cmd spring-boot:run
```

Start the frontend in a separate terminal from `frontend/`:

```sh
npm install
npm run dev
```

During development:

- Frontend: `http://localhost:5173`
- Backend: `http://localhost:8080`
- Health endpoint: `http://localhost:8080/api/health`
- Source endpoints: `http://localhost:8080/api/sources`
- Source preparation: `POST http://localhost:8080/api/sources/{id}/prepare`
- Scan-request endpoints: `http://localhost:8080/api/scan-runs`
- New indexing endpoint: `POST http://localhost:8080/api/indexing-runs`
- Indexing detail: `GET http://localhost:8080/api/indexing-runs/{id}`
- Media metadata status: `GET http://localhost:8080/api/media-metadata-runs/status` (read-only latest/active Job); the Sources UI starts and polls the existing metadata Job after indexing.
- Content assignment endpoint: `POST http://localhost:8080/api/scan-runs/{id}/content-assignment`
- Content hashing endpoint: `POST http://localhost:8080/api/scan-runs/{id}/content-hashing`
- Exact duplicate endpoints: `GET http://localhost:8080/api/exact-duplicate-groups`, `GET http://localhost:8080/api/exact-duplicate-groups/filter-options`, and `GET http://localhost:8080/api/exact-duplicate-groups/{digestHex}`. List and detail reads accept repeated `fileCategory` and `extension` query parameters.
- Cleanup preflight: `POST http://localhost:8080/api/exact-duplicate-groups/{digestHex}/cleanup-preflight` (read-only; no Trash/delete/move).
- Source management and indexing frontend: `http://localhost:5173/sources`
- Grouped Media Library backend: `GET http://localhost:8080/api/media-library/groups`. Read-only current-image groups default to authoritative EXACT equality; repeated `relationshipType=EXACT` is accepted, while unavailable types return `400`. Pages use `afterRepresentativeFileEntryId` and default 50/max 200 groups. Responses contain `groups` (derived `groupKeyContentRecordId`, full representative item, physical `currentItemCount`) and nullable `nextCursor`, with `Cache-Control: no-store`.
- Media Library frontend: `http://localhost:5173/library`. The default Items view browses current catalog images; Groups shows exact-content group summaries, including one-file groups, using each representative image and current physical-file count. Both use a responsive grid with progressive 50-card pages. Cards open the representative Item Detail at `/library/items/{fileEntryId}`; an `Exact ×N` badge opens exact-duplicate detail when at least two present physical FileEntries share the SHA-256 digest. Item Detail shows the existing small thumbnail, available metadata, Source, and a validated catalog absolute path when available. Only visible missing supported thumbnails are scheduled; queued previews refresh within a bounded window. Published previews whose cache files cannot load offer an explicit Repair preview action. Recognized image extensions, including HEIC/HEIF, remain visible without decoded metadata or a thumbnail; unavailable format/dimensions are shown as unavailable. Group detail and compare remain future work.
- Media Library item detail API: `GET http://localhost:8080/api/media-library/items/{fileEntryId}` returns a current eligible item or `404`, with `Cache-Control: no-store`.
- Exact duplicate frontend: `http://localhost:5173/duplicates`. List cards use one supported image representative and the existing small-thumbnail scheduler/cache, or a placeholder. Detail shows complete validated catalog paths where available, groups overlapping Source memberships by physical `FileEntry`, and offers explicit keeper/removal preview decisions collected into a browser-session cleanup plan at `/duplicates/plan`. Multiple groups can be reviewed, updated, removed, or cleared. Use `Check safety` for one group or `Check cleanup plan` to check every group sequentially. The page shows transient READY NOW/BLOCKED results per physical FileEntry, separate CHECK FAILED request errors with retry, and safety counts alongside the unchanged browser-plan totals. Only individual READY results show backend-confirmed current candidate counts and savings; BLOCKED checks never rewrite the plan. Entries represent physical FileEntries, not Source memberships, and disappear on reload. No decision is persisted and no filesystem mutation exists. Future file operations must revalidate backend/filesystem authority rather than trust these review snapshots.
- The Vite development server proxies `/api` requests to the backend.

The V6 schema and v3 indexing cutover are implemented. SourceMembership owns the Source/FileEntry relationship and presence; trusted overlapping Sources can share one source-independent FileEntry. Historical v1/v2 indexing executions remain readable, and new indexing uses v3. On supported local macOS/APFS storage, register a folder on `/sources`, select **Prepare Source**, then select **Analyze Source** once it shows Ready. The page completes indexing, runs media metadata, and reports Library readiness; an interrupted or failed metadata Job can be inspected and explicitly retried without changing completed indexing. Registration stores the path without checking that it exists; preparation checks the filesystem and binds the Source to an accepted logical LocationContext. See [`docs/STATUS.md`](docs/STATUS.md) for current validation results.

The selected Session owns `catalog.db`, `catalog.db.lock`, and `cache/previews`. SQLite/Flyway creates the catalog on first startup; previews are created on demand. Original media remains at its registered Source paths. The old `backend/data/media-compare.db` is legacy smoke-test data and is never selected automatically. Local database files are ignored by Git and are not committed.

The Session-derived catalog URL also determines its `.lock` sidecar. A Java NIO OS lock prevents a second backend from using the same local catalog; do not delete the lock file to try to release ownership. Locking precedes Flyway and startup recovery. Keep the Session on a local filesystem and use one canonical catalog location, not hard-link aliases.

## Source preparation API

`POST /api/sources/{id}/prepare` has no request body. It returns the Source with `preparationState: READY` after successful first-time preparation, and returns the current Source unchanged if it was already ready. Source registration returns `PREPARATION_REQUIRED` without probing the path. A previously bound, currently unbound Source reports `REBIND_REQUIRED`; preparation returns `409` for that state because rebinding is a separate operation. Unknown Sources return `404`. Unavailable or unsupported local storage and uncertain APFS evidence return bounded `422` codes; probe infrastructure errors return `503`. The endpoint is currently for local macOS/APFS folders only.

## Exact-duplicate cleanup preflight API

`POST /api/exact-duplicate-groups/{digestHex}/cleanup-preflight` accepts
`{"keeperFileEntryId":2,"candidateFileEntryIds":[3,4]}`. The digest must be lowercase SHA-256 hex; IDs must be positive, candidates unique, exclude the keeper, and number 1–250. Malformed requests return `400`. Well-formed stale/unsafe proposals return `200` with `BLOCKED`; a successful informational check returns `200` with `READY`. Responses send `Cache-Control: no-store`.

The response includes `digestHex`, `status`, bounded `reason` (null on success), current catalog `sizeBytes` (null for an absent group), proposed `candidateCount`, `estimatedSavingsBytes` (null when blocked), and `keeper`/`candidates` results containing physical `fileEntryId`, `status`, and `reason`. Reasons are `GROUP_CHANGED`, `KEEPER_UNAVAILABLE`, `CANDIDATE_SET_CHANGED`, `AUTHORITY_UNAVAILABLE`, `AUTHORITY_CHANGED`, `FILESYSTEM_CHANGED`, `UNSAFE_PATH`, `HASH_MISMATCH`, and `IO_UNAVAILABLE`. Catalog integrity contradictions remain server failures.

The backend rederives the group: any ACTIVE PRESENT membership makes its physical FileEntry present, overlaps count once, and submitted candidates must exactly equal all other present copies. Each file needs a current trusted membership route, fresh local macOS/APFS Source/context continuity, strict non-symlink path/file/storage evidence, and freshly recomputed SHA-256, including the keeper. Catalog authority is reread after filesystem IO; changes block the proposal. READY savings use checked multiplication of validated candidate count by current group size.

Preflight changes no catalog rows or files and creates no token, job, approval, or durable plan. **READY does not authorize future filesystem mutation.** Results become stale immediately; future Trash execution must repeat the critical checks immediately before acting. The frontend invokes this endpoint from `/duplicates/plan`; results stay in page component state only and clear on navigation or reload. Browser plan snapshots remain non-authoritative. No Trash/delete/move operation exists. Other host/storage profiles fail closed.

## Background indexing API

`POST /api/indexing-runs` accepts `{"requestKey":"client-generated UUID","sourceIds":[1]}`. Keys use canonical UUID text (case-insensitive input, lowercase storage); Source IDs must be distinct positive integers, with 1–1,000 Sources per request. New acceptance returns `202` and `Location: /api/indexing-runs/{scanRunId}`. An exact replay, regardless of Source order or terminal state, returns `200` with the same execution and never resubmits it. Reusing a key for another Source set returns `409`. A later attempt after failure needs a new key.

ScanRun, ScanRunSource selections, v3 Job, and DISCOVERY stage commit atomically before background submission. Every requested Source must already have current supported local macOS/APFS binding and accepted LocationContext authority. Unbound/unsupported Sources and Windows hosts cannot start v3 scans. The frontend explicitly prepares first-time Sources; rebinding and automatic remount recognition remain deferred. Another active SCAN returns `409` without leaving an orphan request. Scheduling rejection returns `503` but retains a failed attempt that can be read or replayed.

`GET /api/indexing-runs/{scanRunId}` returns durable stage/progress/timestamp state and typed final assignment/hash counts. `GET /api/indexing-runs/source-status` returns the active summary plus the latest v2 or v3 run for every registered Source in one response. Both GETs are read-only and send `Cache-Control: no-store`. `completedWithIssues` is derived from completed hashing counts, not stored as a Job status. React `/sources` starts one v3 run and polls this durable state; SSE, cancellation, retry/resume, and multi-Source UI remain unimplemented.

## Status

V6 migration, membership publication, trusted v3 discovery/reconciliation, downstream candidate guards, duplicate physical-copy counting, and historical execution recovery are implemented. Focused V6 checks and the full validation suite pass, including a four-stage v3 execution with deterministic host observations. See [`docs/STATUS.md`](docs/STATUS.md) for current validation results and the next lifecycle step.
