package com.example.data.local

import androidx.compose.runtime.Immutable
import com.example.data.storage.SystemPromptPresets
import com.example.domain.engine.GenerationTelemetry
import com.example.ui.viewmodel.ChatMessageUi
import com.example.ui.viewmodel.ChatRole
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Immutable
@Serializable
data class DocumentCitationUi(
    val documentId: String,
    val documentName: String,
    val sectionLabel: String,
    val snippet: String
)

@Immutable
data class SlashCommandItem(
    val id: String,
    val trigger: String,
    val label: String,
    val description: String,
    val promptBody: String,
    val isActionCommand: Boolean = false,
    val isBuiltIn: Boolean = true
)

class ChatHistoryRepository(
    private val database: LocalMindDatabase
) {
    companion object {
        const val DEFAULT_WORKSPACE_ID = "workspace_general"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    private val workspaceDao = database.workspaceDao()
    private val threadDao = database.chatThreadDao()
    private val messageDao = database.chatMessageDao()
    private val templateDao = database.promptTemplateDao()

    val workspaces: Flow<List<WorkspaceEntity>> = workspaceDao.observeAll()
    val threads: Flow<List<ChatThreadEntity>> = threadDao.observeAll()
    val customTemplates: Flow<List<PromptTemplateEntity>> = templateDao.observeAll()

    fun observeMessagesForThread(threadId: String): Flow<List<ChatMessageEntity>> =
        messageDao.observeForThread(threadId)

    suspend fun ensureSeededAndGetInitialThread(): ChatThreadEntity {
        if (workspaceDao.count() == 0) {
            workspaceDao.insert(
                WorkspaceEntity(
                    id = DEFAULT_WORKSPACE_ID,
                    name = "General",
                    systemPrompt = SystemPromptPresets.all.first().prompt,
                    isDefault = true
                )
            )
            workspaceDao.insert(
                WorkspaceEntity(
                    id = "workspace_engineering",
                    name = "Code & Architecture",
                    systemPrompt = SystemPromptPresets.all[1].prompt,
                    isDefault = false
                )
            )
        }

        if (templateDao.count() == 0) {
            templateDao.upsertAll(
                listOf(
                    PromptTemplateEntity(
                        id = "tpl_summarize",
                        trigger = "summarize",
                        label = "Summarize Concisely",
                        promptBody = "Summarize the key points concisely with bullet takeaways:\n\n",
                        isBuiltIn = true
                    ),
                    PromptTemplateEntity(
                        id = "tpl_code",
                        trigger = "code",
                        label = "Clean Code Review",
                        promptBody = "Review this code for edge cases, performance, and clarity:\n\n",
                        isBuiltIn = true
                    ),
                    PromptTemplateEntity(
                        id = "tpl_explain",
                        trigger = "explain",
                        label = "Explain Step-by-Step",
                        promptBody = "Explain this step by step from first principles:\n\n",
                        isBuiltIn = true
                    )
                )
            )
        }

        return threadDao.getMostRecentActive() ?: createThread(
            workspaceId = DEFAULT_WORKSPACE_ID,
            title = "New Thread"
        )
    }

    suspend fun createWorkspace(name: String, systemPrompt: String): WorkspaceEntity {
        val cleanName = name.trim().ifEmpty { "Workspace" }
        val now = System.currentTimeMillis()
        val ws = WorkspaceEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            systemPrompt = systemPrompt.trim().ifEmpty { SystemPromptPresets.all.first().prompt },
            isDefault = false,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        workspaceDao.insert(ws)
        return ws
    }

    suspend fun updateWorkspaceSystemPrompt(workspaceId: String, systemPrompt: String) {
        val existing = workspaceDao.getById(workspaceId) ?: return
        workspaceDao.update(
            existing.copy(
                systemPrompt = systemPrompt,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteWorkspace(workspaceId: String) {
        workspaceDao.deleteCustom(workspaceId)
    }

    suspend fun getWorkspace(workspaceId: String?): WorkspaceEntity? {
        val id = workspaceId ?: DEFAULT_WORKSPACE_ID
        return workspaceDao.getById(id)
    }

    suspend fun createThread(
        workspaceId: String = DEFAULT_WORKSPACE_ID,
        title: String = "New Thread",
        modelId: String? = null,
        modelName: String? = null
    ): ChatThreadEntity {
        val now = System.currentTimeMillis()
        val thread = ChatThreadEntity(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            title = title,
            lastModelId = modelId,
            lastModelName = modelName,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        threadDao.insert(thread)
        return thread
    }

    suspend fun getThread(threadId: String): ChatThreadEntity? = threadDao.getById(threadId)

    suspend fun updateThreadMetadata(
        threadId: String,
        title: String? = null,
        modelId: String? = null,
        modelName: String? = null
    ) {
        val existing = threadDao.getById(threadId) ?: return
        threadDao.update(
            existing.copy(
                title = title ?: existing.title,
                lastModelId = modelId ?: existing.lastModelId,
                lastModelName = modelName ?: existing.lastModelName,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun setThreadArchived(threadId: String, archived: Boolean) {
        threadDao.setArchived(threadId, archived, System.currentTimeMillis())
    }

    suspend fun setThreadPinned(threadId: String, pinned: Boolean) {
        threadDao.setPinned(threadId, pinned, System.currentTimeMillis())
    }

    suspend fun setTrimBeforeTurnOrder(threadId: String, trimBeforeTurnOrder: Int) {
        threadDao.setTrimBeforeTurnOrder(threadId, trimBeforeTurnOrder, System.currentTimeMillis())
    }

    suspend fun deleteThread(threadId: String) {
        threadDao.deleteById(threadId)
    }

    suspend fun saveMessage(
        threadId: String,
        message: ChatMessageUi,
        turnOrder: Int,
        branchGroupId: String,
        revisionIndex: Int = 0,
        isActiveBranch: Boolean = true
    ) {
        if (threadDao.getById(threadId) == null) return
        if (isActiveBranch) {
            messageDao.deactivateBranchesInGroup(threadId, branchGroupId)
        }
        val safeTelemetry = message.telemetry?.let { t ->
            t.copy(
                prefillTokensPerSec = t.prefillTokensPerSec.takeIf { it.isFinite() } ?: 0.0,
                decodeTokensPerSec = t.decodeTokensPerSec.takeIf { it.isFinite() } ?: 0.0
            )
        }
        val encodedCitations = message.citations.takeIf { it.isNotEmpty() }?.let { list ->
            runCatching { json.encodeToString(list) }.getOrNull()
        }
        val encodedTelemetry = safeTelemetry?.let { t ->
            runCatching { json.encodeToString(t) }.getOrNull()
        }
        val entity = ChatMessageEntity(
            id = message.id,
            threadId = threadId,
            turnOrder = turnOrder,
            branchGroupId = branchGroupId,
            revisionIndex = revisionIndex,
            isActiveBranch = isActiveBranch,
            role = message.role.name,
            text = message.text,
            thinkingText = message.thinkingText,
            imageUriString = message.imageUriString,
            citationsJson = encodedCitations,
            telemetryJson = encodedTelemetry,
            isInterrupted = message.isInterrupted,
            timestampMillis = message.timestampMillis
        )
        runCatching {
            messageDao.upsert(entity)
            threadDao.touchUpdatedAt(threadId, System.currentTimeMillis())
        }
    }

    suspend fun checkpointStreamingMessage(
        threadId: String,
        messageId: String,
        text: String,
        thinkingText: String,
        isInterrupted: Boolean = true
    ) {
        runCatching {
            messageDao.updateStreamingCheckpoint(
                id = messageId,
                text = text,
                thinkingText = thinkingText,
                isInterrupted = isInterrupted,
                timestampMillis = System.currentTimeMillis()
            )
            threadDao.touchUpdatedAt(threadId, System.currentTimeMillis())
        }
    }

    suspend fun selectBranch(threadId: String, branchGroupId: String, targetMessageId: String) {
        messageDao.selectBranch(threadId, branchGroupId, targetMessageId)
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteById(messageId)
    }

    suspend fun deleteFromTurnOrder(threadId: String, fromTurnOrder: Int) {
        messageDao.deleteFromTurnOrder(threadId, fromTurnOrder)
    }

    suspend fun loadActiveBranchMessages(
        threadId: String,
        trimBeforeTurnOrder: Int = 0
    ): List<ChatMessageUi> {
        val raw = messageDao.getForThread(threadId)
        return projectActiveBranchMessages(raw, trimBeforeTurnOrder)
    }

    fun projectActiveBranchMessages(
        entities: List<ChatMessageEntity>,
        trimBeforeTurnOrder: Int
    ): List<ChatMessageUi> {
        if (entities.isEmpty()) return emptyList()
        val groupedByTurn = entities.groupBy { it.branchGroupId }
        val result = mutableListOf<ChatMessageUi>()

        val orderedGroups = groupedByTurn.values
            .sortedWith(compareBy({ it.minOf { m -> m.turnOrder } }, { it.minOf { m -> m.timestampMillis } }))

        for (siblings in orderedGroups) {
            val sortedSiblings = siblings.sortedBy { it.revisionIndex }
            val active = sortedSiblings.firstOrNull { it.isActiveBranch } ?: sortedSiblings.last()
            val citations = active.citationsJson?.let {
                runCatching { json.decodeFromString<List<DocumentCitationUi>>(it) }.getOrNull()
            }.orEmpty()
            val telemetry = active.telemetryJson?.let {
                runCatching { json.decodeFromString<GenerationTelemetry>(it) }.getOrNull()
            }
            val role = runCatching { ChatRole.valueOf(active.role) }.getOrDefault(ChatRole.ASSISTANT)
            result += ChatMessageUi(
                id = active.id,
                role = role,
                text = active.text,
                thinkingText = active.thinkingText,
                isThinking = false,
                imageUriString = active.imageUriString,
                isStreaming = false,
                isInterrupted = active.isInterrupted,
                telemetry = telemetry,
                timestampMillis = active.timestampMillis,
                turnOrder = active.turnOrder,
                branchGroupId = active.branchGroupId,
                revisionIndex = sortedSiblings.indexOf(active).coerceAtLeast(0),
                siblingIds = sortedSiblings.map { it.id },
                citations = citations,
                isOutsideContextWindow = active.turnOrder < trimBeforeTurnOrder
            )
        }
        return result
    }

    suspend fun addCustomTemplate(trigger: String, label: String, promptBody: String) {
        val cleanTrigger = trigger.trim().removePrefix("/").lowercase().replace(Regex("\\s+"), "_")
        if (cleanTrigger.isBlank() || promptBody.isBlank()) return
        templateDao.upsert(
            PromptTemplateEntity(
                id = UUID.randomUUID().toString(),
                trigger = cleanTrigger,
                label = label.trim().ifEmpty { "/$cleanTrigger" },
                promptBody = promptBody.trim(),
                isBuiltIn = false
            )
        )
    }

    suspend fun deleteCustomTemplate(id: String) {
        templateDao.deleteCustom(id)
    }
}
