<div align="center">
  <h1>diff2test-android</h1>
  <p><strong>Diff-driven Android ViewModel test generation CLI</strong></p>
  <p>Scan changed ViewModels, plan tests, generate candidate local unit tests, and verify them with Gradle.</p>
  <p>
    <a href="https://github.com/gay00ung/diff2test-android/stargazers">
      <img alt="GitHub stars" src="https://img.shields.io/github/stars/gay00ung/diff2test-android?style=flat-square">
    </a>
    <a href="https://github.com/gay00ung/diff2test-android/releases">
      <img alt="Release ZIP" src="https://img.shields.io/badge/release-d2t.zip-2563eb?style=flat-square">
    </a>
    <a href="https://github.com/gay00ung/diff2test-android/releases">
      <img alt="Homebrew" src="https://img.shields.io/badge/install-Homebrew-fbbf24?style=flat-square&logo=homebrew">
    </a>
    <img alt="Status Preview" src="https://img.shields.io/badge/status-preview-f97316?style=flat-square">
    <img alt="Kotlin 1.9.25" src="https://img.shields.io/badge/kotlin-1.9.25-7f52ff?style=flat-square">
    <img alt="Java 17" src="https://img.shields.io/badge/java-17-437291?style=flat-square">
  </p>
  <p>
    <a href="./README.md">English</a>
    ·
    <a href="./README.ko.md">한국어</a>
  </p>
</div>

<p align="center">
  <img src="./docs/assets/readme-hero.png" alt="d2t workflow banner" width="960">
</p>

> Preview build: the CLI is usable today from source, a release ZIP, or Homebrew. The 1.0 target is a CLI-only release for Android ViewModel local unit test generation and verification. The MCP app remains experimental and is not a transport-bound server.

`diff2test-android` is a Kotlin-based CLI for diff-driven Android ViewModel test generation.

It is currently best understood as a developer preview:

- The CLI is usable today from source or a release ZIP.
- Homebrew distribution is the recommended macOS install path.
- The MCP app is still an experimental catalog scaffold, not a transport-bound MCP server.

## What It Does

Today the project focuses on a narrow workflow:

- detect changed ViewModel files from `git diff`
- analyze changed methods and collaborators
- build a scenario-first `TestPlan`
- generate candidate local unit tests
- verify generated tests with Gradle

The repository is organized into three layers:

- engine modules under `modules/*`
- a local CLI app under `apps/cli`
- an MCP-facing catalog scaffold under `apps/mcp-server`

## 1.0 Direction

The 1.0 promise should stay narrow:

- CLI for diff-driven Android ViewModel local unit test generation and verification
- bring-your-own API key with OpenAI, Anthropic, Gemini, or Responses-compatible gateways
- release ZIP and Homebrew distribution

The current roadmap for that work lives in [`docs/roadmap-1.0.md`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/docs/roadmap-1.0.md), and the final release gate is tracked in [`docs/release-gate-1.0.md`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/docs/release-gate-1.0.md).

## Supported Today

- diff-based ViewModel detection from the current git working tree
- scenario-first planning for changed ViewModels
- generated local unit test candidates
- Gradle verification of generated test targets
- one bounded repair pass for common import and coroutine utility failures when `auto --repair` is enabled
- user-owned AI configuration via `d2t init` and `d2t doctor`
- Homebrew and release ZIP distribution

## Not In 1.0 Yet

- transport-bound MCP server behavior
- instrumented `androidTest` generation
- Compose UI test generation
- end-to-end automatic repair loop
- non-heuristic Kotlin analysis for the primary path

## Install

### Homebrew on macOS

The recommended install path for macOS users is Homebrew.

Direct install without tapping first:

```bash
brew install gay00ung/diff2test-android/d2t
```

Optional tap flow:

```bash
brew tap gay00ung/diff2test-android
brew install d2t
```

### Run from a Release ZIP

Download `d2t.zip` from the latest release and run:

```bash
unzip d2t.zip
cd d2t
./bin/d2t help
```

### Run from Source

```bash
git clone https://github.com/gay00ung/diff2test-android.git
cd diff2test-android
./gradlew test
./d2t help
```

## Quick Start

```bash
d2t init
d2t doctor
d2t auto --ai
d2t verify
```

If you are running from source instead of Homebrew, use `./d2t` instead of `d2t`.

`auto` now writes generated tests and verifies them by default. Add `--repair` if you want one bounded repair pass for common import and coroutine-test utility failures.
Generated output must also pass the built-in quality gate, which rejects placeholder assertions and unresolved `TODO()` scaffolding.

Commands that rely on the current analyzer surface explicit analysis warnings when they are using PSI-backed declaration parsing without symbol resolution.

## AI Configuration

The CLI reads user-level configuration from:

```bash
~/.config/d2t/config.toml
```

Generate a starter config:

```bash
d2t init
```

Inspect the current configuration:

```bash
d2t doctor
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

Then load your shell environment and run:

```bash
source ~/.zshrc
d2t auto --ai
```

## Commands

```bash
d2t init [--force]
d2t doctor
d2t scan
d2t plan path/to/SomeViewModel.kt
d2t generate path/to/SomeViewModel.kt --write [--ai|--no-ai] [--strict-ai]
d2t auto [--ai|--no-ai] [--strict-ai] [--model model-name] [--no-verify] [--repair]
d2t verify :module:testTask
```

When you run `verify` without an explicit Gradle task, the CLI now verifies generated test files for the currently changed ViewModels.

## Homebrew Packaging

This repository already includes:

- a Gradle distribution task: `./gradlew :apps:cli:distZip`
- a Homebrew formula template: [`packaging/homebrew/d2t.rb`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/packaging/homebrew/d2t.rb)
- a release guide: [`docs/homebrew-release.md`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/docs/homebrew-release.md)
- an automatic tag workflow for `main`: [`.github/workflows/tag-release.yml`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/.github/workflows/tag-release.yml)
- a release automation workflow for tagged builds: [`.github/workflows/release.yml`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/.github/workflows/release.yml)

`distZip` creates a runnable CLI bundle that contains:

- the `d2t` launcher
- compiled jars
- runtime dependencies

The generated archive is written under:

```bash
apps/cli/build/distributions/d2t.zip
```

## Current Limitations

- The Kotlin analyzer is PSI-backed, but still lacks symbol resolution for full production fidelity.
- AI generation supports the OpenAI Responses API, the Anthropic Messages API, the Gemini GenerateContent API, and Responses-compatible gateways.
- Repair is still bounded and only covers common generated-test import or coroutine utility failures.
- The MCP app is experimental and not yet a real transport-bound server.

When `--ai` is explicitly enabled, AI generation now fails closed instead of silently pretending success through heuristic fallback. Use `auto` without `--ai` only if you want fallback behavior.

## Legacy Environment Fallback

If no config file exists, the CLI still falls back to environment variables.

- Auth: `D2T_AI_AUTH_TOKEN`, `LLM_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `OPENAI_API_KEY`
- Model: `D2T_AI_MODEL`, `STRIX_LLM`, `ANTHROPIC_MODEL`, `OPENAI_MODEL`
- Base URL: `D2T_AI_BASE_URL`, `LLM_API_BASE`, `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`
- Reasoning: `D2T_REASONING_EFFORT`, `STRIX_RESONING_EFFORT`, `OPENAI_REASONING_EFFORT`
- Connect timeout: `D2T_CONNECT_TIMEOUT_SECONDS`, `LLM_CONNECT_TIMEOUT_SECONDS`, `OPENAI_CONNECT_TIMEOUT_SECONDS`
- Request timeout: `D2T_REQUEST_TIMEOUT_SECONDS`, `LLM_REQUEST_TIMEOUT_SECONDS`, `OPENAI_REQUEST_TIMEOUT_SECONDS`

## Repository Layout

- `apps/cli`: local command entry point
- `apps/mcp-server`: MCP catalog scaffold
- `modules/*`: engine modules
- `prompts/*`: prompt and policy templates
- `docs/*`: architecture and release docs
- `fixtures/*`: sample app and verification fixtures
