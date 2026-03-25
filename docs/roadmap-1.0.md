# 1.0.0 Roadmap

`diff2test-android` should treat `1.0.0` as a focused CLI release:

- CLI for diff-driven Android ViewModel local unit test generation and verification
- bring-your-own AI key and Responses-compatible endpoint support
- Homebrew and release ZIP distribution

Everything outside that scope should stay clearly marked as experimental or post-1.0.

## 1. Scope Lock

- [ ] Define `1.0.0` as `CLI for diff-driven Android ViewModel local unit test generation and verification`
- [ ] Mark MCP as experimental or remove it from the 1.0 promise
- [ ] Explicitly defer `androidTest`, Compose UI test, flaky detection, and PR bot automation

## 2. Real Kotlin Analysis

- [ ] Replace heuristic or stub ViewModel analysis with PSI or compiler-backed analysis
- [ ] Reliably extract public methods, suspend functions, and constructor dependencies
- [ ] Reliably detect `StateFlow`, `SharedFlow`, `LiveData`, and state mutation points
- [ ] Improve changed-symbol mapping so internal edits connect to the correct public API
- [ ] Add regression coverage for representative ViewModel patterns

## 3. Test Generation Quality

- [ ] Guarantee generated tests are syntactically valid Kotlin
- [ ] Eliminate placeholder-style tests from the default path
- [ ] Require at least one meaningful observable assertion per generated scenario
- [ ] Enforce minimum scenario coverage for happy path and error or validation paths
- [ ] Preserve collaborator return types and signatures during generation
- [ ] Add golden or snapshot-style generation regression tests

## 4. Verification Pipeline

- [ ] Make `generate -> verify` the normal happy path
- [ ] Ensure default `verify` behavior always targets generated tests for changed ViewModels
- [ ] Make verify output clearly show target file, Gradle task, filter, and pass or fail result
- [ ] Add failure classification for common generation and runtime issues
- [ ] Fail clearly when verification preconditions are missing

## 5. Repair Loop

- [ ] Either implement one real bounded repair pass or remove repair from the 1.0 surface area
- [ ] If implemented, support import fixes, return type mismatches, basic coroutine fixes, and assertion repair from failure logs
- [ ] Ensure repair never silently changes test intent
- [ ] Add tests for repair decision boundaries

## 6. AI Provider UX

- [ ] Keep `d2t init` and `d2t doctor` as the main configuration flow
- [ ] Keep configuration precedence documented and predictable
- [ ] Keep Responses-compatible support reliable and well messaged
- [ ] Make provider and timeout failures actionable
- [ ] Keep AI progress logging concise and useful
- [x] Support native Anthropic and Gemini adapters alongside Responses-compatible gateways

## 7. Fixture and Validation Matrix

- [ ] Expand fixture coverage beyond the current sample app
- [ ] Add at least five to ten representative ViewModel fixtures
- [ ] Cover simple state updates, validation-heavy flows, loading and success and error transitions, coroutine collaborators, one-shot events, and `SavedStateHandle`
- [ ] Add CI coverage that validates generation and verification across the fixture matrix

## 8. Release and Packaging

- [ ] Stabilize the tag -> release -> asset -> tap flow
- [ ] Ensure release workflow always produces a valid `d2t.zip`
- [ ] Ensure formula update flow is reliable when `HOMEBREW_TAP_TOKEN` is configured
- [ ] Add a Homebrew formula smoke test
- [ ] Keep Gradle version, tag version, and release asset version aligned
- [ ] Document the maintainer fallback flow for manual release recovery

## 9. Product and Documentation Cleanup

- [ ] Update README to reflect the exact 1.0 promise
- [ ] Remove or downgrade wording that overstates MCP readiness
- [ ] Add a short supported vs not-yet-supported section
- [ ] Add troubleshooting guidance for missing diffs, AI timeouts, unsupported endpoints, and verify failures
- [ ] Add one end-to-end example in both English and Korean README

## 10. CLI Stability

- [ ] Finalize command semantics for `scan`, `plan`, `generate`, `auto`, and `verify`
- [ ] Ensure failures return non-zero exit codes consistently
- [ ] Keep help text and examples aligned with real behavior
- [ ] Avoid hidden fallback behavior that changes the effective target unexpectedly
- [ ] Add integration-style CLI tests for the primary user flows

## 11. 1.0.0 Release Gate

- [ ] Analyzer is no longer described as stub or heuristic-only for the primary path
- [ ] Generated tests compile across the fixture matrix
- [ ] Default AI path is understandable and debuggable
- [ ] Verify output is trustworthy
- [ ] Release automation is stable enough to publish without manual patching
- [ ] README accurately matches what the tool really does
- [ ] Homebrew install works against the latest release
