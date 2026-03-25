package com.example.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: SearchRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SearchUiState(
            query = savedStateHandle.get<String>("query").orEmpty(),
        ),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun onQueryChanged(value: String) {
        val normalized = value.trim()
        savedStateHandle["query"] = normalized
        _uiState.update { current -> current.copy(query = normalized, errorMessage = null) }
    }

    fun runSearch() {
        val query = _uiState.value.query
        if (query.isBlank()) {
            _uiState.update { current -> current.copy(errorMessage = "Enter a search query") }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { current -> current.copy(isLoading = true, errorMessage = null) }
            repository.search(query)
                .onSuccess { results ->
                    _uiState.update { current ->
                        current.copy(
                            results = results,
                            isLoading = false,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            errorMessage = throwable.message ?: "Search failed",
                        )
                    }
                }
        }
    }
}
