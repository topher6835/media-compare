# Media Compare Agent Guide

## Purpose and Sources of Truth

This repository is the fresh v2 Media Compare project. The repository and its durable documentation are the authority for implemented technical state. A separate planning/ideas ChatGPT conversation is the authority for high-level product decisions.

Do not silently convert tentative ideas into confirmed requirements or architecture. If a choice has not been made, record it as undecided or TODO rather than making the choice during an unrelated task.

Before changing anything, inspect the existing implementation and the relevant documentation. Keep work within the requested scope, and do not change unrelated code.

## Development Philosophy

The project is being built incrementally by a developer returning to coding after a hiatus. Code should be:

- Conventional, readable, and easy to learn from.
- Explicit rather than clever.
- Organized so responsibilities are understandable.
- Developed in small, understandable increments.
- Free of unnecessary abstraction, premature architecture, unnecessary enterprise patterns, and giant generated-looking classes.

Prefer small, understandable classes and functions. Preserve clear boundaries between `backend/` and `frontend/`. Do not introduce frameworks, libraries, dependencies, or patterns casually; add one only when the current task has a concrete need and its cost is justified.

Tests should cover meaningful behavior and important failure cases. Do not add tests merely to inflate the test count.

Use Codex deliberately for meaningful implementation work. Straightforward work may be handled manually when that conserves usage without compromising correctness.

## Incremental Change Rules

- Make the smallest coherent change that completes the requested task.
- Avoid speculative architecture and abstractions intended only for hypothetical future needs.
- Do not refactor working code unless the task calls for it or the refactor is necessary to complete the scoped change safely.
- Do not install or modify system software without explicit authorization.
- Do not commit or push unless explicitly requested.

## Cross-Platform Requirement

Support for both macOS and Windows is a hard requirement.

- Never hard-code macOS-only filesystem paths.
- Use Java NIO for filesystem work when practical.
- Keep platform-specific path separators, executable lookup, quoting, and process invocation in mind.
- Future FFmpeg and ffprobe invocation must use a cross-platform discovery or configuration strategy. Do not assume `/opt/homebrew/bin/ffmpeg`, `/opt/homebrew/bin/ffprobe`, or any other machine-specific executable path.
- Do not decide the FFmpeg/ffprobe discovery strategy until that design is explicitly in scope.

## Documentation Maintenance

At the end of every meaningful implementation task:

1. Update `docs/STATUS.md` so it accurately states where development stopped.
2. Update any durable documentation made inaccurate by the change.
3. Record an important new technical or product decision in `docs/DECISIONS.md` when appropriate.
4. Update `docs/ARCHITECTURE.md` if system boundaries or data flow materially changed.
5. Update `docs/DATA_MODEL.md` if persistent data structures or schema changed.
6. Keep documentation concise and factual.
7. Do not record speculative ideas as implemented state.

`docs/STATUS.md` must remain trustworthy enough that a completely fresh ChatGPT or Codex session can understand the current implementation, what works, what is missing, and the recommended next step without reconstructing project history.
