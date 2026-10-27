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
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val CUSTOM_MODEL_OVERRIDE = stringPreferencesKey("custom_model_override")
        val VOICE_RESPONSE_ENABLED = booleanPreferencesKey("voice_response_enabled")
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val HIGH_RISK_CONFIRMATION = booleanPreferencesKey("high_risk_confirmation")
        val DEFAULT_CITY = stringPreferencesKey("default_city")
        val AI_INFERENCE_MODE = stringPreferencesKey("ai_inference_mode")
        val LOCAL_MODEL_NAME = stringPreferencesKey("local_model_name")
        val LOCAL_MODEL_PATH = stringPreferencesKey("local_model_path")
        val LOCAL_CONTEXT_LENGTH = intPreferencesKey("local_context_length")
        val HUGGINGFACE_TOKEN = stringPreferencesKey("huggingface_token")
        val SELECTED_LOCAL_MODEL_ID = stringPreferencesKey("selected_local_model_id")
    }

    val apiKeyFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GEMINI_API_KEY] ?: ""
    }

    val modelFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GEMINI_MODEL] ?: "gemini-3.5-flash"
    }

    val providerFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[AI_PROVIDER] ?: "gemini"
    }

    val baseUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[API_BASE_URL] ?: "https://generativelanguage.googleapis.com"
    }

    val customModelOverrideFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_MODEL_OVERRIDE] ?: ""
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

    val aiInferenceModeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[AI_INFERENCE_MODE] ?: "automatic"
    }

    val localModelNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LOCAL_MODEL_NAME] ?: "gemma-2b-it-cpu-int4.bin"
    }

    val localModelPathFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LOCAL_MODEL_PATH] ?: ""
    }

    val localContextLengthFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[LOCAL_CONTEXT_LENGTH] ?: 1024
    }

    val huggingFaceTokenFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[HUGGINGFACE_TOKEN] ?: ""
    }

    val selectedLocalModelIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SELECTED_LOCAL_MODEL_ID] ?: "qwen2.5-0.5b-instruct-litert"
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

    suspend fun saveProvider(provider: String) {
        context.dataStore.edit { prefs ->
            prefs[AI_PROVIDER] = provider
        }
    }

    suspend fun saveBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[API_BASE_URL] = url.trim()
        }
    }

    suspend fun saveCustomModelOverride(model: String) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_MODEL_OVERRIDE] = model.trim()
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

    suspend fun saveAiInferenceMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[AI_INFERENCE_MODE] = mode.lowercase().trim()
        }
    }

    suspend fun saveLocalModelName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[LOCAL_MODEL_NAME] = name.trim()
        }
    }

    suspend fun saveLocalModelPath(path: String) {
        context.dataStore.edit { prefs ->
            prefs[LOCAL_MODEL_PATH] = path.trim()
        }
    }

    suspend fun saveLocalContextLength(length: Int) {
        context.dataStore.edit { prefs ->
            prefs[LOCAL_CONTEXT_LENGTH] = length
        }
    }

    suspend fun saveHuggingFaceToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[HUGGINGFACE_TOKEN] = token.trim()
        }
    }

    suspend fun saveSelectedLocalModelId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[SELECTED_LOCAL_MODEL_ID] = id.trim()
        }
    }
}
