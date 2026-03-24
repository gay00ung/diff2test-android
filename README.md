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

> Preview build: the CLI is usable today from source, a release ZIP, or Homebrew. The MCP app is still a catalog scaffold, not a transport-bound MCP server.

`diff2test-android` is a Kotlin-based CLI for diff-driven Android ViewModel test generation.

It is currently best understood as a developer preview:

- The CLI is usable today from source or a release ZIP.
- Homebrew distribution is the recommended macOS install path.
- The MCP app is still a catalog scaffold, not a transport-bound MCP server.

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
```

If you are running from source instead of Homebrew, use `./d2t` instead of `d2t`.

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
d2t generate path/to/SomeViewModel.kt --write [--ai|--no-ai]
d2t auto [--ai|--no-ai] [--model model-name]
d2t verify :module:testTask
```

## Homebrew Packaging

This repository already includes:

- a Gradle distribution task: `./gradlew :apps:cli:distZip`
- a Homebrew formula template: [`packaging/homebrew/d2t.rb`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/packaging/homebrew/d2t.rb)
- a release guide: [`docs/homebrew-release.md`](/Users/shingayeong/Desktop/projects/gayoung/diff2test-android/docs/homebrew-release.md)

`distZip` creates a runnable CLI bundle that contains:

- the `d2t` launcher
- compiled jars
- runtime dependencies

The generated archive is written under:

```bash
apps/cli/build/distributions/d2t.zip
```

## Current Limitations

- The Kotlin analyzer is still heuristic and should be replaced with PSI or symbol resolution.
- AI generation currently supports Responses-compatible endpoints only.
- Native Anthropic `messages` transport is not implemented yet.
- The repair loop is not implemented end-to-end yet.
- The MCP app is not yet a real transport-bound server.

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
