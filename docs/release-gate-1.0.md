# 1.0 Release Gate

Use this checklist before creating the first `1.0.0` release.

## Product Scope

- [ ] README describes the tool as a CLI for Android ViewModel local unit test generation and verification
- [ ] MCP remains marked as experimental unless a transport-bound server is implemented
- [ ] `androidTest`, Compose UI tests, and broader provider support are still clearly deferred if unfinished

## Analyzer

- [ ] The default analyzer is source-backed and no longer presented as a stub
- [ ] Public methods, constructor dependencies, and observable holders are covered across the fixture matrix
- [ ] Remaining analyzer limits are surfaced clearly in CLI output

## Generation and Verification

- [ ] `auto` generates and verifies tests by default
- [ ] Generated test files compile in the fixture sample app
- [ ] Verification failures return non-zero status and identify the target class or test filter
- [ ] Bounded repair is opt-in and clearly documented

## Repair Boundary

- [ ] Automatic repair is limited to well-understood generated-test fixes
- [ ] Unsupported failures stop with a clear message instead of silently mutating test intent
- [ ] Repair behavior is covered by unit tests

## Fixture Matrix

- [ ] The fixture sample app covers at least:
  - state-only ViewModels
  - coroutine collaborators
  - SavedStateHandle usage
  - SharedFlow event emission
  - validation-heavy flows
- [ ] `./gradlew -p fixtures/sample-app :app:test` passes on CI

## Release Operations

- [ ] `build.gradle.kts` is the only automatic release source of truth
- [ ] Release workflows only create a new release when the project version is bumped
- [ ] `d2t.zip` is published successfully for the intended tag
- [ ] Homebrew tap automation or manual fallback is documented and tested
