# Sample App

This fixture contains fake Android-style ViewModels that exist only to test `diff2test-android`.

## Included Targets

- `app/src/main/java/com/example/auth/SignUpViewModel.kt`
- `app/src/main/java/com/example/auth/LoginViewModel.kt`
- `app/src/main/java/com/example/profile/EditProfileViewModel.kt`

## Example Flow

1. Edit one of the sample ViewModels.
2. Run `./gradlew :apps:cli:run --args="scan"`.
3. Run `./gradlew :apps:cli:run --args="plan"`.
4. Run `./gradlew :apps:cli:run --args="generate fixtures/sample-app/app/src/main/java/com/example/auth/SignUpViewModel.kt --write"`.
5. Verify the generated sample test with `./gradlew -p fixtures/sample-app :app:test --tests '*SignUpViewModelGeneratedTest'`.

The generated test scaffold will be written under the sample module root:

- `fixtures/sample-app/app/src/test/kotlin/...`
