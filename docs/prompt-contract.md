# Prompt Contract

## Planner Input

- Structured ViewModel analysis
- Project test style guide
- Existing test patterns
- Placement policy (`src/test` vs `src/androidTest`)

## Planner Output

- `TestPlan`
- Explicit scenarios
- Required fakes or mocks
- Observable assertions
- Risk level

## Generator Input

- `TestPlan`
- Style guide
- Existing examples
- Module/package target

## Generator Output

- Candidate Kotlin test files
- Warnings when the generator cannot honor style or safety rules

## Repair Input

- Failing test file
- Failure logs
- Original target code
- Project test policy

## Repair Constraint

- Preserve test intent
- Avoid semantic rewrites
- Stop after two attempts

