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

The structures for filesystem scanning, comparison, jobs, SSE, FFmpeg discovery, and AI-provider integration remain undecided.

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
