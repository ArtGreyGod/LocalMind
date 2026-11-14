package com.example.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.example.data.engine.MemoryPressureWarning
import com.example.data.hub.HfModelFile
import com.example.data.hub.HfRepoSummary
import com.example.data.local.DocumentCitationUi
import com.example.data.storage.AppThemeMode
import com.example.domain.engine.GenerationTelemetry
import com.example.domain.engine.HardwareAccelerator
import com.example.domain.engine.LocalModelEntry
import com.example.domain.engine.MemoryStats
import com.example.domain.engine.PreparedImage
import com.example.domain.engine.RamPreflightResult
import java.util.UUID

enum class ChatRole {
    USER,
    ASSISTANT
}

@Immutable
data class ChatMessageUi(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val thinkingText: String = "",
    val isThinking: Boolean = false,
    val imageUriString: String? = null,
    val isStreaming: Boolean = false,
    val isInterrupted: Boolean = false,
    val telemetry: GenerationTelemetry? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val turnOrder: Int = 0,
    val branchGroupId: String = id,
    val revisionIndex: Int = 0,
    val siblingIds: List<String> = listOf(id),
    val citations: List<DocumentCitationUi> = emptyList(),
    val isOutsideContextWindow: Boolean = false
)

@Immutable
data class InferenceTuningConfig(
    val preferredAccelerator: HardwareAccelerator = HardwareAccelerator.NPU,
    val contextLength: Int = 4096,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
    val gpuLayers: Int = 0,
    val temperature: Float = 0.4f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    val enableThinking: Boolean = true,
    val thinkingBudgetTokens: Int = 1024,
    val systemPrompt: String = "You are LocalMind, a helpful, accurate on-device AI assistant."
)

@Immutable
data class PendingPreflightWarning(
    val model: LocalModelEntry,
    val preflight: RamPreflightResult
)

@Immutable
data class HubBrowserState(
    val query: String = "",
    val isSearching: Boolean = false,
    val repos: List<HfRepoSummary> = emptyList(),
    val repoFiles: Map<String, List<HfModelFile>> = emptyMap(),
    val loadingRepoIds: Set<String> = emptySet(),
    val errorMessage: String? = null
)

@Immutable
data class LocalMindUiState(
    val activeThreadId: String? = null,
    val activeThreadTitle: String = "New Thread",
    val activeWorkspaceId: String = "workspace_general",
    val activeWorkspaceName: String = "General",
    val messages: List<ChatMessageUi> = emptyList(),
    val pendingImage: PreparedImage? = null,
    val isPreparingImage: Boolean = false,
    val isIndexingDocument: Boolean = false,
    val liveDecodeTokensPerSec: Double = 0.0,
    val livePrefillTokensPerSec: Double = 0.0,
    val liveDecodeTokenCount: Int = 0,
    val estimatedContextTokens: Int = 0,
    val tuning: InferenceTuningConfig = InferenceTuningConfig(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val autoLoadLastModel: Boolean = true,
    val keepScreenOnDuringInference: Boolean = true,
    val memoryStats: MemoryStats? = null,
    val memoryPressureWarning: MemoryPressureWarning? = null,
    val pendingPreflightWarning: PendingPreflightWarning? = null,
    val statusBanner: String? = null
)
