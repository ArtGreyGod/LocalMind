package com.example.data.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.util.Log
import com.example.domain.engine.EngineLifecycleState
import com.example.domain.engine.GenerationTelemetry
import com.example.domain.engine.HardwareAccelerator
import com.example.domain.engine.InferenceEvent
import com.example.domain.engine.InferenceInput
import com.example.domain.engine.LoadedModelInfo
import com.example.domain.engine.LocalInferenceEngine
import com.example.domain.engine.MemoryStats
import com.example.domain.engine.ModelFormat
import com.example.domain.engine.ModelLoadConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Callback interface invoked synchronously from llama_jni.cpp on the native inference thread.
 */
interface LlamaStreamCallback {
    fun onPrefillComplete(prefillTokens: Int, prefillTimeMs: Long, startsInThought: Boolean)
    fun onToken(tokenText: String)
    fun onCompleted(decodeTokens: Int, decodeTimeMs: Long)
    fun onError(message: String)
}

internal object LlamaCppNativeBridge {
    private const val TAG = "LlamaCppNativeBridge"

    val isLibraryLoaded: Boolean
    val loadErrorMessage: String?

    init {
        var loaded = false
        var err: String? = null
        try {
            System.loadLibrary("llama_jni")
            loaded = true
        } catch (t: UnsatisfiedLinkError) {
            err = "Native llama_jni library unavailable on this ABI (${t.message}). Requires arm64-v8a device."
            Log.e(TAG, err, t)
        }
        isLibraryLoaded = loaded
        loadErrorMessage = err
    }

    external fun nativeInit()
    external fun nativeLoadModel(
        modelPath: String,
        mmprojPath: String?,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        temperature: Float,
        topP: Float,
        topK: Int
    ): Int

    external fun nativeGenerateStream(
        systemPrompt: String?,
        userPrompt: String,
        rgbBytes: ByteArray?,
        imgWidth: Int,
        imgHeight: Int,
        maxTokens: Int,
        enableThinking: Boolean,
        thinkingBudgetTokens: Int,
        callback: LlamaStreamCallback
    ): Int

    external fun nativeCancelGeneration()
    external fun nativeRollbackLastTurn(): Boolean
    external fun nativeResetContext()
    external fun nativeUnloadModel()
    external fun nativeGetStatsJson(): String
}

@Serializable
private data class NativeStatsSnapshot(
    val loaded: Boolean = false,
    val kvUsed: Int = 0,
    val kvMax: Int = 4096,
    val modelBytes: Long = 0L,
    val visionEnabled: Boolean = false,
    val backend: String = "CPU"
)

/**
 * Kotlin wrapper around the C++17 llama.cpp + mtmd JNI bridge for .gguf LLMs and VLMs.
 */
class LlamaCppEngineImpl(
    private val context: Context
) : LocalInferenceEngine {

    companion object {
        private const val TAG = "LlamaCppEngineImpl"
    }

    override val engineId: String = "llama_cpp"

    private val json = Json { ignoreUnknownKeys = true }
    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "LocalMind-LlamaCpp-Worker", 4L * 1024L * 1024L).apply {
            isDaemon = true
        }
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mutex = Mutex()

    private val _activeConfig = MutableStateFlow<ModelLoadConfig?>(null)
    override val activeConfig: StateFlow<ModelLoadConfig?> = _activeConfig.asStateFlow()

    private val _engineState = MutableStateFlow<EngineLifecycleState>(EngineLifecycleState.Idle)
    override val engineState: StateFlow<EngineLifecycleState> = _engineState.asStateFlow()

    private var activeInfo: LoadedModelInfo? = null

    @Volatile
    private var isGenerating: Boolean = false

    override suspend fun loadModel(config: ModelLoadConfig): Result<LoadedModelInfo> =
        withContext(engineDispatcher) {
            mutex.withLock {
                if (!LlamaCppNativeBridge.isLibraryLoaded) {
                    val errMsg = LlamaCppNativeBridge.loadErrorMessage
                        ?: "llama.cpp native library failed to load."
                    _engineState.value = EngineLifecycleState.Error(errMsg)
                    return@withContext Result.failure(UnsatisfiedLinkError(errMsg))
                }

                releaseInternalLocked()
                val startNs = System.nanoTime()
                _activeConfig.value = config
                _engineState.value = EngineLifecycleState.Loading(
                    modelName = config.modelName,
                    stage = if (!config.mmprojPath.isNullOrBlank()) {
                        "Mapping GGUF weights + mtmd vision projector..."
                    } else {
                        "Mapping GGUF weights (mmap)..."
                    }
                )

                val modelFile = File(config.modelPath)
                if (!modelFile.isFile) {
                    val err = IllegalArgumentException("GGUF file not found at ${config.modelPath}")
                    _engineState.value = EngineLifecycleState.Error(err.message!!)
                    return@withContext Result.failure(err)
                }

                LlamaCppNativeBridge.nativeInit()

                val effectiveGpuLayers = when (config.preferredAccelerator) {
                    HardwareAccelerator.CPU -> 0
                    HardwareAccelerator.GPU, HardwareAccelerator.NPU -> config.gpuLayers
                }

                val rc = LlamaCppNativeBridge.nativeLoadModel(
                    modelPath = config.modelPath,
                    mmprojPath = config.mmprojPath,
                    nCtx = config.contextLength,
                    nThreads = config.threads,
                    nGpuLayers = effectiveGpuLayers,
                    temperature = config.temperature,
                    topP = config.topP,
                    topK = config.topK
                )

                if (rc != 0) {
                    val errMsg = "llama.cpp failed to load model (error code $rc). Check available RAM and GGUF integrity."
                    _activeConfig.value = null
                    _engineState.value = EngineLifecycleState.Error(errMsg)
                    return@withContext Result.failure(IllegalStateException(errMsg))
                }

                val stats = parseNativeStats()
                val resolvedAccelerator = if (effectiveGpuLayers > 0 &&
                    !stats.backend.startsWith("CPU", ignoreCase = true)
                ) {
                    HardwareAccelerator.GPU
                } else {
                    HardwareAccelerator.CPU
                }

                val fallbackNote = if (config.preferredAccelerator != HardwareAccelerator.CPU &&
                    resolvedAccelerator == HardwareAccelerator.CPU &&
                    config.gpuLayers > 0
                ) {
                    "GPU offload unavailable; running on ${stats.backend}"
                } else {
                    null
                }

                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val info = LoadedModelInfo(
                    modelName = config.modelName,
                    format = ModelFormat.GGUF,
                    activeAccelerator = resolvedAccelerator,
                    backendDescription = "llama.cpp (${stats.backend})",
                    visionSupported = stats.visionEnabled,
                    loadDurationMs = elapsedMs,
                    fallbackReason = fallbackNote
                )
                activeInfo = info
                _engineState.value = EngineLifecycleState.Ready(info)
                Result.success(info)
            }
        }

    override fun generateStream(input: InferenceInput): Flow<InferenceEvent> = callbackFlow {
        val info = activeInfo
        val config = _activeConfig.value
        if (info == null || config == null || !LlamaCppNativeBridge.isLibraryLoaded) {
            trySend(InferenceEvent.Error("No GGUF model is currently loaded.", recoverable = true))
            close()
            return@callbackFlow
        }

        val job = engineScope.launch {
            if (isGenerating) {
                trySend(InferenceEvent.Error("Generation already active.", recoverable = true))
                close()
                return@launch
            }
            isGenerating = true
            _engineState.value = EngineLifecycleState.Generating(info)
            trySend(InferenceEvent.Started(info.activeAccelerator, info.backendDescription))

            var decodeTokens = 0
            var thoughtTokens = 0
            var prefillTokensRecorded = 0
            var prefillMsRecorded = 1L
            val decodeStartNs = longArrayOf(0L)
            var thinkingParser = ThinkingStreamParser(startInThought = false)
            var errorEmitted = false

            val callback = object : LlamaStreamCallback {
                override fun onPrefillComplete(prefillTokens: Int, prefillTimeMs: Long, startsInThought: Boolean) {
                    prefillTokensRecorded = prefillTokens
                    prefillMsRecorded = max(1L, prefillTimeMs)
                    decodeStartNs[0] = System.nanoTime()
                    thinkingParser = ThinkingStreamParser(startInThought = startsInThought)
                    val tps = (prefillTokens * 1000.0 / prefillMsRecorded).takeIf { it.isFinite() } ?: 0.0
                    trySend(
                        InferenceEvent.PrefillCompleted(
                            prefillTokens = prefillTokens,
                            prefillTimeMs = prefillMsRecorded,
                            prefillTokensPerSec = tps
                        )
                    )
                }

                override fun onToken(tokenText: String) {
                    if (tokenText.isEmpty()) return
                    decodeTokens++
                    val elapsedDecodeMs = max(1L, (System.nanoTime() - decodeStartNs[0]) / 1_000_000L)
                    val curTps = (decodeTokens * 1000.0 / elapsedDecodeMs).takeIf { it.isFinite() } ?: 0.0
                    val chunks = thinkingParser.feed(tokenText)
                    for (chunk in chunks) {
                        if (chunk.isThought) {
                            thoughtTokens++
                        }
                        trySend(
                            InferenceEvent.Token(
                                text = chunk.text,
                                decodeTokens = decodeTokens,
                                elapsedDecodeMs = elapsedDecodeMs,
                                currentDecodeTokPerSec = curTps,
                                isThought = chunk.isThought
                            )
                        )
                    }
                }

                override fun onCompleted(decodeTokens: Int, decodeTimeMs: Long) {
                    val safeDecodeMs = max(1L, decodeTimeMs)
                    val remaining = thinkingParser.flush()
                    val curTps = if (decodeTokens > 0) {
                        (decodeTokens * 1000.0 / safeDecodeMs).takeIf { it.isFinite() } ?: 0.0
                    } else 0.0
                    for (chunk in remaining) {
                        if (chunk.isThought) {
                            thoughtTokens++
                        }
                        trySend(
                            InferenceEvent.Token(
                                text = chunk.text,
                                decodeTokens = decodeTokens,
                                elapsedDecodeMs = safeDecodeMs,
                                currentDecodeTokPerSec = curTps,
                                isThought = chunk.isThought
                            )
                        )
                    }
                    val prefillTps = (prefillTokensRecorded * 1000.0 / prefillMsRecorded).takeIf { it.isFinite() } ?: 0.0
                    trySend(
                        InferenceEvent.Completed(
                            GenerationTelemetry(
                                prefillTokens = prefillTokensRecorded,
                                prefillTimeMs = prefillMsRecorded,
                                prefillTokensPerSec = prefillTps,
                                decodeTokens = decodeTokens,
                                decodeTimeMs = safeDecodeMs,
                                decodeTokensPerSec = curTps,
                                activeAccelerator = info.activeAccelerator,
                                backendLabel = info.backendDescription,
                                thoughtTokens = thoughtTokens
                            )
                        )
                    )
                }

                override fun onError(message: String) {
                    errorEmitted = true
                    trySend(InferenceEvent.Error(message, recoverable = true))
                }
            }

            try {
                val img = input.imageAttachment
                val rc = LlamaCppNativeBridge.nativeGenerateStream(
                    systemPrompt = input.systemPrompt ?: config.systemPrompt,
                    userPrompt = input.prompt,
                    rgbBytes = img?.rgbBytes,
                    imgWidth = img?.width ?: 0,
                    imgHeight = img?.height ?: 0,
                    maxTokens = input.maxTokens,
                    enableThinking = input.enableThinking,
                    thinkingBudgetTokens = input.thinkingBudgetTokens,
                    callback = callback
                )
                if (rc != 0 && !errorEmitted) {
                    trySend(InferenceEvent.Error("Native generation returned status $rc", recoverable = true))
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (oom: OutOfMemoryError) {
                trySend(InferenceEvent.Error("Out of memory in llama.cpp: ${oom.message}", recoverable = false))
            } catch (t: Throwable) {
                trySend(InferenceEvent.Error(t.message ?: "llama.cpp generation error", recoverable = true))
            } finally {
                isGenerating = false
                if (activeInfo != null) {
                    _engineState.value = EngineLifecycleState.Ready(activeInfo!!)
                }
                close()
            }
        }

        awaitClose {
            if (isGenerating && LlamaCppNativeBridge.isLibraryLoaded) {
                LlamaCppNativeBridge.nativeCancelGeneration()
            }
            job.cancel()
        }
    }

    override suspend fun cancelGeneration() {
        if (LlamaCppNativeBridge.isLibraryLoaded) {
            LlamaCppNativeBridge.nativeCancelGeneration()
        }
    }

    override suspend fun rollbackLastTurn(): Boolean =
        withContext(engineDispatcher) {
            if (!LlamaCppNativeBridge.isLibraryLoaded) return@withContext false
            LlamaCppNativeBridge.nativeCancelGeneration()
            mutex.withLock {
                LlamaCppNativeBridge.nativeRollbackLastTurn()
            }
        }

    override suspend fun resetSession() {
        withContext(engineDispatcher) {
            if (LlamaCppNativeBridge.isLibraryLoaded) {
                LlamaCppNativeBridge.nativeCancelGeneration()
                mutex.withLock {
                    LlamaCppNativeBridge.nativeResetContext()
                }
            }
        }
    }

    override suspend fun unloadModel() {
        withContext(engineDispatcher) {
            if (LlamaCppNativeBridge.isLibraryLoaded) {
                LlamaCppNativeBridge.nativeCancelGeneration()
            }
            mutex.withLock {
                releaseInternalLocked()
            }
        }
    }

    override suspend fun getMemoryStats(): MemoryStats = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val nativeStats = parseNativeStats()
        val info = activeInfo
        MemoryStats(
            totalDeviceRamBytes = memInfo.totalMem,
            availableDeviceRamBytes = memInfo.availMem,
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            modelEstimatedFootprintBytes = nativeStats.modelBytes,
            kvCacheUsedTokens = nativeStats.kvUsed,
            kvCacheMaxTokens = nativeStats.kvMax,
            activeBackend = info?.backendDescription ?: nativeStats.backend,
            activeAccelerator = info?.activeAccelerator,
            isLowMemory = memInfo.lowMemory
        )
    }

    override fun onTrimMemory(level: Int) {
        // Warn-first policy handled by InferenceEngineCoordinator; never silently unload mid-chat.
        Log.i(TAG, "onTrimMemory($level): retaining loaded llama.cpp model per warn-first policy")
    }

    private fun releaseInternalLocked() {
        if (LlamaCppNativeBridge.isLibraryLoaded) {
            runCatching { LlamaCppNativeBridge.nativeUnloadModel() }
        }
        activeInfo = null
        _activeConfig.value = null
        _engineState.value = EngineLifecycleState.Idle
    }

    private fun parseNativeStats(): NativeStatsSnapshot {
        if (!LlamaCppNativeBridge.isLibraryLoaded) return NativeStatsSnapshot()
        return runCatching {
            val raw = LlamaCppNativeBridge.nativeGetStatsJson()
            json.decodeFromString<NativeStatsSnapshot>(raw)
        }.getOrDefault(NativeStatsSnapshot())
    }
}
