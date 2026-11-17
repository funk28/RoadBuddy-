package com.roadbuddy.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.roadbuddy.core.model.DriveSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DriveSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun updatesSettingsAndBehaviorGates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = newRepository(scope)

            assertEquals(DriveSettings(), repository.settings.first())

            repository.setAudibleAlerts(false)
            repository.setDetectionEnabled(false)
            repository.setSuggestReroutes(false)

            assertEquals(
                DriveSettings(
                    audibleAlerts = false,
                    detectionEnabled = false,
                    suggestReroutes = false,
                ),
                repository.settings.first(),
            )
            assertFalse(repository.frameCaptureEnabled.first())
            assertFalse(repository.detectionUploadsEnabled.first())
            assertFalse(repository.audibleAlertsEnabled.first())
            assertFalse(repository.rerouteSuggestionsEnabled.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun resetRestoresDefaultSettings() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = newRepository(scope)
            repository.setAudibleAlerts(false)
            repository.setDetectionEnabled(false)
            repository.setSuggestReroutes(false)

            repository.reset()

            assertEquals(DriveSettings(), repository.settings.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun settingsSurviveRepositoryRecreation() = runBlocking {
        val file = File(temporaryFolder.root, "persistent-settings.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = newRepository(firstScope, file)
            repository.setAudibleAlerts(false)
            repository.setDetectionEnabled(false)
            repository.setSuggestReroutes(false)
        } finally {
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = newRepository(secondScope, file)
            assertEquals(
                DriveSettings(
                    audibleAlerts = false,
                    detectionEnabled = false,
                    suggestReroutes = false,
                ),
                repository.settings.first(),
            )
        } finally {
            secondScope.cancel()
        }
    }

    private fun newRepository(scope: CoroutineScope): DriveSettingsRepository {
        val file = File(temporaryFolder.root, "settings-${System.nanoTime()}.preferences_pb")
        return newRepository(scope, file)
    }

    private fun newRepository(scope: CoroutineScope, file: File): DriveSettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return DriveSettingsRepository(dataStore)
    }
}
