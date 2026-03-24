# generate-coroutine-viewmodel-tests

Generate Kotlin tests from a `TestPlan`.

Rules:

- honor project naming and assertion style
- default to `runTest`
- keep one observable outcome per scenario
- avoid verify-only tests without assertions
- reuse project patterns before inventing new abstractions

