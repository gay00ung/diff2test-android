package com.example.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SignUpViewModel(
    private val registerUser: RegisterUserUseCase,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SignUpUiState())
    val uiState: StateFlow<SignUpUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SignUpEvent>()
    val events: SharedFlow<SignUpEvent> = _events.asSharedFlow()

    fun onFullNameChanged(value: String) {
        _uiState.update { current -> current.copy(fullName = value, errorMessage = null) }
    }

    fun onEmailChanged(value: String) {
        _uiState.update { current ->
            current.copy(
                email = value.trim().lowercase(),
                errorMessage = null,
            )
        }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { current -> current.copy(password = value, errorMessage = null) }
    }

    fun clearError() {
        _uiState.update { current -> current.copy(errorMessage = null) }
    }

    fun submitRegistration() {
        val snapshot = _uiState.value
        if (
            snapshot.fullName.isBlank() ||
            snapshot.email.isBlank() ||
            !snapshot.email.contains("@") ||
            snapshot.password.length < 8
        ) {
            _uiState.update { current ->
                current.copy(errorMessage = "Please enter a valid email and a password with at least 8 characters")
            }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { current -> current.copy(isSubmitting = true, errorMessage = null) }

            registerUser(
                fullName = snapshot.fullName,
                email = snapshot.email,
                password = snapshot.password,
            ).onSuccess {
                _uiState.update { current ->
                    current.copy(
                        isSubmitting = false,
                        isRegistered = true,
                    )
                }
                _events.emit(SignUpEvent.RegistrationCompleted)
            }.onFailure { throwable ->
                _uiState.update { current ->
                    current.copy(
                        isSubmitting = false,
                        errorMessage = throwable.message ?: "Unknown registration error",
                    )
                }
            }
        }
    }
}
