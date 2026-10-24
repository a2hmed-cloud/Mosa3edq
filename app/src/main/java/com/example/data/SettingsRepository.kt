package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "yamosaed_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val VOICE_RESPONSE_ENABLED = booleanPreferencesKey("voice_response_enabled")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val HIGH_RISK_CONFIRMATION = booleanPreferencesKey("high_risk_confirmation")
        val DEFAULT_CITY = stringPreferencesKey("default_city")
    }

    val apiKeyFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GEMINI_API_KEY] ?: ""
    }

    val modelFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GEMINI_MODEL] ?: "gemini-3.5-flash"
    }

    val voiceEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[VOICE_RESPONSE_ENABLED] ?: true
    }

    val wakeWordEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[WAKE_WORD_ENABLED] ?: false
    }

    val highRiskConfirmationFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[HIGH_RISK_CONFIRMATION] ?: true
    }

    val defaultCityFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEFAULT_CITY] ?: "القاهرة"
    }

    suspend fun saveApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[GEMINI_API_KEY] = key.trim()
        }
    }

    suspend fun saveModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[GEMINI_MODEL] = model
        }
    }

    suspend fun setVoiceResponseEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[VOICE_RESPONSE_ENABLED] = enabled
        }
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[WAKE_WORD_ENABLED] = enabled
        }
    }

    suspend fun setHighRiskConfirmation(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[HIGH_RISK_CONFIRMATION] = enabled
        }
    }

    suspend fun setDefaultCity(city: String) {
        context.dataStore.edit { prefs ->
            prefs[DEFAULT_CITY] = city
        }
    }
}
