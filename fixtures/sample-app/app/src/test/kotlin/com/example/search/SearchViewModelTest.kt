package com.example.search

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `uses saved state query as initial state`() = runTest(dispatcher) {
        val savedStateHandle = SavedStateHandle(mapOf("query" to "compose"))
        val repository = object : SearchRepository {
            override suspend fun search(query: String): Result<List<String>> = Result.success(emptyList())
        }

        val viewModel = SearchViewModel(savedStateHandle, repository, dispatcher)

        assertEquals("compose", viewModel.uiState.value.query)
    }

    @Test
    fun `runSearch stores results when repository succeeds`() = runTest(dispatcher) {
        val savedStateHandle = SavedStateHandle()
        val repository = object : SearchRepository {
            override suspend fun search(query: String): Result<List<String>> = Result.success(listOf("Jetpack Compose"))
        }
        val viewModel = SearchViewModel(savedStateHandle, repository, dispatcher)

        viewModel.onQueryChanged(" Compose ")
        viewModel.runSearch()
        advanceUntilIdle()

        assertEquals(listOf("Jetpack Compose"), viewModel.uiState.value.results)
        assertEquals("Compose", savedStateHandle.get<String>("query"))
    }
}
