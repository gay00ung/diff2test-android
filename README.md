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
./d2t init
./d2t doctor
./d2t auto --ai
```

## AI Setup

The CLI now supports a user-level config file at `~/.config/d2t/config.toml`.

Create a starter config:

```bash
./d2t init
```

Validate the current AI configuration:

```bash
./d2t doctor
```

The config stores only the environment variable name for the API key, not the secret itself.

Example for the official OpenAI Responses API:

```toml
[ai]
enabled = true
provider = "openai"
protocol = "responses-compatible"
api_key_env = "OPENAI_API_KEY"
model = "gpt-5"
base_url = "https://api.openai.com/v1"
connect_timeout_seconds = 30
request_timeout_seconds = 180
```

Example for a local or self-hosted Responses-compatible gateway:

```toml
[ai]
enabled = true
provider = "custom"
protocol = "responses-compatible"
api_key_env = "LLM_API_KEY"
model = "qwen3-coder-next-mlx"
base_url = "http://127.0.0.1:12345"
reasoning_effort = "high"
connect_timeout_seconds = 30
request_timeout_seconds = 300
```

Then export the key in your shell and run:

```bash
source ~/.zshrc
./d2t auto --ai
```

Current note:

- `responses-compatible` endpoints are supported today.
- Native Anthropic-style `messages` transport is not implemented yet.
- If your provider exposes a Responses-compatible gateway, use `provider = "custom"`.

## Commands

```bash
./d2t init [--force]
./d2t doctor
./d2t scan
./d2t plan path/to/SomeViewModel.kt
./d2t generate path/to/SomeViewModel.kt --write [--ai|--no-ai]
./d2t auto [--ai|--no-ai] [--model model-name]
./d2t verify :module:testTask
```

## Legacy Env Fallback

If no config file exists, the CLI still falls back to environment variables.

- Auth: `D2T_AI_AUTH_TOKEN`, `LLM_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `OPENAI_API_KEY`
- Model: `D2T_AI_MODEL`, `STRIX_LLM`, `ANTHROPIC_MODEL`, `OPENAI_MODEL`
- Base URL: `D2T_AI_BASE_URL`, `LLM_API_BASE`, `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`
- Reasoning: `D2T_REASONING_EFFORT`, `STRIX_RESONING_EFFORT`, `OPENAI_REASONING_EFFORT`

## Notes

- The current CLI detects changed ViewModels from git diff and can generate test files automatically.
- The analyzer is still heuristic. Replacing it with real Kotlin AST or symbol analysis remains a priority.
- MCP exposure currently ships as a catalog scaffold, not a transport-bound server implementation.
