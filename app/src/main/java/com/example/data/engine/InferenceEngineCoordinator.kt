package com.example.data.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import androidx.compose.runtime.Immutable
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

@Immutable
data class MemoryPressureWarning(
    val level: Int,
    val severityLabel: String,
    val availableRamMb: Long,
    val totalRamMb: Long,
    val timestampMillis: Long = System.currentTimeMillis()
)

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

    private val _memoryPressureWarning = MutableStateFlow<MemoryPressureWarning?>(null)
    val memoryPressureWarning: StateFlow<MemoryPressureWarning?> = _memoryPressureWarning.asStateFlow()

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

    suspend fun saveSessionState(path: String): Boolean {
        return _activeEngine.value?.saveSessionState(path) ?: false
    }

    suspend fun loadSessionState(path: String): Boolean {
        return _activeEngine.value?.loadSessionState(path) ?: false
    }

    suspend fun loadEmbeddingModel(modelPath: String, contextLength: Int = 512) {
        liteRtEngine.loadEmbeddingModel(modelPath, contextLength)
        llamaCppEngine.loadEmbeddingModel(modelPath, contextLength)
    }

    suspend fun unloadEmbeddingModel() {
        liteRtEngine.unloadEmbeddingModel()
        llamaCppEngine.unloadEmbeddingModel()
    }

    suspend fun isEmbeddingAvailable(): Boolean {
        return llamaCppEngine.isEmbeddingAvailable() || liteRtEngine.isEmbeddingAvailable()
    }

    suspend fun embedText(text: String): FloatArray? {
        return llamaCppEngine.embedText(text) ?: liteRtEngine.embedText(text)
    }

    suspend fun recentNativeLogs(): String {
        val llamaLogs = llamaCppEngine.recentNativeLogs()
        val liteRtLogs = liteRtEngine.recentNativeLogs()
        return listOf(llamaLogs, liteRtLogs).filter { it.isNotBlank() }.joinToString("\n")
    }

    suspend fun unloadActiveModel() {
        liteRtEngine.unloadModel()
        llamaCppEngine.unloadModel()
        _activeEngine.value = null
        _activeModelEntry.value = null
        _memoryPressureWarning.value = null
    }

    fun dismissMemoryPressureWarning() {
        _memoryPressureWarning.value = null
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

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        liteRtEngine.onTrimMemory(level)
        llamaCppEngine.onTrimMemory(level)

        // Never auto-unload mid-chat; emit a user-facing warning only on genuine memory pressure
        // (ignoring routine TRIM_MEMORY_UI_HIDDEN=20 and TRIM_MEMORY_BACKGROUND=40 transitions).
        if (_activeModelEntry.value == null) return
        val isGenuinePressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (!isGenuinePressure) return

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024L * 1024L)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val label = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "Critical system memory"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "Critical low memory"
            else -> "Low memory warning"
        }
        _memoryPressureWarning.value = MemoryPressureWarning(
            level = level,
            severityLabel = label,
            availableRamMb = availMb,
            totalRamMb = totalMb
        )
    }
}
