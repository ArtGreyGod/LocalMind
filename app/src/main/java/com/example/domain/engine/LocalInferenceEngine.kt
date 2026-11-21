package com.example.domain.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Runtime-agnostic abstraction over local LLM and VLM inference backends
 * (LiteRT-LM / LiteRT CompiledModel and llama.cpp JNI).
 */
interface LocalInferenceEngine {
    val engineId: String
    val activeConfig: StateFlow<ModelLoadConfig?>
    val engineState: StateFlow<EngineLifecycleState>

    suspend fun loadModel(config: ModelLoadConfig): Result<LoadedModelInfo>

    fun generateStream(input: InferenceInput): Flow<InferenceEvent>

    suspend fun cancelGeneration()

    suspend fun rollbackLastTurn(): Boolean

    suspend fun resetSession()

    suspend fun unloadModel()

    suspend fun getMemoryStats(): MemoryStats

    fun onTrimMemory(level: Int)

    // --- Optional capabilities (default no-op implementations) ---

    /**
     * Persists the live KV-cache for the current session to [path] so the thread can be
     * resumed without a full re-prefill. Returns false when unsupported or nothing to save.
     */
    suspend fun saveSessionState(path: String): Boolean = false

    /**
     * Restores a previously saved KV-cache state into the current (fresh) session.
     * Returns false when the state is missing or incompatible with the loaded model.
     */
    suspend fun loadSessionState(path: String): Boolean = false

    /** Loads a small GGUF embedding model for semantic retrieval; no-op if unsupported. */
    suspend fun loadEmbeddingModel(modelPath: String, contextLength: Int = 512) = Unit

    suspend fun unloadEmbeddingModel() = Unit

    /** True when an embedding model is loaded and [embedText] can be called. */
    suspend fun isEmbeddingAvailable(): Boolean = false

    /**
     * Embeds [text] into a float vector using the loaded embedding model.
     * Returns null when no embedding model is loaded or inference failed.
     */
    suspend fun embedText(text: String): FloatArray? = null

    /** Recent native warnings/errors for the in-app diagnostics viewer; empty if unsupported. */
    suspend fun recentNativeLogs(): String = ""
}
