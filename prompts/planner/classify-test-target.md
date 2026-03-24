# classify-test-target

Choose between `src/test` and `src/androidTest`.

Prefer `src/test` when:

- the target is a ViewModel
- constructor injection is sufficient
- assertions can run on the JVM

Choose `src/androidTest` only when:

- Android framework fidelity is required
- UI or runtime-only components are involved
- local unit testing would hide behavior critical to the change

