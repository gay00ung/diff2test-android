package com.example.profile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `saveProfile marks ui state as saved when repository succeeds`() = runTest(dispatcher) {
        val repository = object : ProfileRepository {
            override suspend fun updateProfile(nickname: String, bio: String): Result<Unit> = Result.success(Unit)
        }
        val viewModel = EditProfileViewModel(repository, dispatcher)

        viewModel.onNicknameChanged("gayeong")
        viewModel.onBioChanged("Android engineer")
        viewModel.saveProfile()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isSaved)
    }
}
