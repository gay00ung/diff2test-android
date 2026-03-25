package com.example.onboarding

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class OnboardingViewModel : ViewModel() {
    private val _events = MutableSharedFlow<OnboardingEvent>(replay = 1)
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()

    fun completeOnboarding() {
        _events.tryEmit(OnboardingEvent.NavigateHome)
    }
}

sealed interface OnboardingEvent {
    data object NavigateHome : OnboardingEvent
}
