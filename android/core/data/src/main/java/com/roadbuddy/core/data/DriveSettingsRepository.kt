package com.roadbuddy.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.roadbuddy.core.model.DriveSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.driveSettingsDataStore by preferencesDataStore(name = "drive_settings")

private val AudibleAlertsKey = booleanPreferencesKey("audible_alerts")
private val DetectionEnabledKey = booleanPreferencesKey("detection_enabled")
private val SuggestReroutesKey = booleanPreferencesKey("suggest_reroutes")
private val DefaultSettings = DriveSettings.DEFAULT

class DriveSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.driveSettingsDataStore)

    val settings: Flow<DriveSettings> = dataStore.data
        .map { preferences ->
            DriveSettings(
                audibleAlerts = preferences[AudibleAlertsKey] ?: DefaultSettings.audibleAlerts,
                detectionEnabled = preferences[DetectionEnabledKey] ?: DefaultSettings.detectionEnabled,
                suggestReroutes = preferences[SuggestReroutesKey] ?: DefaultSettings.suggestReroutes,
            )
        }
        .distinctUntilChanged()

    val frameCaptureEnabled: Flow<Boolean> =
        settings.map { it.detectionEnabled }.distinctUntilChanged()

    val detectionUploadsEnabled: Flow<Boolean> =
        settings.map { it.detectionEnabled }.distinctUntilChanged()

    val audibleAlertsEnabled: Flow<Boolean> =
        settings.map { it.audibleAlerts }.distinctUntilChanged()

    val rerouteSuggestionsEnabled: Flow<Boolean> =
        settings.map { it.suggestReroutes }.distinctUntilChanged()

    suspend fun setAudibleAlerts(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[AudibleAlertsKey] = enabled }
    }

    suspend fun setDetectionEnabled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[DetectionEnabledKey] = enabled }
    }

    suspend fun setSuggestReroutes(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[SuggestReroutesKey] = enabled }
    }

    suspend fun reset() {
        dataStore.edit { preferences -> preferences.clear() }
    }
}
