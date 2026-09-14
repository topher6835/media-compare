# Decisions

This file records decisions that have already been made. It does not turn planned capabilities into finalized architecture.

## Project

- Media Compare is a fresh v2 project rather than a continuation of the old tutorial implementation.
- The repository name is `media-compare`, hosted as `topher6835/media-compare` on GitHub.
- Supporting both macOS and Windows is a hard requirement.

## Stack

- Use Java 21.
- Use Spring Boot 4.1.1.
- Use Maven and commit its macOS/Linux and Windows wrappers.
- Use React with TypeScript and Vite for the frontend.
- Use React Router for client-side routing.
- Use SQLite for persistence.
- Use Spring JDBC instead of JPA.
- Use Flyway for schema migrations.
- Use Java NIO for future filesystem work when practical.
- Use FFmpeg and ffprobe for future media inspection and processing.
- Use REST APIs, with SSE as the direction for future server-to-client live/progress updates.
- Allow future AI integrations through pluggable local and cloud providers.

Concrete implementation structures for filesystem scanning, comparison, jobs, SSE, FFmpeg discovery, and AI-provider integration remain undecided.

## Architecture

- Begin as a modular monolith: one Spring Boot backend, one React frontend, and one SQLite catalog. Do not introduce microservices, message queues, a Docker requirement, or a separate worker process initially.
- Keep meaningful internal responsibility boundaries without finalizing Java packages or detailed frontend modules prematurely.
- Catalog files generally, not only media. Apply cheap catalog work broadly and expensive analysis only to selected and supported media.
- Give each registered scan Source a durable internal identity. Treat its absolute path as changeable location/configuration rather than identity, so history can survive moves, remounts, and temporary unavailability.
- Represent a FileEntry as one current or historical filesystem occurrence within a Source. Prefer a Source-relative path and preserve missing history rather than deleting an entry automatically.
- Represent exact bytes with a stable internal ContentRecord identity independent of path and hashing algorithm. Multiple exact duplicate FileEntries may share a ContentRecord; transformed copies must not.
- Attach reusable expensive analysis primarily to ContentRecord so it survives moves, renames, duplicates, later scans, and missing/reappearing copies.
- Treat a trusted exact hash as confirmation of byte identity, while keeping the algorithm explicit and keeping hashes separate from database primary identity. Size and timestamp are optimization signals, not proof.
- Perform lightweight reconciliation before expensive analysis. Platform-specific file IDs may be optional hints but cannot be required cross-platform identity.
- Design traversal and persistence for libraries up to potentially hundreds of thousands of files using streamed discovery, database batching, indexed queries, and selective expensive analysis—not speculative distributed infrastructure.
- Keep ScanRun (the user's requested operation) distinct from Job (durable long-running execution), because future jobs are not limited to scans.
- Make resume database-driven and durable across full application shutdown. Prefer idempotent stages and small committed batches over serialized Java state or fragile iterator cursors.
- Represent a saved index as a persistent WorkingSet backed by the one catalog database. WorkingSets reference ContentRecords, reuse their analysis, and retain useful identity/history when physical copies disappear.
- Use a general AnalysisRecord concept for analysis provenance, version/configuration, and lifecycle, with specialized structures for queryable results. Do not put every result into one generic JSON column.
- Reuse analysis only when type, analyzer/model/provider, version, and configuration are compatible. Preserve prior artifacts when those inputs change.
- Keep filesystem metadata on FileEntry and media-derived metadata in ContentRecord analysis, so a move or rename does not invalidate compatible media analysis.
- Keep face detection/instances/embeddings separate from later human person or group classification.
- Keep AI optional and provider-independent; local and cloud providers may coexist under the same provenance/versioning principles.
- Keep large derived files in a future managed cache rather than as large SQLite BLOBs; store the catalog and compact/queryable artifacts in SQLite.
- Generate plausible matching candidates cheaply before deeper comparison. Do not use full pairwise comparison for large libraries, and make pending candidate work resumable.

These decisions define conceptual boundaries and invariants. They do not finalize SQL schemas, package names, APIs, enums, algorithms, concurrency, or provider interfaces.

## Development

- Favor readable, conventional, learnable code over clever abstractions.
- Implement in small, understandable increments and avoid premature architecture.
- Require explicit authorization before installing or modifying system software.
- Use Codex deliberately for meaningful implementation work; conserve usage when straightforward work can be handled manually.
- Treat the separate planning/ideas ChatGPT conversation as the authority for high-level product decisions.
- Treat the repository and its documentation as the authority for implemented technical state.
- Do not silently promote tentative ideas to confirmed decisions.

## Git

- Use the personal GitHub account `topher6835` for this repository.
- Use the repository-local identity `topher6835 <topher6835@users.noreply.github.com>`.
- Use the `github-personal` SSH alias; `origin` is `git@github-personal:topher6835/media-compare.git`.
- Keep this configuration repository-local and do not disturb other GitHub identities on the machine.
- Do not commit or push unless explicitly requested.
