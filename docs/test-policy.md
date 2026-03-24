# Test Policy

## Default Rules

- ViewModel business logic defaults to local unit tests.
- Instrumented tests are reserved for Android runtime or UI-coupled behavior.
- Patch preview is the default output mode.
- Auto-commit and auto-push stay disabled.

## Generation Rules

- Use constructor injection before Hilt in unit tests.
- Prefer `runTest` for coroutine entry points.
- Do not generate assertion-free tests.
- Do not stop at happy-path coverage.
- Fail closed when there is no observable outcome to assert.

## Repair Rules

- Retry at most two times.
- Preserve original scenario intent.
- Escalate missing Android setup instead of hiding it.

