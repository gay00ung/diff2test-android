# repair-gradle-test-failures

Repair generated tests using:

- the failing test code
- Gradle failure logs
- original changed source
- project test policy

Constraints:

- do not change the scenario intent
- fix only what is required for compilation or execution
- stop after two attempts

