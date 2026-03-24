# diff2test-android

[![English](https://img.shields.io/badge/Language-English-1f6feb?style=for-the-badge)](./README.md)
[![한국어](https://img.shields.io/badge/언어-한국어-0f9d58?style=for-the-badge)](./README.ko.md)

`diff2test-android` is a Kotlin monorepo for Android test generation driven by code diffs.

The repository is structured around three layers:

- Core engine modules for deterministic analysis, planning, generation, execution, and repair
- A CLI app for local execution and workflow orchestration
- An MCP-facing app that exposes tools, resources, and prompts on top of the same engine contracts

## Current Scope

The current scaffold targets ViewModel-focused local unit tests under `src/test`, with patch preview and bounded repair attempts.

Planned v1 capabilities:

- Detect ViewModel-oriented changes from diffs
- Analyze target APIs, state holders, and collaborators
- Build a scenario-first `TestPlan`
- Generate candidate local unit tests
- Run targeted Gradle verification
- Allow at most two repair attempts

## Repository Layout

- `apps/cli`: local command entry point
- `apps/mcp-server`: MCP catalog and server-facing contracts
- `modules/*`: engine modules
- `prompts/*`: prompt and policy templates
- `docs/*`: architecture and contract documents
- `fixtures/*`: sample app and golden data

## Quick Start

```bash
./gradlew test
./gradlew :apps:cli:run --args="scan"
./gradlew :apps:cli:run --args="plan app/src/main/java/com/example/LoginViewModel.kt"
./gradlew :apps:cli:run --args="generate app/src/main/java/com/example/LoginViewModel.kt"
```

## Notes

- The current CLI uses stubbed analysis to prove the engine boundaries and data contracts.
- The next implementation step is replacing stub analysis with real Kotlin AST or symbol analysis.
- MCP exposure currently ships as a catalog scaffold, not a transport-bound server implementation.

