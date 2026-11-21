package com.example.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.engine.HardwareAccelerator
import com.example.ui.viewmodel.InferenceTuningConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "localmind_preferences"
)

enum class AppThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Obsidian"),
    LIGHT("Daylight")
}

data class SystemPromptPreset(
    val id: String,
    val label: String,
    val prompt: String
)

object SystemPromptPresets {
    val all = listOf(
        SystemPromptPreset(
            id = "default",
            label = "Default",
            prompt = "You are LocalMind, a helpful, accurate on-device AI assistant."
        ),
        SystemPromptPreset(
            id = "coder",
            label = "Coder",
            prompt = "You are LocalMind, an expert software engineer. Give concise, production-ready code with Markdown fenced blocks and minimal filler."
        ),
        SystemPromptPreset(
            id = "reasoner",
            label = "Analyst",
            prompt = "You are LocalMind, a rigorous analytical assistant. Break problems down step by step, verify edge cases, and present clear structured conclusions."
        ),
        SystemPromptPreset(
            id = "vision",
            label = "Vision",
            prompt = "You are LocalMind, an offline multimodal assistant. Describe visual details accurately, transcribe visible text verbatim, and answer concisely."
        ),
        SystemPromptPreset(
            id = "writer",
            label = "Writer",
            prompt = "You are LocalMind, a crisp technical and creative writer. Use clear headings, bullet points, and well-structured prose."
        )
    )
}

data class PersistedAppSettings(
    val tuning: InferenceTuningConfig = InferenceTuningConfig(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val autoLoadLastModel: Boolean = true,
    val keepScreenOnDuringInference: Boolean = true,
    val lastLoadedModelId: String? = null,
    // Semantic (embedding-based) RAG reranking
    val semanticRetrievalEnabled: Boolean = false,
    val embeddingModelId: String? = null,
    // Biometric app lock
    val appLockEnabled: Boolean = false
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val preferredAccelerator = stringPreferencesKey("preferred_accelerator")
        val contextLength = intPreferencesKey("context_length")
        val threads = intPreferencesKey("threads")
        val gpuLayers = intPreferencesKey("gpu_layers")
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("top_p")
        val topK = intPreferencesKey("top_k")
        val maxTokens = intPreferencesKey("max_tokens")
        val enableThinking = booleanPreferencesKey("enable_thinking")
        val thinkingBudgetTokens = intPreferencesKey("thinking_budget_tokens")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val themeMode = stringPreferencesKey("theme_mode")
        val autoLoadLastModel = booleanPreferencesKey("auto_load_last_model")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val lastLoadedModelId = stringPreferencesKey("last_loaded_model_id")
        val semanticRetrievalEnabled = booleanPreferencesKey("semantic_retrieval_enabled")
        val embeddingModelId = stringPreferencesKey("embedding_model_id")
        val appLockEnabled = booleanPreferencesKey("app_lock_enabled")
    }

    val settings: Flow<PersistedAppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaultTuning = InferenceTuningConfig()
        val acc = prefs[Keys.preferredAccelerator]?.let { name ->
            runCatching { HardwareAccelerator.valueOf(name) }.getOrNull()
        } ?: defaultTuning.preferredAccelerator

        val theme = prefs[Keys.themeMode]?.let { name ->
            runCatching { AppThemeMode.valueOf(name) }.getOrNull()
        } ?: AppThemeMode.SYSTEM

        PersistedAppSettings(
            tuning = InferenceTuningConfig(
                preferredAccelerator = acc,
                contextLength = prefs[Keys.contextLength] ?: defaultTuning.contextLength,
                threads = prefs[Keys.threads] ?: defaultTuning.threads,
                gpuLayers = prefs[Keys.gpuLayers] ?: defaultTuning.gpuLayers,
                temperature = prefs[Keys.temperature] ?: defaultTuning.temperature,
                topP = prefs[Keys.topP] ?: defaultTuning.topP,
                topK = prefs[Keys.topK] ?: defaultTuning.topK,
                maxTokens = prefs[Keys.maxTokens] ?: defaultTuning.maxTokens,
                enableThinking = prefs[Keys.enableThinking] ?: defaultTuning.enableThinking,
                thinkingBudgetTokens = prefs[Keys.thinkingBudgetTokens] ?: defaultTuning.thinkingBudgetTokens,
                systemPrompt = prefs[Keys.systemPrompt] ?: defaultTuning.systemPrompt
            ),
            themeMode = theme,
            autoLoadLastModel = prefs[Keys.autoLoadLastModel] ?: true,
            keepScreenOnDuringInference = prefs[Keys.keepScreenOn] ?: true,
            lastLoadedModelId = prefs[Keys.lastLoadedModelId],
            semanticRetrievalEnabled = prefs[Keys.semanticRetrievalEnabled] ?: false,
            embeddingModelId = prefs[Keys.embeddingModelId],
            appLockEnabled = prefs[Keys.appLockEnabled] ?: false
        )
    }

    suspend fun saveTuning(tuning: InferenceTuningConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.preferredAccelerator] = tuning.preferredAccelerator.name
            prefs[Keys.contextLength] = tuning.contextLength
            prefs[Keys.threads] = tuning.threads
            prefs[Keys.gpuLayers] = tuning.gpuLayers
            prefs[Keys.temperature] = tuning.temperature
            prefs[Keys.topP] = tuning.topP
            prefs[Keys.topK] = tuning.topK
            prefs[Keys.maxTokens] = tuning.maxTokens
            prefs[Keys.enableThinking] = tuning.enableThinking
            prefs[Keys.thinkingBudgetTokens] = tuning.thinkingBudgetTokens
            prefs[Keys.systemPrompt] = tuning.systemPrompt
        }
    }

    suspend fun saveThemeMode(mode: AppThemeMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.themeMode] = mode.name
        }
    }

    suspend fun saveAutoLoadLastModel(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.autoLoadLastModel] = enabled
        }
    }

    suspend fun saveKeepScreenOn(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.keepScreenOn] = enabled
        }
    }

    suspend fun saveLastLoadedModelId(modelId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (modelId == null) {
                prefs.remove(Keys.lastLoadedModelId)
            } else {
                prefs[Keys.lastLoadedModelId] = modelId
            }
        }
    }

    suspend fun saveSemanticRetrieval(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.semanticRetrievalEnabled] = enabled
        }
    }

    suspend fun saveEmbeddingModelId(modelId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (modelId == null) {
                prefs.remove(Keys.embeddingModelId)
            } else {
                prefs[Keys.embeddingModelId] = modelId
            }
        }
    }

    suspend fun saveAppLockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.appLockEnabled] = enabled
        }
    }
}
