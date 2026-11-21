package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LocalMindApplication
import com.example.data.hub.HfModelFile
import com.example.data.hub.ModelDownloadTask
import com.example.data.local.ChatHistoryRepository
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatThreadEntity
import com.example.data.local.IndexedDocumentEntity
import com.example.data.local.SlashCommandItem
import com.example.data.local.WorkspaceEntity
import com.example.data.storage.AppThemeMode
import com.example.data.storage.ImportProgress
import com.example.data.storage.PersistedAppSettings
import com.example.data.rag.RagEmbeddingProvider
import com.example.domain.engine.EngineLifecycleState
import com.example.domain.engine.InferenceEvent
import com.example.domain.engine.InferenceInput
import com.example.domain.engine.LocalModelEntry
import com.example.domain.engine.ModelLoadConfig
import com.example.domain.engine.PreparedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LocalMindViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LocalMindApplication
    private val modelRepository = app.modelRepository
    private val preferencesRepository = app.preferencesRepository
    private val imagePreprocessor = app.imagePreprocessor
    private val engineCoordinator = app.engineCoordinator
    private val chatHistoryRepository = app.chatHistoryRepository
    private val localRagRepository = app.localRagRepository
    private val huggingFaceHubRepository = app.huggingFaceHubRepository
    private val voiceController = app.voiceController

    val registeredModels: StateFlow<List<LocalModelEntry>> =
        modelRepository.models.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val importProgress: StateFlow<ImportProgress?> =
        modelRepository.importProgress.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val engineState: StateFlow<EngineLifecycleState> =
        engineCoordinator.observeEngineState().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EngineLifecycleState.Idle
        )

    val activeModelEntry: StateFlow<LocalModelEntry?> = engineCoordinator.activeModelEntry

    val workspaces: StateFlow<List<WorkspaceEntity>> =
        chatHistoryRepository.workspaces.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val threads: StateFlow<List<ChatThreadEntity>> =
        chatHistoryRepository.threads.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val hubDownloads: StateFlow<Map<String, ModelDownloadTask>> =
        huggingFaceHubRepository.downloads

    val isListening: StateFlow<Boolean> = voiceController.isListening
    val partialTranscript: StateFlow<String> = voiceController.partialTranscript
    val speakingMessageId: StateFlow<String?> = voiceController.speakingMessageId

    val appLockEnabled: StateFlow<Boolean> = preferencesRepository.settings
        .map { it.appLockEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val semanticRetrievalEnabled: StateFlow<Boolean> = preferencesRepository.settings
        .map { it.semanticRetrievalEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val embeddingModelId: StateFlow<String?> = preferencesRepository.settings
        .map { it.embeddingModelId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _uiState = MutableStateFlow(LocalMindUiState())
    val uiState: StateFlow<LocalMindUiState> = _uiState.asStateFlow()

    private val _hubState = MutableStateFlow(
        HubBrowserState(repos = huggingFaceHubRepository.curatedStarterRepos)
    )
    val hubState: StateFlow<HubBrowserState> = _hubState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val attachedDocuments: StateFlow<List<IndexedDocumentEntity>> =
        _uiState
            .map { (it.activeThreadId ?: "") to it.activeWorkspaceId }
            .distinctUntilChanged()
            .flatMapLatest { (threadId, workspaceId) ->
                if (threadId.isBlank()) emptyFlow()
                else localRagRepository.observeDocuments(threadId = threadId, workspaceId = workspaceId)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val slashCommands: StateFlow<List<SlashCommandItem>> =
        chatHistoryRepository.customTemplates
            .map { templates ->
                builtInActionCommands() + templates.map { tpl ->
                    SlashCommandItem(
                        id = tpl.id,
                        trigger = tpl.trigger,
                        label = tpl.label,
                        description = tpl.promptBody.replace('\n', ' ').take(60),
                        promptBody = tpl.promptBody,
                        isActionCommand = false,
                        isBuiltIn = tpl.isBuiltIn
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = builtInActionCommands()
            )

    private var generationJob: Job? = null
    private var summarizationJob: Job? = null
    private var lastTurnImageAttachment: PreparedImage? = null
    private var attemptedStartupAutoload = false
    private var needsEngineResync = false

    // Per-thread composer drafts and staged image attachments (session-scoped)
    private val draftsByThread = mutableMapOf<String, String>()
    private val pendingImagesByThread = mutableMapOf<String, List<PreparedImage>>()

    // Semantic-RAG embedding provider backed by the engine coordinator
    private val ragEmbeddingProvider = object : RagEmbeddingProvider {
        override val isAvailable: Boolean
            get() = semanticEmbeddingReady.value

        override suspend fun embed(text: String): FloatArray? =
            engineCoordinator.embedText(text)
    }

    private val semanticEmbeddingReady = MutableStateFlow(false)

    private fun engineStateFile(threadId: String): File =
        File(File(app.filesDir, "engine_states").apply { mkdirs() }, "$threadId.bin")

    companion object {
        fun estimateTokenCount(messages: List<ChatMessageUi>): Int {
            val totalChars = messages
                .filterNot { it.isOutsideContextWindow }
                .sumOf { it.text.length + it.thinkingText.length }
            return (totalChars / 4).coerceAtLeast(0)
        }

        fun formatMarkdownTranscript(
            messages: List<ChatMessageUi>,
            modelName: String?,
            includeThoughts: Boolean = true
        ): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            return buildString {
                appendLine("# LocalMind Conversation")
                appendLine()
                appendLine("- **Model:** ${modelName ?: "On-Device Local Model"}")
                appendLine("- **Exported:** ${sdf.format(Date())}")
                appendLine()
                appendLine("---")
                appendLine()
                for (msg in messages) {
                    val speaker = if (msg.role == ChatRole.USER) "User" else "Assistant"
                    appendLine("### $speaker")
                    appendLine()
                    if (includeThoughts && msg.thinkingText.isNotBlank()) {
                        appendLine("<details>")
                        appendLine("<summary>Thought Process</summary>")
                        appendLine()
                        appendLine(msg.thinkingText.trim())
                        appendLine()
                        appendLine("</details>")
                        appendLine()
                    }
                    if (!msg.imageUriString.isNullOrBlank()) {
                        appendLine("_[Attached image: ${msg.imageUriString}]_")
                        appendLine()
                    }
                    appendLine(msg.text.trim())
                    msg.telemetry?.let { t ->
                        val thoughtInfo = if (t.thoughtTokens > 0) " • ${t.thoughtTokens} thought" else ""
                        appendLine()
                        appendLine("<sub>${t.activeAccelerator.label} • Prefill ${"%.1f".format(t.prefillTokensPerSec)} t/s • Decode ${"%.1f".format(t.decodeTokensPerSec)} t/s (${t.decodeTokens} tok$thoughtInfo)</sub>")
                    }
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }.trimEnd()
        }

        private fun builtInActionCommands(): List<SlashCommandItem> = listOf(
            SlashCommandItem(
                id = "cmd_think",
                trigger = "think",
                label = "Enable Thinking",
                description = "Turn step-by-step reasoning stream ON",
                promptBody = "",
                isActionCommand = true,
                isBuiltIn = true
            ),
            SlashCommandItem(
                id = "cmd_direct",
                trigger = "direct",
                label = "Direct Answer Mode",
                description = "Turn thinking stream OFF for faster answers",
                promptBody = "",
                isActionCommand = true,
                isBuiltIn = true
            ),
            SlashCommandItem(
                id = "cmd_trim",
                trigger = "trim",
                label = "Trim Context Window",
                description = "Drop older turns from KV cache while keeping history",
                promptBody = "",
                isActionCommand = true,
                isBuiltIn = true
            ),
            SlashCommandItem(
                id = "cmd_clear",
                trigger = "clear",
                label = "Start New Thread",
                description = "Create a fresh thread and reset KV cache",
                promptBody = "",
                isActionCommand = true,
                isBuiltIn = true
            )
        )
    }

    init {
        hydratePreferencesAndMaybeAutoload()
        initializeDatabaseThread()
        startMemoryPolling()
        observeVoiceErrors()
        observeMemoryPressureWarnings()
    }

    private fun observeMemoryPressureWarnings() {
        viewModelScope.launch {
            engineCoordinator.memoryPressureWarning.collect { warning ->
                _uiState.update { it.copy(memoryPressureWarning = warning) }
            }
        }
    }

    private fun observeVoiceErrors() {
        viewModelScope.launch {
            voiceController.voiceError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _uiState.update { it.copy(statusBanner = err) }
                    voiceController.clearError()
                }
            }
        }
    }

    private fun initializeDatabaseThread() {
        viewModelScope.launch {
            val thread = chatHistoryRepository.ensureSeededAndGetInitialThread()
            loadThreadIntoUi(thread)
        }
    }

    private suspend fun loadThreadIntoUi(thread: ChatThreadEntity) {
        val workspace = chatHistoryRepository.getWorkspace(thread.workspaceId)
        val loadedMessages = chatHistoryRepository.loadActiveBranchMessages(
            threadId = thread.id,
            trimBeforeTurnOrder = thread.trimBeforeTurnOrder
        )
        needsEngineResync = loadedMessages.any { !it.isOutsideContextWindow }
        val previousThreadId = _uiState.value.activeThreadId
        if (previousThreadId != null && previousThreadId != thread.id) {
            // Stash the outgoing thread's composer state; restore the incoming one's.
            val out = _uiState.value
            if (out.attachedImages.isNotEmpty()) {
                pendingImagesByThread[previousThreadId] = out.attachedImages
            }
        }
        _uiState.update { state ->
            state.copy(
                activeThreadId = thread.id,
                activeThreadTitle = thread.title,
                activeWorkspaceId = workspace?.id ?: state.activeWorkspaceId,
                activeWorkspaceName = workspace?.name ?: state.activeWorkspaceName,
                messages = loadedMessages,
                pendingImage = null,
                pendingImages = pendingImagesByThread[thread.id].orEmpty(),
                estimatedContextTokens = estimateTokenCount(loadedMessages),
                threadMemorySummary = thread.memorySummary,
                tuning = if (workspace != null && workspace.systemPrompt.isNotBlank()) {
                    state.tuning.copy(systemPrompt = workspace.systemPrompt)
                } else {
                    state.tuning
                }
            )
        }
    }

    private fun hydratePreferencesAndMaybeAutoload() {
        viewModelScope.launch {
            var hydrated = false
            preferencesRepository.settings.collect { persisted ->
                if (!hydrated) {
                    hydrated = true
                    _uiState.update {
                        it.copy(
                            tuning = persisted.tuning,
                            themeMode = persisted.themeMode,
                            autoLoadLastModel = persisted.autoLoadLastModel,
                            keepScreenOnDuringInference = persisted.keepScreenOnDuringInference
                        )
                    }
                    if (!attemptedStartupAutoload &&
                        persisted.autoLoadLastModel &&
                        !persisted.lastLoadedModelId.isNullOrBlank() &&
                        engineState.value is EngineLifecycleState.Idle
                    ) {
                        attemptedStartupAutoload = true
                        val models = modelRepository.models.first()
                        val target = models.firstOrNull { it.id == persisted.lastLoadedModelId }
                        if (target != null) {
                            val preflight = engineCoordinator.checkRamPreflight(target, persisted.tuning.contextLength)
                            if (preflight.canSafelyLoad) {
                                requestLoadModel(target, bypassPreflight = true)
                            }
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            themeMode = persisted.themeMode,
                            autoLoadLastModel = persisted.autoLoadLastModel,
                            keepScreenOnDuringInference = persisted.keepScreenOnDuringInference
                        )
                    }
                }
                reconcileEmbeddingModel(persisted)
            }
        }
    }

    private fun startMemoryPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val stats = engineCoordinator.getMemoryStats()
                _uiState.update { it.copy(memoryStats = stats) }
                delay(1500)
            }
        }
    }

    /**
     * Keeps the semantic-RAG embedding model in sync with the persisted setting:
     * when enabled with a chosen embedding-capable GGUF, loads it into the engine;
     * when disabled, releases it.
     */
    private suspend fun reconcileEmbeddingModel(persisted: PersistedAppSettings) {
        val targetId = persisted.embeddingModelId.takeIf { persisted.semanticRetrievalEnabled }
        val alreadyReady = semanticEmbeddingReady.value
        if (targetId == null) {
            if (alreadyReady) {
                engineCoordinator.unloadEmbeddingModel()
                semanticEmbeddingReady.value = false
            }
            return
        }
        if (alreadyReady && targetId == loadedEmbeddingModelId) return
        val entry = modelRepository.models.first().firstOrNull { it.id == targetId } ?: run {
            semanticEmbeddingReady.value = false
            return
        }
        engineCoordinator.loadEmbeddingModel(entry.filePath, contextLength = 512)
        loadedEmbeddingModelId = targetId
        semanticEmbeddingReady.value = engineCoordinator.isEmbeddingAvailable()
    }

    private var loadedEmbeddingModelId: String? = null

    fun setSemanticRetrieval(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.saveSemanticRetrieval(enabled)
            if (!enabled) {
                engineCoordinator.unloadEmbeddingModel()
                loadedEmbeddingModelId = null
                semanticEmbeddingReady.value = false
            }
        }
    }

    fun setEmbeddingModel(modelId: String?) {
        viewModelScope.launch {
            preferencesRepository.saveEmbeddingModelId(modelId)
        }
    }

    fun loadNativeDiagnostics() {
        viewModelScope.launch {
            val logs = engineCoordinator.recentNativeLogs()
            _uiState.update { it.copy(nativeDiagnostics = logs.ifBlank { "No native diagnostics recorded yet." }) }
        }
    }

    // --- Thread & Workspace Hub Actions ---

    fun createNewThread(workspaceId: String? = null, initialPrompt: String? = null) {
        viewModelScope.launch {
            generationJob?.cancel()
            lastTurnImageAttachment = null
            engineCoordinator.resetSession()
            needsEngineResync = false

            val targetWorkspaceId = workspaceId ?: _uiState.value.activeWorkspaceId
            val workspace = chatHistoryRepository.getWorkspace(targetWorkspaceId)
            val newThread = chatHistoryRepository.createThread(
                workspaceId = workspace?.id ?: targetWorkspaceId,
                title = "New Thread",
                modelId = activeModelEntry.value?.id,
                modelName = activeModelEntry.value?.displayName
            )
            _uiState.update { state ->
                state.copy(
                    activeThreadId = newThread.id,
                    activeThreadTitle = newThread.title,
                    activeWorkspaceId = workspace?.id ?: state.activeWorkspaceId,
                    activeWorkspaceName = workspace?.name ?: state.activeWorkspaceName,
                    messages = emptyList(),
                    pendingImage = null,
                    pendingImages = emptyList(),
                    estimatedContextTokens = 0,
                    liveDecodeTokensPerSec = 0.0,
                    livePrefillTokensPerSec = 0.0,
                    liveDecodeTokenCount = 0,
                    tuning = if (workspace != null && workspace.systemPrompt.isNotBlank()) {
                        state.tuning.copy(systemPrompt = workspace.systemPrompt)
                    } else {
                        state.tuning
                    }
                )
            }
            refreshMemoryStatsNow()

            val prompt = initialPrompt?.trim().orEmpty()
            if (prompt.isNotEmpty()) {
                sendPrompt(prompt)
            }
        }
    }

    fun selectThread(threadId: String) {
        viewModelScope.launch {
            val thread = chatHistoryRepository.getThread(threadId) ?: return@launch
            generationJob?.cancel()
            lastTurnImageAttachment = null
            engineCoordinator.resetSession()
            loadThreadIntoUi(thread)
            // Fast path: restore the saved KV-cache state instead of re-prefilling history.
            if (needsEngineResync) {
                val restored = engineCoordinator.loadSessionState(engineStateFile(thread.id).absolutePath)
                if (restored) {
                    needsEngineResync = false
                }
            }
            refreshMemoryStatsNow()
        }
    }

    /** KV state snapshots are invalidated by any change that desyncs the restored cache. */
    private fun invalidateEngineStateForActiveThread() {
        _uiState.value.activeThreadId?.let { threadId ->
            runCatching { engineStateFile(threadId).delete() }
        }
    }

    fun renameThread(threadId: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            chatHistoryRepository.updateThreadMetadata(threadId = threadId, title = clean)
            if (_uiState.value.activeThreadId == threadId) {
                _uiState.update { it.copy(activeThreadTitle = clean) }
            }
        }
    }

    fun togglePinThread(threadId: String) {
        viewModelScope.launch {
            val existing = chatHistoryRepository.getThread(threadId) ?: return@launch
            chatHistoryRepository.setThreadPinned(threadId, !existing.isPinned)
        }
    }

    fun toggleArchiveThread(threadId: String) {
        viewModelScope.launch {
            val existing = chatHistoryRepository.getThread(threadId) ?: return@launch
            val nextArchived = !existing.isArchived
            chatHistoryRepository.setThreadArchived(threadId, nextArchived)
            _uiState.update {
                it.copy(statusBanner = if (nextArchived) "Thread archived" else "Thread restored")
            }
        }
    }

    fun deleteThread(threadId: String) {
        viewModelScope.launch {
            val wasActive = _uiState.value.activeThreadId == threadId
            // Snapshot for the undo snackbar before the row (and cascade) disappears.
            val snapshot = chatHistoryRepository.snapshotThreadForUndo(threadId)
            chatHistoryRepository.deleteThread(threadId)
            runCatching { engineStateFile(threadId).delete() }
            if (wasActive) {
                val fallback = chatHistoryRepository.ensureSeededAndGetInitialThread()
                engineCoordinator.resetSession()
                loadThreadIntoUi(fallback)
            }
            lastDeletedThreadSnapshot = snapshot
            _uiState.update { it.copy(statusBanner = "Thread deleted") }
        }
    }

    private var lastDeletedThreadSnapshot: ChatHistoryRepository.ThreadExport? = null

    fun hasUndoableThreadDelete(): Boolean = lastDeletedThreadSnapshot != null

    fun undoDeleteThread() {
        val snapshot = lastDeletedThreadSnapshot ?: return
        lastDeletedThreadSnapshot = null
        viewModelScope.launch {
            chatHistoryRepository.restoreThreadSnapshot(snapshot)
            selectThread(snapshot.thread.id)
            _uiState.update { it.copy(statusBanner = "Thread restored") }
        }
    }

    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.saveAppLockEnabled(enabled)
        }
    }

    suspend fun searchThreadMessages(query: String): List<ChatMessageEntity> =
        chatHistoryRepository.searchMessages(query)

    fun clearPendingSharedText() {
        _uiState.update { it.copy(pendingSharedText = null) }
    }

    fun createWorkspace(name: String, systemPrompt: String) {
        viewModelScope.launch {
            val ws = chatHistoryRepository.createWorkspace(name, systemPrompt)
            createNewThread(workspaceId = ws.id)
            _uiState.update { it.copy(statusBanner = "Workspace '${ws.name}' created") }
        }
    }

    fun selectWorkspace(workspaceId: String) {
        viewModelScope.launch {
            val existingThread = threads.value.firstOrNull { it.workspaceId == workspaceId && !it.isArchived }
            if (existingThread != null) {
                selectThread(existingThread.id)
            } else {
                createNewThread(workspaceId = workspaceId)
            }
        }
    }

    fun deleteWorkspace(workspaceId: String) {
        viewModelScope.launch {
            chatHistoryRepository.deleteWorkspace(workspaceId)
            if (_uiState.value.activeWorkspaceId == workspaceId) {
                val fallback = chatHistoryRepository.ensureSeededAndGetInitialThread()
                loadThreadIntoUi(fallback)
            }
        }
    }

    // --- Context Window Gauge & Non-Destructive Trim ---

    fun trimOlderContextTurns() {
        viewModelScope.launch {
            val threadId = _uiState.value.activeThreadId ?: return@launch
            val activeMsgs = _uiState.value.messages.filterNot { it.isOutsideContextWindow }
            if (activeMsgs.size <= 2) {
                _uiState.update { it.copy(statusBanner = "Only the latest turn is in context.") }
                return@launch
            }
            // Retain the latest 2 exchanges (~4 messages) and mark earlier turns outside KV context
            val keepCount = minOf(4, activeMsgs.size - 1)
            val cutoffTurnOrder = activeMsgs[activeMsgs.size - keepCount].turnOrder

            val trimmedTurns = activeMsgs.filter { it.turnOrder < cutoffTurnOrder }
            chatHistoryRepository.setTrimBeforeTurnOrder(threadId, cutoffTurnOrder)
            val reloaded = chatHistoryRepository.loadActiveBranchMessages(threadId, cutoffTurnOrder)
            invalidateEngineStateForActiveThread()
            engineCoordinator.resetSession()
            needsEngineResync = true

            _uiState.update { state ->
                state.copy(
                    messages = reloaded,
                    estimatedContextTokens = estimateTokenCount(reloaded),
                    statusBanner = "Trimmed older turns from active context (history saved)"
                )
            }
            refreshMemoryStatsNow()
            summarizeTrimmedTurns(threadId, cutoffTurnOrder, trimmedTurns)
        }
    }

    /**
     * Conversation memory: distills newly-trimmed turns into the thread's rolling summary
     * using the loaded model, so resynced contexts stay aware of older history. Best-effort;
     * silently skipped when no model is loaded or a generation is already running.
     */
    private fun summarizeTrimmedTurns(threadId: String, untilTurnOrder: Int, trimmedTurns: List<ChatMessageUi>) {
        if (trimmedTurns.isEmpty()) return
        if (engineState.value !is EngineLifecycleState.Ready) return
        summarizationJob?.cancel()
        summarizationJob = viewModelScope.launch {
            val previousSummary = _uiState.value.threadMemorySummary
            val transcript = buildString {
                if (!previousSummary.isNullOrBlank()) {
                    appendLine("Earlier conversation summary:")
                    appendLine(previousSummary.trim())
                    appendLine()
                }
                appendLine("New turns to fold in:")
                trimmedTurns.forEach { msg ->
                    val who = if (msg.role == ChatRole.USER) "User" else "Assistant"
                    appendLine("$who: ${msg.text.take(600).trim()}")
                }
            }
            val prompt = buildString {
                appendLine("Condense the following conversation history into a compact running summary (max ~180 words) preserving key facts, decisions, names, and open questions. Output only the summary.")
                appendLine()
                append(transcript)
            }
            val input = InferenceInput(
                prompt = prompt,
                maxTokens = 320,
                enableThinking = false,
                systemPrompt = _uiState.value.tuning.systemPrompt
            )
            val collected = StringBuilder()
            runCatching {
                engineCoordinator.generateStream(input).collect { event ->
                    when (event) {
                        is InferenceEvent.Token -> if (!event.isThought) collected.append(event.text)
                        is InferenceEvent.Completed -> Unit
                        is InferenceEvent.Error -> throw IllegalStateException(event.message)
                        else -> Unit
                    }
                }
            }.onSuccess {
                val summary = collected.toString().trim()
                if (summary.isNotBlank()) {
                    chatHistoryRepository.setThreadMemorySummary(threadId, summary, untilTurnOrder)
                    _uiState.update { it.copy(threadMemorySummary = summary) }
                }
            }
        }
    }

    // --- Local Document & PDF RAG ---

    fun attachDocumentUri(uri: Uri, attachToWorkspace: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isIndexingDocument = true) }
            val threadId = if (attachToWorkspace) null else {
                _uiState.value.activeThreadId ?: chatHistoryRepository.ensureSeededAndGetInitialThread().id
            }
            val workspaceId = if (attachToWorkspace) _uiState.value.activeWorkspaceId else null
            val result = localRagRepository.attachDocumentFromUri(
                uri = uri,
                threadId = threadId,
                workspaceId = workspaceId
            )
            result.onSuccess { doc ->
                _uiState.update {
                    it.copy(
                        isIndexingDocument = false,
                        statusBanner = "Indexed ${doc.displayName} (${doc.chunkCount} chunks)"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isIndexingDocument = false,
                        statusBanner = err.message ?: "Failed to index document"
                    )
                }
            }
        }
    }

    fun removeDocument(docId: String) {
        viewModelScope.launch {
            localRagRepository.removeDocument(docId)
            _uiState.update { it.copy(statusBanner = "Detached document") }
        }
    }

    // --- Slash Templates & Action Commands ---

    fun executeSlashAction(commandId: String) {
        when (commandId) {
            "cmd_think" -> if (!_uiState.value.tuning.enableThinking) toggleThinking()
            "cmd_direct" -> if (_uiState.value.tuning.enableThinking) toggleThinking()
            "cmd_trim" -> trimOlderContextTurns()
            "cmd_clear" -> resetConversation()
        }
    }

    fun addCustomTemplate(trigger: String, label: String, promptBody: String) {
        viewModelScope.launch {
            chatHistoryRepository.addCustomTemplate(trigger, label, promptBody)
            _uiState.update { it.copy(statusBanner = "Saved /$trigger prompt template") }
        }
    }

    fun deleteCustomTemplate(id: String) {
        viewModelScope.launch {
            chatHistoryRepository.deleteCustomTemplate(id)
        }
    }

    // --- Model Vault & Hugging Face Hub Actions ---

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val result = modelRepository.importModelFromSaf(uri)
            result.onSuccess { entry ->
                _uiState.update {
                    it.copy(statusBanner = "Imported ${entry.displayName} (${entry.format.label})")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(statusBanner = err.message ?: "Failed to import model")
                }
            }
        }
    }

    fun pairMmproj(modelId: String, mmprojUri: Uri) {
        viewModelScope.launch {
            val result = modelRepository.pairMmprojToModel(modelId, mmprojUri)
            result.onSuccess { updated ->
                _uiState.update {
                    it.copy(statusBanner = "Paired ${updated.mmprojFileName} to ${updated.displayName} (VL Ready)")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(statusBanner = err.message ?: "Failed to pair mmproj file")
                }
            }
        }
    }

    fun deleteModel(model: LocalModelEntry) {
        viewModelScope.launch {
            if (activeModelEntry.value?.id == model.id) {
                engineCoordinator.unloadActiveModel()
                preferencesRepository.saveLastLoadedModelId(null)
            }
            modelRepository.deleteModel(model.id)
            _uiState.update { it.copy(statusBanner = "Deleted ${model.displayName}") }
        }
    }

    fun requestLoadModel(model: LocalModelEntry, bypassPreflight: Boolean = false) {
        val tuning = _uiState.value.tuning
        if (!bypassPreflight) {
            val preflight = engineCoordinator.checkRamPreflight(model, tuning.contextLength)
            if (!preflight.canSafelyLoad) {
                _uiState.update {
                    it.copy(pendingPreflightWarning = PendingPreflightWarning(model, preflight))
                }
                return
            }
        }
        _uiState.update { it.copy(pendingPreflightWarning = null) }

        viewModelScope.launch {
            val config = ModelLoadConfig(
                modelId = model.id,
                modelName = model.displayName,
                modelPath = model.filePath,
                mmprojPath = model.mmprojFilePath,
                format = model.format,
                preferredAccelerator = tuning.preferredAccelerator,
                contextLength = tuning.contextLength,
                threads = tuning.threads,
                gpuLayers = tuning.gpuLayers,
                systemPrompt = tuning.systemPrompt,
                temperature = tuning.temperature,
                topP = tuning.topP,
                topK = tuning.topK,
                enableThinking = tuning.enableThinking,
                thinkingBudgetTokens = tuning.thinkingBudgetTokens
            )

            val result = engineCoordinator.loadModel(model, config)
            result.onSuccess { info ->
                preferencesRepository.saveLastLoadedModelId(model.id)
                _uiState.value.activeThreadId?.let { tid ->
                    chatHistoryRepository.updateThreadMetadata(tid, modelId = model.id, modelName = model.displayName)
                }
                if (_uiState.value.messages.any { !it.isOutsideContextWindow }) {
                    needsEngineResync = true
                }
                val banner = if (info.fallbackReason != null) {
                    "Loaded on ${info.activeAccelerator.label} (${info.fallbackReason})"
                } else {
                    "Loaded ${info.modelName} on ${info.activeAccelerator.label} in ${info.loadDurationMs} ms"
                }
                _uiState.update { it.copy(statusBanner = banner) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(statusBanner = err.message ?: "Failed to load model")
                }
            }
            refreshMemoryStatsNow()
        }
    }

    fun dismissPreflightWarning() {
        _uiState.update { it.copy(pendingPreflightWarning = null) }
    }

    fun dismissMemoryPressureWarning() {
        engineCoordinator.dismissMemoryPressureWarning()
    }

    fun trimContextFromMemoryWarning() {
        engineCoordinator.dismissMemoryPressureWarning()
        trimOlderContextTurns()
    }

    fun unloadFromMemoryWarning() {
        engineCoordinator.dismissMemoryPressureWarning()
        unloadActiveModel()
    }

    fun unloadActiveModel() {
        viewModelScope.launch {
            engineCoordinator.cancelGeneration()
            generationJob?.cancelAndJoin()
            engineCoordinator.unloadActiveModel()
            preferencesRepository.saveLastLoadedModelId(null)
            refreshMemoryStatsNow()
            _uiState.update { it.copy(statusBanner = "Native model buffers released") }
        }
    }

    fun searchHubModels(query: String) {
        _hubState.update { it.copy(query = query, isSearching = true, errorMessage = null) }
        viewModelScope.launch {
            val res = huggingFaceHubRepository.searchRepos(query)
            res.onSuccess { list ->
                _hubState.update {
                    it.copy(isSearching = false, repos = list, errorMessage = null)
                }
            }.onFailure { err ->
                _hubState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = err.message ?: "Failed to search Hugging Face Hub"
                    )
                }
            }
        }
    }

    fun loadHubRepoFiles(repoId: String) {
        if (_hubState.value.loadingRepoIds.contains(repoId)) return
        _hubState.update { it.copy(loadingRepoIds = it.loadingRepoIds + repoId, errorMessage = null) }
        viewModelScope.launch {
            val res = huggingFaceHubRepository.fetchRepoFiles(repoId)
            res.onSuccess { files ->
                _hubState.update {
                    it.copy(
                        loadingRepoIds = it.loadingRepoIds - repoId,
                        repoFiles = it.repoFiles + (repoId to files)
                    )
                }
            }.onFailure { err ->
                _hubState.update {
                    it.copy(
                        loadingRepoIds = it.loadingRepoIds - repoId,
                        errorMessage = err.message ?: "Unable to fetch repository files"
                    )
                }
            }
        }
    }

    fun downloadHubFile(file: HfModelFile) {
        _uiState.update { it.copy(statusBanner = "Downloading ${file.fileName}...") }
        com.example.data.hub.DownloadForegroundService.startIfDownloadsActive(app)
        huggingFaceHubRepository.startOrResumeDownload(file) { result ->
            result.onSuccess { entry ->
                _uiState.update {
                    it.copy(statusBanner = "Downloaded & ready: ${entry.displayName}")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(statusBanner = err.message ?: "Download failed for ${file.fileName}")
                }
            }
        }
    }

    fun pauseHubDownload(taskId: String) {
        huggingFaceHubRepository.pauseDownload(taskId)
    }

    fun cancelHubDownload(taskId: String) {
        huggingFaceHubRepository.cancelDownload(taskId)
    }

    fun resumeHubDownload(taskId: String) {
        com.example.data.hub.DownloadForegroundService.startIfDownloadsActive(app)
        if (!huggingFaceHubRepository.resumeTask(taskId)) {
            _uiState.update { it.copy(statusBanner = "This download needs to be restarted (stale session).") }
        }
    }

    // --- Thread Export / Import (full fidelity) ---

    fun exportThreadJson(threadId: String, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val raw = chatHistoryRepository.exportThreadJson(threadId)
                    ?: error("Thread not found")
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(raw.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("Cannot open export destination")
            }.onSuccess {
                _uiState.update { it.copy(statusBanner = "Thread exported as JSON") }
            }.onFailure { err ->
                _uiState.update { it.copy(statusBanner = "Export failed: ${err.message}") }
            }
        }
    }

    fun importThreadJson(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val raw = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
                    ?: error("Cannot open imported file")
                chatHistoryRepository.importThreadJson(raw)
            }.onSuccess { result ->
                result.onSuccess { thread ->
                    selectThread(thread.id)
                    _uiState.update { it.copy(statusBanner = "Imported thread '${thread.title}'") }
                }.onFailure { err ->
                    _uiState.update { it.copy(statusBanner = "Import failed: ${err.message}") }
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusBanner = "Import failed: ${err.message}") }
            }
        }
    }

    // --- Share-sheet & Shortcut Entry Points ---

    /**
     * Handles text shared into LocalMind from other apps: if a model is ready, starts a
     * new thread with the shared content; otherwise seeds the composer draft.
     */
    fun handleSharedText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (engineState.value is EngineLifecycleState.Ready) {
            createNewThread(initialPrompt = clean)
        } else {
            _uiState.update {
                it.copy(
                    statusBanner = "Load a local model to process shared content",
                    pendingSharedText = clean
                )
            }
        }
    }

    fun consumePendingSharedText(): String? {
        val text = _uiState.value.pendingSharedText ?: return null
        _uiState.update { it.copy(pendingSharedText = null) }
        return text
    }

    // --- Multimodal Image Attachments ---

    fun attachImageUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingImage = true) }
            val result = imagePreprocessor.prepareImage(uri, maxDimension = 768)
            result.onSuccess { prepared ->
                _uiState.update {
                    it.copy(
                        pendingImages = (it.attachedImages + prepared).takeLast(4),
                        isPreparingImage = false
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isPreparingImage = false,
                        statusBanner = "Image preprocessing failed: ${err.message}"
                    )
                }
            }
        }
    }

    fun clearPendingImage() {
        _uiState.update { it.copy(pendingImage = null, pendingImages = emptyList()) }
    }

    fun removePendingImage(uriString: String) {
        _uiState.update { it.copy(pendingImages = it.pendingImages.filterNot { p -> p.uriString == uriString }) }
    }

    // --- Per-thread Composer Drafts ---

    fun saveDraft(threadId: String?, text: String) {
        if (threadId == null) return
        synchronized(draftsByThread) {
            if (text.isBlank()) draftsByThread.remove(threadId) else draftsByThread[threadId] = text
        }
    }

    fun restoreDraft(threadId: String?): String {
        if (threadId == null) return ""
        return synchronized(draftsByThread) { draftsByThread[threadId].orEmpty() }
    }

    // --- Voice Dictation & Read-Aloud ---

    fun startVoiceDictation(onTranscriptReady: (String) -> Unit) {
        voiceController.startDictation(onTranscriptReady)
    }

    fun stopVoiceDictation() {
        voiceController.stopDictation()
    }

    fun toggleReadAloud(messageId: String, markdownText: String) {
        voiceController.toggleSpeakMessage(messageId, markdownText)
    }

    fun stopReadAloud() {
        voiceController.stopSpeaking()
    }

    // --- Messaging, Regeneration Branches & Persistence ---

    fun sendPrompt(rawPrompt: String) {
        val promptText = rawPrompt.trim()
        val attached = _uiState.value.attachedImages
        if (promptText.isEmpty() && attached.isEmpty()) return
        if (engineState.value !is EngineLifecycleState.Ready) {
            _uiState.update { it.copy(statusBanner = "Load a local model from the Vault before chatting.") }
            return
        }

        val effectivePrompt = promptText.ifEmpty { "Describe this image in detail." }
        lastTurnImageAttachment = attached.firstOrNull()

        viewModelScope.launch {
            val threadId = _uiState.value.activeThreadId
                ?: chatHistoryRepository.ensureSeededAndGetInitialThread().also { loadThreadIntoUi(it) }.id

            val priorMessages = _uiState.value.messages
            val nextTurnOrder = (priorMessages.maxOfOrNull { it.turnOrder } ?: -1) + 1
            val userMsgId = UUID.randomUUID().toString()
            val userMsg = ChatMessageUi(
                id = userMsgId,
                role = ChatRole.USER,
                text = effectivePrompt,
                imageUris = attached.map { it.uriString },
                turnOrder = nextTurnOrder,
                branchGroupId = userMsgId
            )
            chatHistoryRepository.saveMessage(
                threadId = threadId,
                message = userMsg,
                turnOrder = nextTurnOrder,
                branchGroupId = userMsgId
            )
            if (_uiState.value.activeThreadTitle == "New Thread") {
                val autoTitle = effectivePrompt.replace('\n', ' ').take(44).trim()
                if (autoTitle.isNotEmpty()) {
                    chatHistoryRepository.updateThreadMetadata(
                        threadId = threadId,
                        title = autoTitle,
                        modelId = activeModelEntry.value?.id,
                        modelName = activeModelEntry.value?.displayName
                    )
                    _uiState.update { it.copy(activeThreadTitle = autoTitle) }
                }
            }

            launchAssistantGeneration(
                threadId = threadId,
                userMessage = userMsg,
                attachedImages = attached,
                priorMessages = priorMessages,
                assistantTurnOrder = nextTurnOrder + 1,
                assistantBranchGroupId = UUID.randomUUID().toString(),
                assistantRevisionIndex = 0,
                existingSiblings = emptyList()
            )
        }
    }

    fun regenerateLastResponse() {
        if (engineState.value !is EngineLifecycleState.Ready) return
        val msgs = _uiState.value.messages
        if (msgs.size < 2) return
        val lastAssistant = msgs.last()
        val lastUser = msgs[msgs.size - 2]
        if (lastAssistant.role != ChatRole.ASSISTANT || lastUser.role != ChatRole.USER) return
        val earlierMessages = msgs.dropLast(2)

        viewModelScope.launch {
            val threadId = _uiState.value.activeThreadId ?: return@launch
            val preservedImage = resolveAttachedImage(lastUser.imageUriString)
            lastTurnImageAttachment = preservedImage
            invalidateEngineStateForActiveThread()
            val rolledBack = engineCoordinator.rollbackLastTurn()
            if (!rolledBack) {
                engineCoordinator.resetSession()
                needsEngineResync = earlierMessages.any { !it.isOutsideContextWindow }
            }
            launchAssistantGeneration(
                threadId = threadId,
                userMessage = lastUser,
                attachedImages = listOfNotNull(preservedImage),
                priorMessages = earlierMessages,
                assistantTurnOrder = lastAssistant.turnOrder,
                assistantBranchGroupId = lastAssistant.branchGroupId,
                assistantRevisionIndex = lastAssistant.siblingIds.size,
                existingSiblings = lastAssistant.siblingIds
            )
        }
    }

    fun selectMessageBranch(branchGroupId: String, targetMessageId: String) {
        viewModelScope.launch {
            val threadId = _uiState.value.activeThreadId ?: return@launch
            val thread = chatHistoryRepository.getThread(threadId) ?: return@launch
            chatHistoryRepository.selectBranch(threadId, branchGroupId, targetMessageId)
            val updated = chatHistoryRepository.loadActiveBranchMessages(
                threadId = threadId,
                trimBeforeTurnOrder = thread.trimBeforeTurnOrder
            )
            invalidateEngineStateForActiveThread()
            engineCoordinator.resetSession()
            needsEngineResync = true
            _uiState.update { state ->
                state.copy(
                    messages = updated,
                    estimatedContextTokens = estimateTokenCount(updated)
                )
            }
            refreshMemoryStatsNow()
        }
    }

    fun editLastUserMessageAndResend(newRawPrompt: String) {
        val editedPrompt = newRawPrompt.trim()
        if (editedPrompt.isEmpty()) return
        if (engineState.value !is EngineLifecycleState.Ready) return

        val msgs = _uiState.value.messages
        val lastUserIdx = msgs.indexOfLast { it.role == ChatRole.USER }
        if (lastUserIdx < 0) return
        val targetUser = msgs[lastUserIdx]
        val earlierMessages = msgs.take(lastUserIdx)

        viewModelScope.launch {
            val threadId = _uiState.value.activeThreadId ?: return@launch
            val preservedImage = resolveAttachedImage(targetUser.imageUriString)
            lastTurnImageAttachment = preservedImage
            invalidateEngineStateForActiveThread()
            val rolledBack = engineCoordinator.rollbackLastTurn()
            if (!rolledBack) {
                engineCoordinator.resetSession()
                needsEngineResync = earlierMessages.any { !it.isOutsideContextWindow }
            }
            chatHistoryRepository.deleteFromTurnOrder(threadId, targetUser.turnOrder)

            val updatedUser = targetUser.copy(
                text = editedPrompt,
                timestampMillis = System.currentTimeMillis()
            )
            chatHistoryRepository.saveMessage(
                threadId = threadId,
                message = updatedUser,
                turnOrder = updatedUser.turnOrder,
                branchGroupId = updatedUser.branchGroupId
            )

            launchAssistantGeneration(
                threadId = threadId,
                userMessage = updatedUser,
                attachedImages = listOfNotNull(preservedImage),
                priorMessages = earlierMessages,
                assistantTurnOrder = updatedUser.turnOrder + 1,
                assistantBranchGroupId = UUID.randomUUID().toString(),
                assistantRevisionIndex = 0,
                existingSiblings = emptyList()
            )
        }
    }

    private suspend fun resolveAttachedImage(uriString: String?): PreparedImage? {
        if (uriString.isNullOrBlank()) return null
        lastTurnImageAttachment?.takeIf { it.uriString == uriString }?.let { return it }
        return runCatching {
            imagePreprocessor.prepareImage(Uri.parse(uriString), maxDimension = 768).getOrNull()
        }.getOrNull()
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatHistoryRepository.deleteMessage(messageId)
            invalidateEngineStateForActiveThread()
            engineCoordinator.resetSession()
            needsEngineResync = true
            _uiState.update { state ->
                val nextMsgs = state.messages.filterNot { it.id == messageId }
                state.copy(
                    messages = nextMsgs,
                    estimatedContextTokens = estimateTokenCount(nextMsgs)
                )
            }
        }
    }

    private fun launchAssistantGeneration(
        threadId: String,
        userMessage: ChatMessageUi,
        attachedImages: List<PreparedImage>,
        priorMessages: List<ChatMessageUi>,
        assistantTurnOrder: Int,
        assistantBranchGroupId: String,
        assistantRevisionIndex: Int,
        existingSiblings: List<String>
    ) {
        val assistantMsgId = UUID.randomUUID().toString()
        val combinedSiblings = existingSiblings + assistantMsgId
        val placeholderAssistant = ChatMessageUi(
            id = assistantMsgId,
            role = ChatRole.ASSISTANT,
            text = "",
            isStreaming = true,
            isInterrupted = false,
            turnOrder = assistantTurnOrder,
            branchGroupId = assistantBranchGroupId,
            revisionIndex = combinedSiblings.lastIndex,
            siblingIds = combinedSiblings
        )

        val basePriorTokens = estimateTokenCount(priorMessages + userMessage)
        val nextList = priorMessages + userMessage + placeholderAssistant
        _uiState.update {
            it.copy(
                messages = nextList,
                pendingImage = null,
                pendingImages = emptyList(),
                statusBanner = null,
                liveDecodeTokensPerSec = 0.0,
                livePrefillTokensPerSec = 0.0,
                liveDecodeTokenCount = 0,
                estimatedContextTokens = basePriorTokens
            )
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            // Create the assistant row upfront marked interrupted=true until Completed clears it,
            // so even a hard process crash retains any checkpointed thinking & partial text.
            withContext(Dispatchers.IO) {
                chatHistoryRepository.saveMessage(
                    threadId = threadId,
                    message = placeholderAssistant.copy(isStreaming = false, isInterrupted = true),
                    turnOrder = assistantTurnOrder,
                    branchGroupId = assistantBranchGroupId,
                    revisionIndex = assistantRevisionIndex,
                    isActiveBranch = true
                )
            }

            val ragResult = localRagRepository.retrieveContextForPrompt(
                query = userMessage.text,
                attachedDocs = attachedDocuments.value,
                topK = 3,
                embeddingProvider = ragEmbeddingProvider
            )
            val citations = ragResult?.citations.orEmpty()
            if (citations.isNotEmpty()) {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.map { msg ->
                            if (msg.id == assistantMsgId) msg.copy(citations = citations) else msg
                        }
                    )
                }
            }

            val historyBlock = if (needsEngineResync) {
                val activePrior = priorMessages.filterNot { it.isOutsideContextWindow }
                val memorySummary = _uiState.value.threadMemorySummary?.takeIf { it.isNotBlank() }
                if (activePrior.isNotEmpty() || memorySummary != null) {
                    buildString {
                        appendLine("<conversation_history>")
                        if (memorySummary != null) {
                            appendLine("Summary of earlier trimmed turns: $memorySummary")
                            if (activePrior.isNotEmpty()) appendLine()
                        }
                        activePrior.forEach { m ->
                            val who = if (m.role == ChatRole.USER) "User" else "Assistant"
                            appendLine("$who: ${m.text.trim()}")
                        }
                        appendLine("</conversation_history>")
                    }.trim()
                } else null
            } else null

            val promptForEngine = buildString {
                if (historyBlock != null) {
                    appendLine(historyBlock)
                    appendLine()
                }
                if (ragResult != null) {
                    appendLine(ragResult.promptContextBlock)
                    appendLine()
                }
                append(userMessage.text)
            }

            val tuning = _uiState.value.tuning
            val input = InferenceInput(
                prompt = promptForEngine,
                imageAttachments = attachedImages,
                maxTokens = tuning.maxTokens,
                enableThinking = tuning.enableThinking,
                thinkingBudgetTokens = tuning.thinkingBudgetTokens,
                systemPrompt = tuning.systemPrompt
            )

            val thinkingBuffer = StringBuilder()
            val answerBuffer = StringBuilder()
            var currentlyThinking = false
            var latestDecodeTokPerSec = 0.0
            var latestPrefillTokPerSec = 0.0
            var latestDecodeCount = 0
            var lastUiFlushMs = 0L
            var lastDbCheckpointMs = 0L
            var dirtyUi = false
            var checkpointJob: Job? = null

            fun applyBufferedStateToUi(
                isStreamingNow: Boolean,
                isThinkingNow: Boolean,
                isInterruptedNow: Boolean,
                fallbackErrorIfBlank: String? = null
            ): ChatMessageUi? {
                val finalThinking = thinkingBuffer.toString()
                val rawAnswer = answerBuffer.toString()
                val finalAnswer = if (rawAnswer.isEmpty() && finalThinking.isBlank() && fallbackErrorIfBlank != null) {
                    "[Error] $fallbackErrorIfBlank"
                } else {
                    rawAnswer
                }
                val streamTokens = (finalAnswer.length + finalThinking.length) / 4
                var snapshot: ChatMessageUi? = null
                _uiState.update { state ->
                    val updatedMessages = state.messages.map { msg ->
                        if (msg.id == assistantMsgId) {
                            msg.copy(
                                thinkingText = finalThinking,
                                text = finalAnswer,
                                isThinking = isThinkingNow,
                                isStreaming = isStreamingNow,
                                isInterrupted = isInterruptedNow,
                                citations = if (citations.isNotEmpty()) citations else msg.citations
                            ).also { snapshot = it }
                        } else {
                            msg
                        }
                    }
                    state.copy(
                        livePrefillTokensPerSec = latestPrefillTokPerSec,
                        liveDecodeTokensPerSec = latestDecodeTokPerSec,
                        liveDecodeTokenCount = latestDecodeCount,
                        messages = updatedMessages,
                        estimatedContextTokens = basePriorTokens + streamTokens
                    )
                }
                return snapshot
            }

            try {
                engineCoordinator.generateStream(input).collect { event ->
                    when (event) {
                        is InferenceEvent.Started -> Unit
                        is InferenceEvent.PrefillCompleted -> {
                            latestPrefillTokPerSec = event.prefillTokensPerSec.takeIf { it.isFinite() } ?: 0.0
                            applyBufferedStateToUi(
                                isStreamingNow = true,
                                isThinkingNow = currentlyThinking,
                                isInterruptedNow = false
                            )
                        }
                        is InferenceEvent.Token -> {
                            val phaseChanged = dirtyUi && (currentlyThinking != event.isThought)
                            if (event.isThought) {
                                thinkingBuffer.append(event.text)
                            } else {
                                answerBuffer.append(event.text)
                            }
                            currentlyThinking = event.isThought
                            latestDecodeTokPerSec = event.currentDecodeTokPerSec.takeIf { it.isFinite() } ?: 0.0
                            latestDecodeCount = event.decodeTokens
                            dirtyUi = true

                            val now = System.currentTimeMillis()
                            if (phaseChanged || lastUiFlushMs == 0L || now - lastUiFlushMs >= 50L) {
                                lastUiFlushMs = now
                                dirtyUi = false
                                applyBufferedStateToUi(
                                    isStreamingNow = true,
                                    isThinkingNow = currentlyThinking,
                                    isInterruptedNow = false
                                )
                            }
                            if (now - lastDbCheckpointMs >= 750L &&
                                (thinkingBuffer.isNotEmpty() || answerBuffer.isNotEmpty()) &&
                                checkpointJob?.isActive != true
                            ) {
                                lastDbCheckpointMs = now
                                val textSnapshot = answerBuffer.toString()
                                val thinkingSnapshot = thinkingBuffer.toString()
                                checkpointJob = viewModelScope.launch(Dispatchers.IO) {
                                    chatHistoryRepository.checkpointStreamingMessage(
                                        threadId = threadId,
                                        messageId = assistantMsgId,
                                        text = textSnapshot,
                                        thinkingText = thinkingSnapshot,
                                        isInterrupted = true
                                    )
                                }
                            }
                        }
                        is InferenceEvent.Completed -> {
                            checkpointJob?.join()
                            needsEngineResync = false
                            val safeTelemetry = event.telemetry.copy(
                                prefillTokensPerSec = event.telemetry.prefillTokensPerSec.takeIf { it.isFinite() } ?: 0.0,
                                decodeTokensPerSec = event.telemetry.decodeTokensPerSec.takeIf { it.isFinite() } ?: 0.0
                            )
                            latestPrefillTokPerSec = safeTelemetry.prefillTokensPerSec
                            latestDecodeTokPerSec = safeTelemetry.decodeTokensPerSec
                            latestDecodeCount = safeTelemetry.decodeTokens

                            val finalThinking = thinkingBuffer.toString()
                            val finalAnswer = answerBuffer.toString()
                            var completedMsg: ChatMessageUi? = null
                            _uiState.update { state ->
                                val updatedMessages = state.messages.map { msg ->
                                    if (msg.id == assistantMsgId) {
                                        msg.copy(
                                            thinkingText = finalThinking,
                                            text = finalAnswer,
                                            isStreaming = false,
                                            isThinking = false,
                                            isInterrupted = false,
                                            telemetry = safeTelemetry,
                                            citations = citations
                                        ).also { completedMsg = it }
                                    } else {
                                        msg
                                    }
                                }
                                state.copy(
                                    liveDecodeTokensPerSec = safeTelemetry.decodeTokensPerSec,
                                    livePrefillTokensPerSec = safeTelemetry.prefillTokensPerSec,
                                    liveDecodeTokenCount = safeTelemetry.decodeTokens,
                                    messages = updatedMessages,
                                    estimatedContextTokens = basePriorTokens + (finalAnswer.length + finalThinking.length) / 4
                                )
                            }
                            completedMsg?.let { msgToPersist ->
                                withContext(Dispatchers.IO + NonCancellable) {
                                    chatHistoryRepository.saveMessage(
                                        threadId = threadId,
                                        message = msgToPersist,
                                        turnOrder = assistantTurnOrder,
                                        branchGroupId = assistantBranchGroupId,
                                        revisionIndex = assistantRevisionIndex,
                                        isActiveBranch = true
                                    )
                                }
                            }
                            // Persist the live KV-cache so switching back to this thread
                            // can resume without a full re-prefill (llama.cpp only).
                            if (!needsEngineResync) {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        engineCoordinator.saveSessionState(engineStateFile(threadId).absolutePath)
                                    }
                                }
                            }
                            refreshMemoryStatsNow()
                        }
                        is InferenceEvent.Error -> {
                            checkpointJob?.join()
                            needsEngineResync = true
                            val hasPartial = thinkingBuffer.isNotBlank() || answerBuffer.isNotBlank()
                            val errorMsg = applyBufferedStateToUi(
                                isStreamingNow = false,
                                isThinkingNow = false,
                                isInterruptedNow = true,
                                fallbackErrorIfBlank = event.message
                            )
                            _uiState.update { it.copy(statusBanner = event.message) }
                            errorMsg?.let { msgToPersist ->
                                if (hasPartial || msgToPersist.text.isNotBlank()) {
                                    withContext(Dispatchers.IO + NonCancellable) {
                                        chatHistoryRepository.saveMessage(
                                            threadId = threadId,
                                            message = msgToPersist,
                                            turnOrder = assistantTurnOrder,
                                            branchGroupId = assistantBranchGroupId,
                                            revisionIndex = assistantRevisionIndex,
                                            isActiveBranch = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO + NonCancellable) {
                    checkpointJob?.join()
                    val cancelledMsg = applyBufferedStateToUi(
                        isStreamingNow = false,
                        isThinkingNow = false,
                        isInterruptedNow = thinkingBuffer.isNotBlank() || answerBuffer.isNotBlank()
                    )
                    if (cancelledMsg != null && (cancelledMsg.text.isNotBlank() || cancelledMsg.thinkingText.isNotBlank())) {
                        chatHistoryRepository.saveMessage(
                            threadId = threadId,
                            message = cancelledMsg,
                            turnOrder = assistantTurnOrder,
                            branchGroupId = assistantBranchGroupId,
                            revisionIndex = assistantRevisionIndex,
                            isActiveBranch = true
                        )
                    } else {
                        chatHistoryRepository.deleteMessage(assistantMsgId)
                        _uiState.update { state ->
                            val cleaned = state.messages.filterNot { it.id == assistantMsgId }
                            state.copy(
                                messages = cleaned,
                                estimatedContextTokens = estimateTokenCount(cleaned)
                            )
                        }
                    }
                }
                throw e
            } catch (t: Throwable) {
                withContext(Dispatchers.IO + NonCancellable) {
                    checkpointJob?.join()
                    needsEngineResync = true
                    val errText = t.message ?: "Unexpected generation interruption"
                    val interruptedMsg = applyBufferedStateToUi(
                        isStreamingNow = false,
                        isThinkingNow = false,
                        isInterruptedNow = true,
                        fallbackErrorIfBlank = errText
                    )
                    _uiState.update { it.copy(statusBanner = errText) }
                    if (interruptedMsg != null && (interruptedMsg.text.isNotBlank() || interruptedMsg.thinkingText.isNotBlank())) {
                        chatHistoryRepository.saveMessage(
                            threadId = threadId,
                            message = interruptedMsg,
                            turnOrder = assistantTurnOrder,
                            branchGroupId = assistantBranchGroupId,
                            revisionIndex = assistantRevisionIndex,
                            isActiveBranch = true
                        )
                    } else {
                        chatHistoryRepository.deleteMessage(assistantMsgId)
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        viewModelScope.launch {
            engineCoordinator.cancelGeneration()
            generationJob?.cancelAndJoin()
            val threadId = _uiState.value.activeThreadId
            val streamingMsg = _uiState.value.messages.lastOrNull { it.isStreaming }
            if (streamingMsg != null && threadId != null) {
                finalizeAndPersistStreamingMessage(
                    threadId = threadId,
                    messageId = streamingMsg.id,
                    turnOrder = streamingMsg.turnOrder,
                    branchGroupId = streamingMsg.branchGroupId,
                    revisionIndex = streamingMsg.revisionIndex
                )
            }
        }
    }

    fun resetConversation() {
        createNewThread()
        _uiState.update { it.copy(statusBanner = "Started a fresh thread & cleared KV cache") }
    }

    fun updateTuning(transform: (InferenceTuningConfig) -> InferenceTuningConfig) {
        var updated: InferenceTuningConfig? = null
        _uiState.update { state ->
            val next = transform(state.tuning)
            updated = next
            state.copy(tuning = next)
        }
        updated?.let { cfg ->
            viewModelScope.launch {
                preferencesRepository.saveTuning(cfg)
            }
        }
    }

    fun toggleThinking() {
        var updated: InferenceTuningConfig? = null
        _uiState.update { state ->
            val nextEnabled = !state.tuning.enableThinking
            val nextConfig = state.tuning.copy(enableThinking = nextEnabled)
            updated = nextConfig
            state.copy(
                tuning = nextConfig,
                statusBanner = if (nextEnabled) "Thinking mode enabled" else "Thinking mode disabled (direct answer)"
            )
        }
        updated?.let { cfg ->
            viewModelScope.launch {
                preferencesRepository.saveTuning(cfg)
            }
        }
    }

    fun updateThemeMode(mode: AppThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch {
            preferencesRepository.saveThemeMode(mode)
        }
    }

    fun setAutoLoadLastModel(enabled: Boolean) {
        _uiState.update { it.copy(autoLoadLastModel = enabled) }
        viewModelScope.launch {
            preferencesRepository.saveAutoLoadLastModel(enabled)
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _uiState.update { it.copy(keepScreenOnDuringInference = enabled) }
        viewModelScope.launch {
            preferencesRepository.saveKeepScreenOn(enabled)
        }
    }

    fun buildMarkdownTranscript(): String {
        return formatMarkdownTranscript(
            messages = _uiState.value.messages,
            modelName = activeModelEntry.value?.displayName
        )
    }

    fun showStatusBanner(message: String) {
        _uiState.update { it.copy(statusBanner = message) }
    }

    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    private suspend fun finalizeAndPersistStreamingMessage(
        threadId: String,
        messageId: String,
        turnOrder: Int,
        branchGroupId: String,
        revisionIndex: Int
    ) {
        var finalized: ChatMessageUi? = null
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(
                            isStreaming = false,
                            isThinking = false,
                            isInterrupted = msg.text.isNotBlank() || msg.thinkingText.isNotBlank()
                        ).also { finalized = it }
                    } else msg
                }
            )
        }
        finalized?.let { msg ->
            if (msg.text.isNotBlank() || msg.thinkingText.isNotBlank()) {
                withContext(Dispatchers.IO + NonCancellable) {
                    chatHistoryRepository.saveMessage(
                        threadId = threadId,
                        message = msg,
                        turnOrder = turnOrder,
                        branchGroupId = branchGroupId,
                        revisionIndex = revisionIndex,
                        isActiveBranch = true
                    )
                }
            }
        }
    }

    private suspend fun refreshMemoryStatsNow() {
        val stats = engineCoordinator.getMemoryStats()
        _uiState.update { it.copy(memoryStats = stats) }
    }

    // VoiceController is application-scoped; releasing it here would tear down TTS for the
    // whole process whenever a ViewModel is cleared (e.g. during navigation recreation).
}
