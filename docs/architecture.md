# Architecture

## Layers

1. Engine modules own deterministic analysis, planning contracts, execution, and policy.
2. `apps/cli` wires those modules into local developer workflows.
3. `apps/mcp-server` exposes the same engine contracts as MCP tools, resources, and prompts.

## v1 Boundary

- Target Android ViewModel changes first.
- Prefer local unit tests under `src/test`.
- Treat LLM use as a narrow layer for planning, generation, and repair.
- Keep diff parsing, Kotlin analysis, and Gradle execution deterministic.

## Pipeline

1. Detect changes from git diff or a file watcher.
2. Analyze ViewModel APIs, state holders, and collaborators.
3. Build project-specific test context from style signals and existing examples.
4. Classify between local unit and instrumented tests.
5. Produce a `TestPlan`.
6. Generate candidate tests.
7. Run targeted Gradle tasks.
8. Allow at most two repair attempts.

