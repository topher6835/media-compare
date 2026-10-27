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
- Follow the mandatory review and Git approval process below; implementation authorization never permits staging, committing, or pushing.

## Mandatory Review and Git Approval Process

This sequence applies to every development task unless the project owner explicitly changes it.

### Stage A — Codex implementation and summary

Complete only the authorized task, perform appropriate focused validation, and update affected documentation before reporting completion. Report changed files, implementation details, testing results, limitations, and unfinished work. Stop without staging, committing, or pushing.

### Stage B — Independent diff review

The developer sends the Codex summary to ChatGPT for review. After summary approval, ChatGPT requests the actual Git comparison and provides appropriate `git diff` commands using `pbcopy` on macOS. Review all relevant code and documentation, including untracked new files that ordinary `git diff` omits. Implement requested corrections and submit them for review again. Summary approval does not authorize a commit.

### Stage C — Commit authorization

Only after explicit approval of the actual changes may ChatGPT provide targeted `git add` and `git commit` instructions. The developer executes them and returns the resulting SHA. Never stage unrelated or unapproved changes.

### Stage D — Push authorization

Commit approval does not authorize pushing. After SHA confirmation and separate push approval, ChatGPT provides the appropriate `git push` command for the verified branch and remote. Only successful push verification establishes the approved remote baseline.

### Stage E — Next milestone

Identify the next planned milestone from verified repository status and the approved roadmap. Do not automatically begin implementing it.

## Cross-Platform Requirement

Support for both macOS and Windows is a hard requirement.

- Never hard-code macOS-only filesystem paths.
- Use Java NIO for filesystem work when practical.
- Keep platform-specific path separators, executable lookup, quoting, and process invocation in mind.
- The implemented bounded `FfprobeProcessRunner` uses the optional `media-compare.ffprobe.executable` absolute path or, when absent, `ffprobe` from PATH. Preserve this cross-platform configuration; do not assume `/opt/homebrew/bin/ffmpeg`, `/opt/homebrew/bin/ffprobe`, or any other machine-specific executable path.
- Durable video metadata integration and real-Windows runner qualification remain deferred. Future FFmpeg discovery, packaging, or changes to ffprobe discovery require an explicitly scoped design task.

## Documentation Maintenance

Document responsibilities:

- `AGENTS.md`: Persistent development instructions, workflow, safety rules, and approval gates.
- `README.md`: Accurate project overview, supported functionality, setup, and operating instructions.
- `docs/STATUS.md`: Current implementation checkpoint, validation state, outstanding work, and authoritative next step.
- `docs/DECISIONS.md`: Durable approved technical/product decisions and rationale where recorded.
- `docs/ARCHITECTURE.md`: Actual architecture and boundaries, with planned designs clearly distinguished.
- `docs/DATA_MODEL.md`: Implemented persistence structures and contracts, with future models clearly identified.
- Investigation, test, and acceptance reports: Historical evidence and outstanding qualification requirements; do not rewrite evidence merely to align an old report with current implementation.

At the end of every meaningful implementation task:

1. Update `docs/STATUS.md` so it accurately states where development stopped.
2. Update any durable documentation made inaccurate by the change.
3. Record an important new technical or product decision in `docs/DECISIONS.md` when appropriate.
4. Update `docs/ARCHITECTURE.md` if system boundaries or data flow materially changed.
5. Update `docs/DATA_MODEL.md` if persistent data structures or schema changed.
6. Keep documentation concise and factual.
7. Do not record speculative ideas as implemented state.

Update affected documentation before submitting the task summary. Documentation changes belong in the same independent diff review as code changes.

`docs/STATUS.md` must remain trustworthy enough that a completely fresh ChatGPT or Codex session can understand the current implementation, what works, what is missing, and the recommended next step without reconstructing project history. Maintain one unambiguous current checkpoint and one authoritative next recommended action; historical milestone instructions must not compete with them. Explicitly distinguish implemented but uncommitted, reviewed but not committed, committed locally, successfully pushed, validated or accepted, and deferred or blocked states. Never infer review approval, validation, or acceptance from a commit or push, or treat these states as interchangeable.
