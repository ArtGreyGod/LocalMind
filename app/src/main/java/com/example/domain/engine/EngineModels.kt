package com.example.domain.engine

import kotlinx.serialization.Serializable

@Serializable
enum class ModelFormat(val label: String, val extensions: List<String>) {
    LITERT_LM("LiteRT-LM", listOf("litertlm")),
    LITERT_TFLITE("LiteRT", listOf("tflite")),
    GGUF("GGUF", listOf("gguf"))
}

@Serializable
enum class HardwareAccelerator(val label: String) {
    NPU("NPU"),
    GPU("GPU"),
    CPU("CPU")
}

data class ModelLoadConfig(
    val modelId: String,
    val modelName: String,
    val modelPath: String,
    val mmprojPath: String? = null,
    val format: ModelFormat,
    val preferredAccelerator: HardwareAccelerator = HardwareAccelerator.NPU,
    val contextLength: Int = 4096,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
    val systemPrompt: String = "You are LocalMind, a helpful, concise on-device AI assistant.",
    val temperature: Float = 0.4f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val enableThinking: Boolean = true,
    val thinkingBudgetTokens: Int = 1024
)

data class LoadedModelInfo(
    val modelName: String,
    val format: ModelFormat,
    val activeAccelerator: HardwareAccelerator,
    val backendDescription: String,
    val visionSupported: Boolean,
    val loadDurationMs: Long,
    val fallbackReason: String? = null
)

sealed interface EngineLifecycleState {
    data object Idle : EngineLifecycleState
    data class Loading(val modelName: String, val stage: String) : EngineLifecycleState
    data class Ready(val info: LoadedModelInfo) : EngineLifecycleState
    data class Generating(val info: LoadedModelInfo) : EngineLifecycleState
    data class Error(val message: String) : EngineLifecycleState
}

data class PreparedImage(
    val uriString: String,
    val width: Int,
    val height: Int,
    val jpegBytes: ByteArray,
    val rgbBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedImage) return false
        return uriString == other.uriString && width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = uriString.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

data class InferenceInput(
    val prompt: String,
    val imageAttachment: PreparedImage? = null,
    val maxTokens: Int = 512,
    val enableThinking: Boolean = true,
    val thinkingBudgetTokens: Int = 1024,
    val systemPrompt: String? = null
)

@Serializable
data class GenerationTelemetry(
    val prefillTokens: Int,
    val prefillTimeMs: Long,
    val prefillTokensPerSec: Double,
    val decodeTokens: Int,
    val decodeTimeMs: Long,
    val decodeTokensPerSec: Double,
    val activeAccelerator: HardwareAccelerator,
    val backendLabel: String,
    val thoughtTokens: Int = 0
)

sealed interface InferenceEvent {
    data class Started(
        val activeAccelerator: HardwareAccelerator,
        val backendLabel: String
    ) : InferenceEvent

    data class PrefillCompleted(
        val prefillTokens: Int,
        val prefillTimeMs: Long,
        val prefillTokensPerSec: Double
    ) : InferenceEvent

    data class Token(
        val text: String,
        val decodeTokens: Int,
        val elapsedDecodeMs: Long,
        val currentDecodeTokPerSec: Double,
        val isThought: Boolean = false
    ) : InferenceEvent

    data class Completed(val telemetry: GenerationTelemetry) : InferenceEvent

    data class Error(val message: String, val recoverable: Boolean = true) : InferenceEvent
}

data class MemoryStats(
    val totalDeviceRamBytes: Long,
    val availableDeviceRamBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val modelEstimatedFootprintBytes: Long,
    val kvCacheUsedTokens: Int,
    val kvCacheMaxTokens: Int,
    val activeBackend: String,
    val activeAccelerator: HardwareAccelerator?,
    val isLowMemory: Boolean
)

@Serializable
data class LocalModelEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val format: ModelFormat,
    val quantizationLabel: String,
    val supportsNativeVision: Boolean,
    val mmprojFilePath: String? = null,
    val mmprojFileName: String? = null,
    val mmprojSizeBytes: Long = 0L,
    val importedAtMillis: Long
) {
    val isVisionCapable: Boolean
        get() = supportsNativeVision || (format == ModelFormat.GGUF && !mmprojFilePath.isNullOrBlank())

    val totalFootprintBytes: Long
        get() = fileSizeBytes + mmprojSizeBytes
}

data class RamPreflightResult(
    val canSafelyLoad: Boolean,
    val availableRamBytes: Long,
    val totalRamBytes: Long,
    val estimatedRequiredBytes: Long,
    val warningMessage: String? = null
)
