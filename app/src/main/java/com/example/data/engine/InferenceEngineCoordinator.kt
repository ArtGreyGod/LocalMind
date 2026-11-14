package com.example.data.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.example.domain.engine.EngineLifecycleState
import com.example.domain.engine.InferenceEvent
import com.example.domain.engine.InferenceInput
import com.example.domain.engine.LoadedModelInfo
import com.example.domain.engine.LocalInferenceEngine
import com.example.domain.engine.LocalModelEntry
import com.example.domain.engine.MemoryStats
import com.example.domain.engine.ModelFormat
import com.example.domain.engine.ModelLoadConfig
import com.example.domain.engine.RamPreflightResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Coordinates runtime selection between LiteRT-LM / LiteRT CompiledModel and llama.cpp,
 * enforces RAM preflight checks, and handles OS memory trim notifications.
 */
class InferenceEngineCoordinator(
    private val context: Context,
    private val liteRtEngine: LiteRtEngineImpl,
    private val llamaCppEngine: LlamaCppEngineImpl
) {
    private val _activeEngine = MutableStateFlow<LocalInferenceEngine?>(null)
    val activeEngine: StateFlow<LocalInferenceEngine?> = _activeEngine.asStateFlow()

    private val _activeModelEntry = MutableStateFlow<LocalModelEntry?>(null)
    val activeModelEntry: StateFlow<LocalModelEntry?> = _activeModelEntry.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val engineState: StateFlow<EngineLifecycleState>
        get() = _activeEngine.value?.engineState ?: idleStateFlow

    private val idleStateFlow = MutableStateFlow<EngineLifecycleState>(EngineLifecycleState.Idle)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeEngineState(): Flow<EngineLifecycleState> =
        _activeEngine.flatMapLatest { engine ->
            engine?.engineState ?: idleStateFlow
        }

    fun checkRamPreflight(
        model: LocalModelEntry,
        contextLength: Int
    ): RamPreflightResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        // Approximate KV cache + runtime scratchpad headroom (e.g. ~128MB base + ~64KB/token)
        val kvEstimateBytes = contextLength.toLong() * 64L * 1024L
        val runtimeOverheadBytes = 192L * 1024L * 1024L
        val estimatedTotal = model.totalFootprintBytes + kvEstimateBytes + runtimeOverheadBytes

        // Keep at least 15% of total device RAM free for Android OS stability
        val safeBudget = (memInfo.availMem * 0.85).toLong()
        val canLoadSafely = !memInfo.lowMemory && estimatedTotal <= safeBudget

        val warning = if (!canLoadSafely) {
            val neededMb = estimatedTotal / (1024 * 1024)
            val availMb = memInfo.availMem / (1024 * 1024)
            "Model + KV cache needs ~$neededMb MB, while free RAM is $availMb MB. Loading may trigger an OS low-memory kill."
        } else {
            null
        }

        return RamPreflightResult(
            canSafelyLoad = canLoadSafely,
            availableRamBytes = memInfo.availMem,
            totalRamBytes = memInfo.totalMem,
            estimatedRequiredBytes = estimatedTotal,
            warningMessage = warning
        )
    }

    suspend fun loadModel(
        model: LocalModelEntry,
        config: ModelLoadConfig
    ): Result<LoadedModelInfo> {
        // Release any previously active engine before loading new weights
        unloadActiveModel()

        val targetEngine: LocalInferenceEngine = when (model.format) {
            ModelFormat.LITERT_LM, ModelFormat.LITERT_TFLITE -> liteRtEngine
            ModelFormat.GGUF -> llamaCppEngine
        }

        _activeEngine.value = targetEngine
        _activeModelEntry.value = model

        val result = targetEngine.loadModel(config)
        if (result.isFailure) {
            // Keep engine reference briefly if error state should remain observable, or clear model entry
            _activeModelEntry.value = null
        }
        return result
    }

    fun generateStream(input: InferenceInput): Flow<InferenceEvent> {
        val engine = _activeEngine.value ?: return emptyFlow()
        return engine.generateStream(input)
    }

    suspend fun cancelGeneration() {
        _activeEngine.value?.cancelGeneration()
    }

    suspend fun rollbackLastTurn(): Boolean {
        return _activeEngine.value?.rollbackLastTurn() ?: false
    }

    suspend fun resetSession() {
        _activeEngine.value?.resetSession()
    }

    suspend fun unloadActiveModel() {
        liteRtEngine.unloadModel()
        llamaCppEngine.unloadModel()
        _activeEngine.value = null
        _activeModelEntry.value = null
    }

    suspend fun getMemoryStats(): MemoryStats {
        val engine = _activeEngine.value
        if (engine != null) {
            return engine.getMemoryStats()
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return MemoryStats(
            totalDeviceRamBytes = memInfo.totalMem,
            availableDeviceRamBytes = memInfo.availMem,
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            modelEstimatedFootprintBytes = 0L,
            kvCacheUsedTokens = 0,
            kvCacheMaxTokens = 0,
            activeBackend = "No model loaded",
            activeAccelerator = null,
            isLowMemory = memInfo.lowMemory
        )
    }

    fun onTrimMemory(level: Int) {
        liteRtEngine.onTrimMemory(level)
        llamaCppEngine.onTrimMemory(level)
    }
}
