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
}
