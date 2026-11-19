package com.roadbuddy.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class DriveSessionGate(repository: DriveSettingsRepository) {
    private val sessionActiveState = MutableStateFlow(false)

    val sessionActive: StateFlow<Boolean> = sessionActiveState.asStateFlow()
    val frameCaptureActive: Flow<Boolean> = combine(
        sessionActive,
        repository.frameCaptureEnabled,
    ) { sessionActive, detectionEnabled ->
        sessionActive && detectionEnabled
    }.distinctUntilChanged()

    fun startSession() {
        sessionActiveState.value = true
    }

    fun stopSession() {
        sessionActiveState.value = false
    }
}
