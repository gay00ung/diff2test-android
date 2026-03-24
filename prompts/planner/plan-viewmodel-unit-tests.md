# plan-viewmodel-unit-tests

You are given:

- a structured `ViewModelAnalysis`
- a project `StyleGuide`
- existing test patterns
- the target test placement policy

Produce a `TestPlan` that:

- prefers `src/test` for ViewModel business logic
- includes initial, success, and failure scenarios when observable
- names required fakes or mocks explicitly
- contains at least one concrete assertion per scenario
- marks elevated risk when Android runtime is required

