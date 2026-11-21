package com.example.data.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import com.example.data.media.ImagePreprocessor
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
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Dual-API LiteRT implementation supporting:
 * 1. LiteRT-LM (.litertlm) for multi-turn generative LLMs and multimodal VLMs (Gemma 4 / FastVLM).
 * 2. LiteRT CompiledModel (.tflite) with pre-allocated reusable TensorBuffers and warm-up verification.
 *
 * Enforces hardware fallback priority: NPU -> GPU -> CPU.
 */
class LiteRtEngineImpl(
    private val context: Context,
    private val imagePreprocessor: ImagePreprocessor
) : LocalInferenceEngine {

    companion object {
        private const val TAG = "LiteRtEngineImpl"
        private const val WARMUP_IMAGE_DIM = 224
    }

    override val engineId: String = "litert"

    private val engineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalMind-LiteRT-Worker").apply { isDaemon = true }
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mutex = Mutex()

    private val _activeConfig = MutableStateFlow<ModelLoadConfig?>(null)
    override val activeConfig: StateFlow<ModelLoadConfig?> = _activeConfig.asStateFlow()

    private val _engineState = MutableStateFlow<EngineLifecycleState>(EngineLifecycleState.Idle)
    override val engineState: StateFlow<EngineLifecycleState> = _engineState.asStateFlow()

    // LiteRT-LM runtime handles (owned strictly by engineDispatcher)
    private var lmEngine: Engine? = null
    private var lmConversation: Conversation? = null
    private val lmHistory = mutableListOf<Message>()
    private var lmThinkingEnabled: Boolean = true
    private var lmThinkingBudget: Int = 1024

    // LiteRT CompiledModel (.tflite) handles (owned strictly by engineDispatcher)
    private val litertEnvironment by lazy { Environment.create() }
    private var compiledModel: CompiledModel? = null
    private val tfliteInputs = mutableListOf<TensorBuffer>()
    private val tfliteOutputs = mutableListOf<TensorBuffer>()

    private var activeInfo: LoadedModelInfo? = null
    private var modelFileBytes: Long = 0L
    private var estimatedTurnsTokens: Int = 0

    @Volatile
    private var isGenerating: Boolean = false

    override suspend fun loadModel(config: ModelLoadConfig): Result<LoadedModelInfo> =
        withContext(engineDispatcher) {
            mutex.withLock {
                releaseInternalLocked()
                val startNs = System.nanoTime()
                _activeConfig.value = config
                _engineState.value = EngineLifecycleState.Loading(
                    modelName = config.modelName,
                    stage = "Initializing ${config.format.label} runtime..."
                )

                val modelFile = File(config.modelPath)
                if (!modelFile.isFile) {
                    val err = IllegalArgumentException("Model file not found at ${config.modelPath}")
                    _engineState.value = EngineLifecycleState.Error(err.message ?: "Missing model")
                    return@withContext Result.failure(err)
                }
                modelFileBytes = modelFile.length()

                val fallbackChain = buildAcceleratorPriority(config.preferredAccelerator)
                val failureNotes = mutableListOf<String>()

                when (config.format) {
                    ModelFormat.LITERT_LM -> {
                        for (acc in fallbackChain) {
                            _engineState.value = EngineLifecycleState.Loading(
                                modelName = config.modelName,
                                stage = "Probing ${acc.label} delegate..."
                            )
                            val attempt = tryInitLiteRtLm(config, acc)
                            if (attempt.isSuccess) {
                                val visionEnabled = attempt.getOrThrow()
                                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                                val info = LoadedModelInfo(
                                    modelName = config.modelName,
                                    format = ModelFormat.LITERT_LM,
                                    activeAccelerator = acc,
                                    backendDescription = "LiteRT-LM (${acc.label})",
                                    visionSupported = visionEnabled,
                                    loadDurationMs = elapsedMs,
                                    fallbackReason = failureNotes.takeIf { it.isNotEmpty() }?.joinToString("; ")
                                )
                                activeInfo = info
                                estimatedTurnsTokens = 0
                                _engineState.value = EngineLifecycleState.Ready(info)
                                return@withContext Result.success(info)
                            } else {
                                val reason = "${acc.label}: ${attempt.exceptionOrNull()?.message ?: "init failed"}"
                                Log.w(TAG, "LiteRT-LM fallback from $reason")
                                failureNotes += reason
                            }
                        }
                    }

                    ModelFormat.LITERT_TFLITE -> {
                        for (acc in fallbackChain) {
                            _engineState.value = EngineLifecycleState.Loading(
                                modelName = config.modelName,
                                stage = "Compiling .tflite graph on ${acc.label}..."
                            )
                            val attempt = tryInitCompiledModel(config, acc)
                            if (attempt.isSuccess) {
                                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                                val info = LoadedModelInfo(
                                    modelName = config.modelName,
                                    format = ModelFormat.LITERT_TFLITE,
                                    activeAccelerator = acc,
                                    backendDescription = "LiteRT CompiledModel (${acc.label})",
                                    visionSupported = true,
                                    loadDurationMs = elapsedMs,
                                    fallbackReason = failureNotes.takeIf { it.isNotEmpty() }?.joinToString("; ")
                                )
                                activeInfo = info
                                estimatedTurnsTokens = 0
                                _engineState.value = EngineLifecycleState.Ready(info)
                                return@withContext Result.success(info)
                            } else {
                                val reason = "${acc.label}: ${attempt.exceptionOrNull()?.message ?: "compile failed"}"
                                Log.w(TAG, "LiteRT CompiledModel fallback from $reason")
                                failureNotes += reason
                            }
                        }
                    }

                    ModelFormat.GGUF -> {
                        val err = IllegalArgumentException("GGUF models must be routed to LlamaCppEngineImpl")
                        _engineState.value = EngineLifecycleState.Error(err.message!!)
                        return@withContext Result.failure(err)
                    }
                }

                val summary = "All accelerators failed (${failureNotes.joinToString(" | ")})"
                _engineState.value = EngineLifecycleState.Error(summary)
                _activeConfig.value = null
                Result.failure(IllegalStateException(summary))
            }
        }

    override fun generateStream(input: InferenceInput): Flow<InferenceEvent> = channelFlow {
        val info = activeInfo
        val config = _activeConfig.value
        if (info == null || config == null) {
            trySend(InferenceEvent.Error("No LiteRT model is currently loaded.", recoverable = true))
            return@channelFlow
        }

        withContext(engineDispatcher) {
            if (isGenerating) {
                trySend(InferenceEvent.Error("Inference already in progress.", recoverable = true))
                return@withContext
            }
            isGenerating = true
            _engineState.value = EngineLifecycleState.Generating(info)
            trySend(InferenceEvent.Started(info.activeAccelerator, info.backendDescription))

            val startNs = System.nanoTime()
            var firstTokenNs: Long? = null
            var decodeTokens = 0
            val approxPromptTokens = max(1, (input.prompt.length / 4) + input.imageAttachments.size * 256)

            try {
                when (config.format) {
                    ModelFormat.LITERT_LM -> {
                        val eng = checkNotNull(lmEngine) { "LiteRT-LM engine missing" }
                        val effectiveSystemPrompt = input.systemPrompt ?: config.systemPrompt
                        val systemPromptChanged = lmHistory.isEmpty() && effectiveSystemPrompt != config.systemPrompt
                        if (lmConversation == null ||
                            input.enableThinking != lmThinkingEnabled ||
                            input.thinkingBudgetTokens != lmThinkingBudget ||
                            systemPromptChanged
                        ) {
                            val updatedConfig = config.copy(
                                systemPrompt = if (systemPromptChanged) effectiveSystemPrompt else config.systemPrompt,
                                enableThinking = input.enableThinking,
                                thinkingBudgetTokens = input.thinkingBudgetTokens
                            )
                            _activeConfig.value = updatedConfig
                            runCatching { lmConversation?.close() }
                            lmConversation = createLiteRtLmConversation(eng, updatedConfig, lmHistory.toList())
                        }
                        val conv = checkNotNull(lmConversation) { "LiteRT-LM conversation missing" }
                        val promptMessage: Message = if (input.imageAttachments.isNotEmpty()) {
                            Message.user(
                                Contents.of(
                                    input.imageAttachments.map { Content.ImageBytes(it.jpegBytes) } +
                                        Content.Text(input.prompt)
                                )
                            )
                        } else {
                            Message.user(input.prompt)
                        }

                        var thoughtTokens = 0
                        val thinkingParser = ThinkingStreamParser(startInThought = false)
                        val collectedAnswer = StringBuilder()
                        val collectedThought = StringBuilder()

                        fun recordFirstTokenTiming(nowNs: Long) {
                            if (firstTokenNs == null) {
                                firstTokenNs = nowNs
                                val prefillMs = max(1L, (nowNs - startNs) / 1_000_000L)
                                val prefillTps = approxPromptTokens * 1000.0 / prefillMs
                                trySend(
                                    InferenceEvent.PrefillCompleted(
                                        prefillTokens = approxPromptTokens,
                                        prefillTimeMs = prefillMs,
                                        prefillTokensPerSec = prefillTps
                                    )
                                )
                            }
                        }

                        conv.sendMessageAsync(promptMessage).collect { chunk ->
                            val channelThought = chunk.channels["thought"]
                                ?: chunk.channels["reasoning"]
                                ?: ""
                            val chunkText = chunk.toString()

                            if (channelThought.isNotEmpty() || chunkText.isNotEmpty()) {
                                val nowNs = System.nanoTime()
                                recordFirstTokenTiming(nowNs)
                                decodeTokens++
                                val decodeElapsedMs = max(1L, (nowNs - (firstTokenNs ?: nowNs)) / 1_000_000L)
                                val currentTps = decodeTokens * 1000.0 / decodeElapsedMs

                                if (channelThought.isNotEmpty()) {
                                    thoughtTokens++
                                    collectedThought.append(channelThought)
                                    trySend(
                                        InferenceEvent.Token(
                                            text = channelThought,
                                            decodeTokens = decodeTokens,
                                            elapsedDecodeMs = decodeElapsedMs,
                                            currentDecodeTokPerSec = currentTps,
                                            isThought = true
                                        )
                                    )
                                }

                                if (chunkText.isNotEmpty()) {
                                    if (channelThought.isNotEmpty()) {
                                        collectedAnswer.append(chunkText)
                                        trySend(
                                            InferenceEvent.Token(
                                                text = chunkText,
                                                decodeTokens = decodeTokens,
                                                elapsedDecodeMs = decodeElapsedMs,
                                                currentDecodeTokPerSec = currentTps,
                                                isThought = false
                                            )
                                        )
                                    } else {
                                        val parsed = thinkingParser.feed(chunkText)
                                        for (part in parsed) {
                                            if (part.isThought) {
                                                thoughtTokens++
                                                collectedThought.append(part.text)
                                            } else {
                                                collectedAnswer.append(part.text)
                                            }
                                            trySend(
                                                InferenceEvent.Token(
                                                    text = part.text,
                                                    decodeTokens = decodeTokens,
                                                    elapsedDecodeMs = decodeElapsedMs,
                                                    currentDecodeTokPerSec = currentTps,
                                                    isThought = part.isThought
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val remaining = thinkingParser.flush()
                        if (remaining.isNotEmpty()) {
                            val nowNs = System.nanoTime()
                            val decodeElapsedMs = max(1L, (nowNs - (firstTokenNs ?: nowNs)) / 1_000_000L)
                            val currentTps = if (decodeTokens > 0) decodeTokens * 1000.0 / decodeElapsedMs else 0.0
                            for (part in remaining) {
                                if (part.isThought) {
                                    thoughtTokens++
                                    collectedThought.append(part.text)
                                } else {
                                    collectedAnswer.append(part.text)
                                }
                                trySend(
                                    InferenceEvent.Token(
                                        text = part.text,
                                        decodeTokens = decodeTokens,
                                        elapsedDecodeMs = decodeElapsedMs,
                                        currentDecodeTokPerSec = currentTps,
                                        isThought = part.isThought
                                    )
                                )
                            }
                        }

                        val answerStr = collectedAnswer.toString()
                        val thoughtStr = collectedThought.toString()
                        if (answerStr.isNotEmpty() || thoughtStr.isNotEmpty()) {
                            runCatching {
                                lmHistory.add(promptMessage)
                                val channelsMap = if (thoughtStr.isNotEmpty()) mapOf("thought" to thoughtStr) else emptyMap()
                                val safeAnswer = answerStr.ifEmpty { " " }
                                lmHistory.add(
                                    Message.model(
                                        contents = Contents.of(safeAnswer),
                                        channels = channelsMap
                                    )
                                )
                            }.onFailure { err ->
                                Log.w(TAG, "Failed to append LiteRT-LM history on turn completion", err)
                            }
                        }

                        val endNs = System.nanoTime()
                        val prefillMs = max(1L, ((firstTokenNs ?: endNs) - startNs) / 1_000_000L)
                        val decodeMs = max(1L, (endNs - (firstTokenNs ?: endNs)) / 1_000_000L)
                        estimatedTurnsTokens += approxPromptTokens + decodeTokens
                        val safePrefillTps = (approxPromptTokens * 1000.0 / prefillMs).takeIf { it.isFinite() } ?: 0.0
                        val safeDecodeTps = if (decodeTokens > 0) {
                            (decodeTokens * 1000.0 / decodeMs).takeIf { it.isFinite() } ?: 0.0
                        } else 0.0
                        trySend(
                            InferenceEvent.Completed(
                                GenerationTelemetry(
                                    prefillTokens = approxPromptTokens,
                                    prefillTimeMs = prefillMs,
                                    prefillTokensPerSec = safePrefillTps,
                                    decodeTokens = decodeTokens,
                                    decodeTimeMs = decodeMs,
                                    decodeTokensPerSec = safeDecodeTps,
                                    activeAccelerator = info.activeAccelerator,
                                    backendLabel = info.backendDescription,
                                    thoughtTokens = thoughtTokens
                                )
                            )
                        )
                    }

                    ModelFormat.LITERT_TFLITE -> {
                        val model = checkNotNull(compiledModel) { "CompiledModel missing" }
                        val inputVector = prepareCompiledModelInput(input)
                        tfliteInputs[0].writeFloat(inputVector)
                        model.run(tfliteInputs, tfliteOutputs)
                        val rawOutput = tfliteOutputs[0].readFloat()
                        val elapsedMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)

                        val summaryText = buildString {
                            append("[LiteRT CompiledModel on ${info.activeAccelerator.label}]\n")
                            append("Executed tensor graph in ${elapsedMs} ms.\n")
                            append("Output tensor size: ${rawOutput.size} logits.\n")
                            val topIndices = rawOutput.indices
                                .sortedByDescending { rawOutput[it] }
                                .take(5)
                            append("Top activations: ")
                            append(
                                topIndices.joinToString(", ") { idx ->
                                    "#$idx=${"%.4f".format(rawOutput[idx])}"
                                }
                            )
                        }
                        trySend(
                            InferenceEvent.PrefillCompleted(
                                prefillTokens = inputVector.size,
                                prefillTimeMs = elapsedMs,
                                prefillTokensPerSec = inputVector.size * 1000.0 / elapsedMs
                            )
                        )
                        trySend(
                            InferenceEvent.Token(
                                text = summaryText,
                                decodeTokens = 1,
                                elapsedDecodeMs = elapsedMs,
                                currentDecodeTokPerSec = 1000.0 / elapsedMs
                            )
                        )
                        trySend(
                            InferenceEvent.Completed(
                                GenerationTelemetry(
                                    prefillTokens = inputVector.size,
                                    prefillTimeMs = elapsedMs,
                                    prefillTokensPerSec = inputVector.size * 1000.0 / elapsedMs,
                                    decodeTokens = 1,
                                    decodeTimeMs = elapsedMs,
                                    decodeTokensPerSec = 1000.0 / elapsedMs,
                                    activeAccelerator = info.activeAccelerator,
                                    backendLabel = info.backendDescription
                                )
                            )
                        )
                    }

                    ModelFormat.GGUF -> Unit
                }
            } catch (e: CancellationException) {
                runCatching { lmConversation?.cancelProcess() }
                throw e
            } catch (oom: OutOfMemoryError) {
                trySend(InferenceEvent.Error("Out of memory during LiteRT inference: ${oom.message}", recoverable = false))
            } catch (t: Throwable) {
                trySend(InferenceEvent.Error(t.message ?: "LiteRT inference failed", recoverable = true))
            } finally {
                isGenerating = false
                if (activeInfo != null) {
                    _engineState.value = EngineLifecycleState.Ready(activeInfo!!)
                }
            }
        }
    }

    override suspend fun cancelGeneration() {
        withContext(engineDispatcher) {
            if (isGenerating) {
                runCatching { lmConversation?.cancelProcess() }
            }
        }
    }

    override suspend fun rollbackLastTurn(): Boolean =
        withContext(engineDispatcher) {
            mutex.withLock {
                val cfg = _activeConfig.value ?: return@withContext false
                val eng = lmEngine ?: return@withContext false
                if (lmHistory.size < 2) return@withContext false
                while (isGenerating) {
                    runCatching { lmConversation?.cancelProcess() }
                    delay(50)
                }
                lmHistory.removeAt(lmHistory.lastIndex)
                lmHistory.removeAt(lmHistory.lastIndex)
                runCatching { lmConversation?.close() }
                lmConversation = createLiteRtLmConversation(eng, cfg, lmHistory.toList())
                true
            }
        }

    override suspend fun resetSession() {
        withContext(engineDispatcher) {
            mutex.withLock {
                val cfg = _activeConfig.value ?: return@withLock
                val eng = lmEngine ?: return@withLock
                while (isGenerating) {
                    runCatching { lmConversation?.cancelProcess() }
                    delay(50)
                }
                runCatching { lmConversation?.close() }
                lmConversation = null
                lmHistory.clear()
                lmConversation = createLiteRtLmConversation(eng, cfg)
                estimatedTurnsTokens = 0
            }
        }
    }

    override suspend fun unloadModel() {
        withContext(engineDispatcher) {
            mutex.withLock {
                releaseInternalLocked()
            }
        }
    }

    override suspend fun getMemoryStats(): MemoryStats = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val info = activeInfo
        MemoryStats(
            totalDeviceRamBytes = memInfo.totalMem,
            availableDeviceRamBytes = memInfo.availMem,
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            modelEstimatedFootprintBytes = modelFileBytes,
            kvCacheUsedTokens = estimatedTurnsTokens,
            kvCacheMaxTokens = _activeConfig.value?.contextLength ?: 4096,
            activeBackend = info?.backendDescription ?: "Idle",
            activeAccelerator = info?.activeAccelerator,
            isLowMemory = memInfo.lowMemory
        )
    }

    override fun onTrimMemory(level: Int) {
        // Warn-first policy handled by InferenceEngineCoordinator; never silently unload mid-chat.
        Log.i(TAG, "onTrimMemory($level): retaining loaded LiteRT model per warn-first policy")
    }

    private fun tryInitLiteRtLm(
        config: ModelLoadConfig,
        accelerator: HardwareAccelerator
    ): Result<Boolean> {
        val backend = mapToLiteRtLmBackend(accelerator)
        val wantsVision = config.modelName.contains("gemma-4", ignoreCase = true) ||
            config.modelName.contains("gemma4", ignoreCase = true) ||
            config.modelName.contains("fastvlm", ignoreCase = true) ||
            config.modelName.contains("vision", ignoreCase = true) ||
            config.modelName.contains("vl", ignoreCase = true)

        // First try with visionBackend if likely multimodal; fall back to text-only if bundle lacks vision graph.
        val candidates = if (wantsVision) {
            listOf(Backend.GPU(), Backend.CPU(), null)
        } else {
            listOf(null)
        }

        var lastError: Throwable? = null
        for (visionCandidate in candidates) {
            var candidateEngine: Engine? = null
            try {
                val engineConfig = EngineConfig(
                    modelPath = config.modelPath,
                    backend = backend,
                    visionBackend = visionCandidate,
                    cacheDir = context.cacheDir.path
                )
                candidateEngine = Engine(engineConfig)
                candidateEngine.initialize()

                val conv = createLiteRtLmConversation(candidateEngine, config)
                lmEngine = candidateEngine
                lmConversation = conv
                return Result.success(visionCandidate != null)
            } catch (oom: OutOfMemoryError) {
                lastError = oom
                // Never close an Engine whose initialize() threw; clear reference only.
                candidateEngine = null
            } catch (e: Exception) {
                lastError = e
                candidateEngine = null
            }
        }
        return Result.failure(lastError ?: IllegalStateException("LiteRT-LM initialization failed"))
    }

    private fun createLiteRtLmConversation(
        engine: Engine,
        config: ModelLoadConfig,
        initialMessages: List<Message> = emptyList()
    ): Conversation {
        val thinkingConfig = ThinkingConfig(
            enableThinking = config.enableThinking,
            thinkingTokenBudget = if (config.enableThinking) config.thinkingBudgetTokens else 0
        )
        val convConfig = ConversationConfig(
            systemInstruction = Contents.of(config.systemPrompt),
            initialMessages = initialMessages,
            samplerConfig = SamplerConfig(
                topK = config.topK.coerceAtLeast(1),
                topP = config.topP.toDouble().coerceIn(0.05, 1.0),
                temperature = config.temperature.toDouble().coerceIn(0.0, 2.0)
            ),
            thinkingConfig = thinkingConfig
        )
        lmThinkingEnabled = config.enableThinking
        lmThinkingBudget = config.thinkingBudgetTokens
        return engine.createConversation(convConfig)
    }

    private fun tryInitCompiledModel(
        config: ModelLoadConfig,
        accelerator: HardwareAccelerator
    ): Result<Unit> {
        val litertAcc = when (accelerator) {
            HardwareAccelerator.NPU -> Accelerator.NPU
            HardwareAccelerator.GPU -> Accelerator.GPU
            HardwareAccelerator.CPU -> Accelerator.CPU
        }
        var model: CompiledModel? = null
        return try {
            model = CompiledModel.create(
                config.modelPath,
                CompiledModel.Options(litertAcc),
                litertEnvironment
            )
            val ins = model.createInputBuffers()
            val outs = model.createOutputBuffers()
            tfliteInputs.addAll(ins)
            tfliteOutputs.addAll(outs)

            // Mandatory warm-up inference to verify accelerator graph execution
            if (tfliteInputs.isNotEmpty()) {
                val warmupData = FloatArray(WARMUP_IMAGE_DIM * WARMUP_IMAGE_DIM * 3)
                tfliteInputs[0].writeFloat(warmupData)
                model.run(tfliteInputs, tfliteOutputs)
            }
            compiledModel = model
            Result.success(Unit)
        } catch (e: LiteRtException) {
            closeCompiledModelBuffers()
            runCatching { model?.close() }
            Result.failure(e)
        } catch (oom: OutOfMemoryError) {
            closeCompiledModelBuffers()
            runCatching { model?.close() }
            Result.failure(oom)
        } catch (e: Exception) {
            closeCompiledModelBuffers()
            runCatching { model?.close() }
            Result.failure(e)
        }
    }

    private fun prepareCompiledModelInput(input: InferenceInput): FloatArray {
        val attachment = input.imageAttachment
        if (attachment != null) {
            val bmp = BitmapFactory.decodeByteArray(attachment.jpegBytes, 0, attachment.jpegBytes.size)
            if (bmp != null) {
                return imagePreprocessor.toNormalizedFloatArray(bmp, WARMUP_IMAGE_DIM).also {
                    bmp.recycle()
                }
            }
        }
        val bytes = input.prompt.toByteArray()
        return FloatArray(WARMUP_IMAGE_DIM * WARMUP_IMAGE_DIM * 3) { idx ->
            if (bytes.isEmpty()) 0f else (bytes[idx % bytes.size].toInt() and 0xFF) / 255.0f
        }
    }

    private suspend fun releaseInternalLocked() {
        while (isGenerating) {
            runCatching { lmConversation?.cancelProcess() }
            delay(50)
        }
        runCatching { lmConversation?.close() }
        lmConversation = null
        lmHistory.clear()
        runCatching { lmEngine?.close() }
        lmEngine = null

        closeCompiledModelBuffers()
        runCatching { compiledModel?.close() }
        compiledModel = null

        activeInfo = null
        modelFileBytes = 0L
        estimatedTurnsTokens = 0
        _activeConfig.value = null
        _engineState.value = EngineLifecycleState.Idle
    }

    private fun closeCompiledModelBuffers() {
        tfliteInputs.forEach { runCatching { it.close() } }
        tfliteInputs.clear()
        tfliteOutputs.forEach { runCatching { it.close() } }
        tfliteOutputs.clear()
    }

    private fun mapToLiteRtLmBackend(accelerator: HardwareAccelerator): Backend {
        return when (accelerator) {
            HardwareAccelerator.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
            HardwareAccelerator.GPU -> Backend.GPU()
            HardwareAccelerator.CPU -> Backend.CPU()
        }
    }

    private fun buildAcceleratorPriority(preferred: HardwareAccelerator): List<HardwareAccelerator> {
        return when (preferred) {
            HardwareAccelerator.NPU -> listOf(HardwareAccelerator.NPU, HardwareAccelerator.GPU, HardwareAccelerator.CPU)
            HardwareAccelerator.GPU -> listOf(HardwareAccelerator.GPU, HardwareAccelerator.CPU)
            HardwareAccelerator.CPU -> listOf(HardwareAccelerator.CPU)
        }
    }
}
