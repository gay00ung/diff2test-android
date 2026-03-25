package com.example.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class OnboardingViewModelTest {
    @Test
    fun `completeOnboarding emits navigate home event`() {
        val viewModel = OnboardingViewModel()

        viewModel.completeOnboarding()

        assertEquals(listOf(OnboardingEvent.NavigateHome), viewModel.events.replayCache)
    }
}
