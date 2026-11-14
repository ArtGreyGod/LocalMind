package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.DocumentCitationUi
import com.example.data.local.IndexedDocumentEntity
import com.example.data.local.SlashCommandItem
import com.example.domain.engine.EngineLifecycleState
import com.example.domain.engine.LocalModelEntry
import com.example.ui.components.AttachedDocumentsStrip
import com.example.ui.components.BranchSwitcherPill
import com.example.ui.components.CameraCaptureDialog
import com.example.ui.components.CitationsChipRow
import com.example.ui.components.ContextWindowGaugePill
import com.example.ui.components.FloatingCircleButton
import com.example.ui.components.FloatingPillButton
import com.example.ui.components.MarkdownText
import com.example.ui.components.PendingAttachmentCard
import com.example.ui.components.SlashCommandPopover
import com.example.ui.components.VoiceListeningBanner
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MotionTokens
import com.example.ui.theme.pressScale
import com.example.ui.theme.rememberPrefersReducedMotion
import com.example.ui.theme.tabular
import com.example.ui.viewmodel.ChatMessageUi
import com.example.ui.viewmodel.ChatRole
import com.example.ui.viewmodel.LocalMindUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    uiState: LocalMindUiState,
    engineState: EngineLifecycleState,
    activeModel: LocalModelEntry?,
    onSendPrompt: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onResetConversation: () -> Unit,
    onRegenerateLastResponse: () -> Unit,
    onEditLastUserMessage: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onBuildMarkdownTranscript: () -> String,
    onShowStatusBanner: (String) -> Unit,
    onAttachImageUri: (Uri) -> Unit,
    onClearPendingImage: () -> Unit,
    onToggleThinking: () -> Unit,
    onOpenModelVault: () -> Unit,
    onOpenTelemetrySheet: () -> Unit,
    modifier: Modifier = Modifier,
    attachedDocuments: List<IndexedDocumentEntity> = emptyList(),
    slashCommands: List<SlashCommandItem> = emptyList(),
    isListening: Boolean = false,
    partialTranscript: String = "",
    speakingMessageId: String? = null,
    onAttachDocumentUri: (Uri) -> Unit = {},
    onRemoveDocument: (String) -> Unit = {},
    onExecuteSlashAction: (String) -> Unit = {},
    onSelectBranch: (branchGroupId: String, targetMessageId: String) -> Unit = { _, _ -> },
    onTrimOlderContextTurns: () -> Unit = {},
    onStartVoiceDictation: ((String) -> Unit) -> Unit = {},
    onStopVoiceDictation: () -> Unit = {},
    onToggleReadAloud: (String, String) -> Unit = { _, _ -> },
    onOpenThreadsHub: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val reducedMotion = rememberPrefersReducedMotion()
    val semantic = LocalSemanticColors.current

    var promptInput by rememberSaveable { mutableStateOf("") }
    var showCameraDialog by rememberSaveable { mutableStateOf(false) }
    var showComposerMenu by remember { mutableStateOf(false) }
    var editingPromptText by rememberSaveable { mutableStateOf<String?>(null) }
    var showTrimConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var inspectingCitation by remember { mutableStateOf<DocumentCitationUi?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onAttachImageUri(uri)
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onAttachDocumentUri(uri)
    }

    val appendDictatedText: (String) -> Unit = { spoken ->
        promptInput = if (promptInput.isBlank()) spoken else "${promptInput.trimEnd()} $spoken"
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartVoiceDictation(appendDictatedText)
        } else {
            onShowStatusBanner("Microphone permission is required for offline voice dictation.")
        }
    }

    val launchDocumentPicker = {
        documentPickerLauncher.launch(
            arrayOf("application/pdf", "text/*", "application/json")
        )
    }

    val listState = rememberLazyListState()
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 1
            }
        }
    }

    val lastMsg = uiState.messages.lastOrNull()
    LaunchedEffect(uiState.messages.size, lastMsg?.text?.length, lastMsg?.thinkingText?.length) {
        if (uiState.messages.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    val isGenerating = engineState is EngineLifecycleState.Generating
    val isLoading = engineState is EngineLifecycleState.Loading
    val isReady = engineState is EngineLifecycleState.Ready

    val matchingSlashCommands = remember(promptInput, slashCommands) {
        if (!promptInput.startsWith("/")) emptyList()
        else {
            val filter = promptInput.removePrefix("/").substringBefore(' ').trim().lowercase()
            slashCommands.filter {
                filter.isEmpty() || it.trigger.lowercase().contains(filter) || it.label.lowercase().contains(filter)
            }
        }
    }

    val exportAction: () -> Unit = {
        val md = onBuildMarkdownTranscript()
        clipboard.setText(AnnotatedString(md))
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "LocalMind Chat Transcript")
            putExtra(Intent.EXTRA_TEXT, md)
        }
        val chooser = Intent.createChooser(sendIntent, "Export Markdown Transcript")
        runCatching { context.startActivity(chooser) }
        onShowStatusBanner("Markdown transcript copied & ready to share")
    }

    DisposableEffect(isGenerating, uiState.keepScreenOnDuringInference) {
        val window = context.findActivity()?.window
        val shouldKeepOn = isGenerating && uiState.keepScreenOnDuringInference
        if (shouldKeepOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    if (showCameraDialog) {
        CameraCaptureDialog(
            onPhotoCaptured = { uri ->
                showCameraDialog = false
                onAttachImageUri(uri)
            },
            onDismiss = { showCameraDialog = false }
        )
    }

    if (showTrimConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showTrimConfirmDialog = false },
            title = { Text("Trim Active Context Window?") },
            text = {
                Text(
                    "Older turns will be excluded from the model's active KV cache to reclaim headroom, while remaining readable in your local thread history.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTrimConfirmDialog = false
                        onTrimOlderContextTurns()
                    }
                ) {
                    Text("Trim Older Turns")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrimConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    inspectingCitation?.let { cit ->
        AlertDialog(
            onDismissRequest = { inspectingCitation = null },
            title = { Text("${cit.documentName} · ${cit.sectionLabel}") },
            text = {
                Text(
                    text = cit.snippet,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { inspectingCitation = null }) {
                    Text("Close")
                }
            }
        )
    }

    if (editingPromptText != null) {
        AlertDialog(
            onDismissRequest = { editingPromptText = null },
            title = { Text("Edit & Resend Prompt") },
            text = {
                OutlinedTextField(
                    value = editingPromptText.orEmpty(),
                    onValueChange = { editingPromptText = it },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_prompt_dialog_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = editingPromptText.orEmpty()
                        editingPromptText = null
                        onEditLastUserMessage(updated)
                    },
                    enabled = !editingPromptText.isNullOrBlank(),
                    modifier = Modifier.testTag("confirm_edit_prompt_button")
                ) {
                    Text("Resend")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPromptText = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val lastUserIndex = remember(uiState.messages) {
        uiState.messages.indexOfLast { it.role == ChatRole.USER }
    }

    val activeContextUsed = remember(uiState.memoryStats, uiState.estimatedContextTokens) {
        val reported = uiState.memoryStats?.kvCacheUsedTokens ?: 0
        if (reported > 0) reported else uiState.estimatedContextTokens
    }
    val activeContextMax = remember(uiState.memoryStats, uiState.tuning.contextLength) {
        val reportedMax = uiState.memoryStats?.kvCacheMaxTokens ?: 0
        if (reportedMax > 0) reportedMax else uiState.tuning.contextLength
    }
    val canTrimContext = remember(uiState.messages) {
        uiState.messages.count { !it.isOutsideContextWindow } > 2
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        if (uiState.messages.isEmpty()) {
            WorkbenchEmptyState(
                isModelLoaded = isReady || isGenerating,
                activeModel = activeModel,
                onOpenModelVault = onOpenModelVault,
                onPickSuggestion = { suggestion -> promptInput = suggestion },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = 96.dp, bottom = 160.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .align(Alignment.TopCenter)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 128.dp,
                    bottom = 204.dp
                ),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                itemsIndexed(uiState.messages, key = { _, msg -> msg.id }) { index, msg ->
                    val prevMsg = if (index > 0) uiState.messages[index - 1] else null
                    val showTrimBoundary = prevMsg?.isOutsideContextWindow == true && !msg.isOutsideContextWindow

                    if (showTrimBoundary) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f), thickness = 0.5.dp)
                            Text(
                                text = "Active context window",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f), thickness = 0.5.dp)
                        }
                    }

                    val isLastMessage = index == uiState.messages.lastIndex
                    val canRegenerate = isReady && !isGenerating && isLastMessage &&
                        msg.role == ChatRole.ASSISTANT && index > 0 &&
                        uiState.messages[index - 1].role == ChatRole.USER
                    val canEditPrompt = isReady && !isGenerating &&
                        msg.role == ChatRole.USER && index == lastUserIndex

                    EditorialChatItem(
                        message = msg,
                        canRegenerate = canRegenerate,
                        canEditPrompt = canEditPrompt,
                        isSpeakingThis = speakingMessageId == msg.id,
                        onCopyMessage = { content ->
                            clipboard.setText(AnnotatedString(content))
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onShowStatusBanner("Message copied")
                        },
                        onToggleReadAloud = {
                            onToggleReadAloud(msg.id, msg.text.ifEmpty { msg.thinkingText })
                        },
                        onSelectBranch = { targetId ->
                            onSelectBranch(msg.branchGroupId, targetId)
                        },
                        onInspectCitation = { cit -> inspectingCitation = cit },
                        onRegenerate = onRegenerateLastResponse,
                        onEditPrompt = { editingPromptText = msg.text },
                        onDeleteMessage = { onDeleteMessage(msg.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (msg.isOutsideContextWindow) 0.55f else 1f)
                    )
                }
            }
        }

        // Layer 2: Floating Top Bar + Workspace / Context Window Gauge sub-row
        FloatingThreadTopBar(
            engineState = engineState,
            activeModel = activeModel,
            workspaceName = uiState.activeWorkspaceName,
            liveDecodeTokPerSec = uiState.liveDecodeTokensPerSec,
            contextUsedTokens = activeContextUsed,
            contextMaxTokens = activeContextMax,
            canTrimContext = canTrimContext,
            onTrimContextClick = { showTrimConfirmDialog = true },
            onOpenThreadsHub = onOpenThreadsHub,
            onOpenModelVault = onOpenModelVault,
            onResetConversation = onResetConversation,
            onOpenTelemetrySheet = onOpenTelemetrySheet,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // Layer 3: Jump to bottom floating pill
        AnimatedVisibility(
            visible = !isAtBottom && uiState.messages.isNotEmpty(),
            enter = if (reducedMotion) EnterTransition.None else (
                fadeIn(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong)) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong)
                    )
                ),
            exit = if (reducedMotion) ExitTransition.None else (
                fadeOut(tween(MotionTokens.DurationQuickMs)) +
                    scaleOut(targetScale = 0.92f, animationSpec = tween(MotionTokens.DurationQuickMs))
                ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 156.dp)
        ) {
            FloatingCircleButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(uiState.messages.lastIndex)
                    }
                },
                icon = Icons.Rounded.ArrowDownward,
                contentDescription = stringResource(R.string.btn_jump_to_bottom),
                modifier = Modifier.testTag("jump_to_bottom_button")
            )
        }

        // Layer 4: Bottom scrim + Slash Popover + Voice + Docs/Images + Pills + Capsule Composer
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(
                    visible = matchingSlashCommands.isNotEmpty(),
                    enter = fadeIn(tween(120)) + expandVertically(),
                    exit = fadeOut(tween(100)) + shrinkVertically()
                ) {
                    SlashCommandPopover(
                        commands = matchingSlashCommands,
                        onSelectCommand = { cmd ->
                            if (cmd.isActionCommand) {
                                promptInput = ""
                                onExecuteSlashAction(cmd.id)
                            } else {
                                promptInput = cmd.promptBody
                            }
                        }
                    )
                }

                AnimatedVisibility(
                    visible = isListening,
                    enter = fadeIn(tween(120)) + expandVertically(),
                    exit = fadeOut(tween(100)) + shrinkVertically()
                ) {
                    VoiceListeningBanner(
                        partialTranscript = partialTranscript,
                        onStopDictation = onStopVoiceDictation
                    )
                }

                AnimatedVisibility(
                    visible = isGenerating || isLoading || uiState.isPreparingImage,
                    enter = fadeIn(tween(140)) + expandVertically(),
                    exit = fadeOut(tween(120)) + shrinkVertically()
                ) {
                    val statusLabel = when {
                        uiState.isPreparingImage -> "Preparing image..."
                        isLoading -> (engineState as? EngineLifecycleState.Loading)?.stage ?: "Loading weights..."
                        lastMsg?.isThinking == true -> "Thinking..."
                        else -> "Working"
                    }
                    WorkingStatusIndicator(label = statusLabel)
                }

                AnimatedVisibility(
                    visible = attachedDocuments.isNotEmpty() || uiState.isIndexingDocument,
                    enter = fadeIn(tween(140)) + expandVertically(),
                    exit = fadeOut(tween(120)) + shrinkVertically()
                ) {
                    AttachedDocumentsStrip(
                        documents = attachedDocuments,
                        isIndexing = uiState.isIndexingDocument,
                        onRemoveDocument = onRemoveDocument
                    )
                }

                AnimatedVisibility(
                    visible = uiState.pendingImage != null || uiState.isPreparingImage,
                    enter = if (reducedMotion) EnterTransition.None else (
                        fadeIn(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong)) +
                            expandVertically(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong))
                        ),
                    exit = if (reducedMotion) ExitTransition.None else (
                        fadeOut(tween(MotionTokens.DurationQuickMs)) +
                            shrinkVertically(tween(MotionTokens.DurationQuickMs))
                        )
                ) {
                    PendingAttachmentCard(
                        imageUri = uiState.pendingImage?.uriString,
                        resolutionLabel = uiState.pendingImage?.let { "${it.width}×${it.height} RGB" },
                        isPreparing = uiState.isPreparingImage,
                        visionWarning = activeModel != null && !activeModel.isVisionCapable,
                        onPickVisionPrompt = { quick -> promptInput = quick },
                        onClear = onClearPendingImage
                    )
                }

                // Floating quick-action mini pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val thinkOn = uiState.tuning.enableThinking
                    FloatingPillButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onToggleThinking()
                        },
                        enabled = !isGenerating,
                        containerColor = if (thinkOn) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            semantic.floatingSurface
                        },
                        contentColor = if (thinkOn) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        borderColor = if (thinkOn) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
                        },
                        modifier = Modifier.testTag("thinking_toggle_chip")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Psychology,
                            contentDescription = stringResource(R.string.btn_toggle_thinking),
                            tint = if (thinkOn) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (thinkOn) "Think: ON" else "Think: OFF",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    FloatingPillButton(
                        onClick = launchDocumentPicker,
                        modifier = Modifier.testTag("attach_document_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = stringResource(R.string.btn_attach_document),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Docs",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    FloatingPillButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("photo_picker_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = stringResource(R.string.btn_attach_photo),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Photo",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    FloatingPillButton(
                        onClick = { showCameraDialog = true },
                        modifier = Modifier.testTag("camera_capture_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = stringResource(R.string.btn_capture_camera),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Camera",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    if (uiState.messages.isNotEmpty()) {
                        FloatingPillButton(
                            onClick = exportAction,
                            modifier = Modifier.testTag("export_markdown_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.btn_export_markdown),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Export",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // Main Floating Capsule Prompt Composer (Cursor iOS: + | Prompt / Slash | Mic -> Send -> Stop)
                val sendInteraction = remember { MutableInteractionSource() }
                val stopInteraction = remember { MutableInteractionSource() }
                val micInteraction = remember { MutableInteractionSource() }

                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = semantic.floatingSurface,
                    shadowElevation = if (semantic.isDark) 4.dp else 10.dp,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box {
                            Surface(
                                onClick = { showComposerMenu = true },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .size(44.dp)
                                    .minimumInteractiveComponentSize()
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "Quick actions menu",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showComposerMenu,
                                onDismissRequest = { showComposerMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Attach Document (PDF / TXT)") },
                                    leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                                    onClick = {
                                        showComposerMenu = false
                                        launchDocumentPicker()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Attach Photo") },
                                    leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null) },
                                    onClick = {
                                        showComposerMenu = false
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Capture Photo") },
                                    leadingIcon = { Icon(Icons.Rounded.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        showComposerMenu = false
                                        showCameraDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Slash Commands (/)") },
                                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                    onClick = {
                                        showComposerMenu = false
                                        promptInput = "/"
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Customize & Telemetry") },
                                    leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                                    onClick = {
                                        showComposerMenu = false
                                        onOpenTelemetrySheet()
                                    }
                                )
                                if (uiState.messages.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Export Markdown") },
                                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                        onClick = {
                                            showComposerMenu = false
                                            exportAction()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("New Thread") },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                    onClick = {
                                        showComposerMenu = false
                                        onResetConversation()
                                    }
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (promptInput.isEmpty()) {
                                Text(
                                    text = if (uiState.messages.isEmpty()) {
                                        stringResource(R.string.prompt_hint)
                                    } else {
                                        "Follow up or type /..."
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = promptInput,
                                onValueChange = { promptInput = it },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 5,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("prompt_input_field")
                            )
                        }

                        val actionMode = when {
                            isGenerating -> ComposerActionMode.STOP
                            promptInput.isBlank() && uiState.pendingImage == null -> ComposerActionMode.MIC
                            else -> ComposerActionMode.SEND
                        }

                        AnimatedContent(
                            targetState = actionMode,
                            transitionSpec = {
                                if (reducedMotion) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    (fadeIn(tween(150, easing = MotionTokens.EaseOutStrong)) +
                                        scaleIn(initialScale = 0.88f, animationSpec = tween(150, easing = MotionTokens.EaseOutStrong))) togetherWith
                                        (fadeOut(tween(110)) + scaleOut(targetScale = 0.88f, animationSpec = tween(110)))
                                }
                            },
                            label = "composerActionMorph"
                        ) { mode ->
                            when (mode) {
                                ComposerActionMode.STOP -> {
                                    Surface(
                                        onClick = onStopGeneration,
                                        interactionSource = stopInteraction,
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .minimumInteractiveComponentSize()
                                            .pressScale(stopInteraction, pressedScale = 0.90f)
                                            .testTag("stop_generation_button")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Stop,
                                                contentDescription = stringResource(R.string.btn_stop_generation),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                ComposerActionMode.MIC -> {
                                    Surface(
                                        onClick = {
                                            if (isListening) {
                                                onStopVoiceDictation()
                                            } else {
                                                val granted = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.RECORD_AUDIO
                                                ) == PackageManager.PERMISSION_GRANTED
                                                if (granted) {
                                                    onStartVoiceDictation(appendDictatedText)
                                                } else {
                                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                }
                                            }
                                        },
                                        interactionSource = micInteraction,
                                        shape = CircleShape,
                                        color = if (isListening) {
                                            semantic.liveEmerald
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        contentColor = if (isListening) {
                                            Color.White
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .minimumInteractiveComponentSize()
                                            .pressScale(micInteraction, pressedScale = 0.90f)
                                            .testTag("voice_dictate_button")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                                contentDescription = if (isListening) {
                                                    stringResource(R.string.btn_stop_dictate)
                                                } else {
                                                    stringResource(R.string.btn_voice_dictate)
                                                },
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                ComposerActionMode.SEND -> {
                                    val canSend = isReady && (promptInput.isNotBlank() || uiState.pendingImage != null)
                                    Surface(
                                        onClick = {
                                            val text = promptInput
                                            promptInput = ""
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onSendPrompt(text)
                                        },
                                        enabled = canSend,
                                        interactionSource = sendInteraction,
                                        shape = CircleShape,
                                        color = if (canSend) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        contentColor = if (canSend) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                        },
                                        modifier = Modifier
                                            .size(44.dp)
                                            .minimumInteractiveComponentSize()
                                            .pressScale(sendInteraction, enabled = canSend, pressedScale = 0.90f)
                                            .testTag("send_prompt_button")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.ArrowUpward,
                                                contentDescription = stringResource(R.string.btn_send_prompt),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ComposerActionMode {
    STOP,
    MIC,
    SEND
}

@Composable
private fun FloatingThreadTopBar(
    engineState: EngineLifecycleState,
    activeModel: LocalModelEntry?,
    workspaceName: String,
    liveDecodeTokPerSec: Double,
    contextUsedTokens: Int,
    contextMaxTokens: Int,
    canTrimContext: Boolean,
    onTrimContextClick: () -> Unit,
    onOpenThreadsHub: () -> Unit,
    onOpenModelVault: () -> Unit,
    onResetConversation: () -> Unit,
    onOpenTelemetrySheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberPrefersReducedMotion()
    val semantic = LocalSemanticColors.current
    val isLive = engineState is EngineLifecycleState.Generating || engineState is EngineLifecycleState.Loading
    val pulseAlpha = if (isLive && !reducedMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
        val anim by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = MotionTokens.EaseInOutStrong),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
        anim
    } else {
        1.0f
    }

    val selectorInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        Color.Transparent
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .align(Alignment.Center)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FloatingCircleButton(
                    onClick = onOpenThreadsHub,
                    icon = Icons.Rounded.Forum,
                    contentDescription = stringResource(R.string.btn_open_threads),
                    iconSize = 20.dp,
                    modifier = Modifier.testTag("nav_threads")
                )

                // Center floating active model / Vault pill
                Surface(
                    onClick = onOpenModelVault,
                    interactionSource = selectorInteraction,
                    shape = RoundedCornerShape(24.dp),
                    color = semantic.floatingSurface,
                    shadowElevation = if (semantic.isDark) 2.dp else 5.dp,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .minimumInteractiveComponentSize()
                        .pressScale(selectorInteraction, pressedScale = 0.98f)
                        .testTag("nav_vault")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val dotColor = when (engineState) {
                            is EngineLifecycleState.Ready,
                            is EngineLifecycleState.Generating,
                            is EngineLifecycleState.Loading -> semantic.liveEmerald
                            is EngineLifecycleState.Error -> MaterialTheme.colorScheme.error
                            is EngineLifecycleState.Idle -> MaterialTheme.colorScheme.outline
                        }
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .graphicsLayer { alpha = pulseAlpha }
                                .clip(CircleShape)
                                .background(dotColor)
                        )

                        Text(
                            text = activeModel?.displayName ?: "Select Model",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (liveDecodeTokPerSec > 0.0) {
                            Text(
                                text = "${"%.1f".format(liveDecodeTokPerSec)} t/s",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        } else if (activeModel?.isVisionCapable == true) {
                            Text(
                                text = "VL",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                FloatingCircleButton(
                    onClick = onResetConversation,
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.btn_reset_session),
                    iconSize = 19.dp,
                    modifier = Modifier.testTag("reset_chat_button")
                )

                FloatingCircleButton(
                    onClick = onOpenTelemetrySheet,
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.btn_telemetry),
                    iconSize = 20.dp,
                    modifier = Modifier.testTag("telemetry_button")
                )
            }

            // Quiet workspace & context window status sub-row
            if (contextUsedTokens > 0 || activeModel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = workspaceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ContextWindowGaugePill(
                        usedTokens = contextUsedTokens,
                        maxTokens = contextMaxTokens,
                        canTrim = canTrimContext,
                        onClick = onTrimContextClick
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkingStatusIndicator(label: String) {
    val reducedMotion = rememberPrefersReducedMotion()
    val alpha = if (!reducedMotion) {
        val transition = rememberInfiniteTransition(label = "workingPulse")
        val anim by transition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(750, easing = MotionTokens.EaseInOutStrong),
                repeatMode = RepeatMode.Reverse
            ),
            label = "workingAlpha"
        )
        anim
    } else {
        1.0f
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(LocalSemanticColors.current.liveEmerald)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkbenchEmptyState(
    isModelLoaded: Boolean,
    activeModel: LocalModelEntry?,
    onOpenModelVault: () -> Unit,
    onPickSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val vaultInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier.padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(44.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_local_ai_hero_1794535707681),
                        contentDescription = stringResource(R.string.hero_image_desc),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (isModelLoaded) {
                            activeModel?.displayName ?: "Model Ready"
                        } else {
                            "Offline Engine"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (isModelLoaded) {
                            "Private on-device weights active"
                        } else {
                            "LiteRT-LM & llama.cpp runtime"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = if (isModelLoaded) "What would you like to explore?" else "Load a local model to begin",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (!isModelLoaded) {
                Text(
                    text = stringResource(R.string.empty_chat_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FloatingPillButton(
                    onClick = onOpenModelVault,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    borderColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .pressScale(vaultInteraction)
                        .testTag("empty_open_vault_button")
                ) {
                    Text(
                        text = stringResource(R.string.btn_open_vault),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                val prompts = listOf(
                    "Compare INT4 vs Q4_K_M quantization in a Markdown table.",
                    "Write a Kotlin Flow debounce example with explanation.",
                    "Explain how NPU delegation accelerates KV cache prefill."
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    prompts.forEachIndexed { idx, sample ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable { onPickSuggestion(sample) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = sample,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (idx < prompts.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorialChatItem(
    message: ChatMessageUi,
    canRegenerate: Boolean,
    canEditPrompt: Boolean,
    isSpeakingThis: Boolean,
    onCopyMessage: (String) -> Unit,
    onToggleReadAloud: () -> Unit,
    onSelectBranch: (String) -> Unit,
    onInspectCitation: (DocumentCitationUi) -> Unit,
    onRegenerate: () -> Unit,
    onEditPrompt: () -> Unit,
    onDeleteMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == ChatRole.USER
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1600)
            justCopied = false
        }
    }

    if (isUser) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                ),
                modifier = Modifier.widthIn(max = 560.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!message.imageUriString.isNullOrBlank()) {
                        AsyncImage(
                            model = message.imageUriString,
                            contentDescription = "User multimodal prompt image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }

                    MarkdownText(
                        markdown = message.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        isStreaming = false,
                        codeContainerColor = MaterialTheme.colorScheme.surface,
                        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
                        accentColor = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            MessageActionRow(
                justCopied = justCopied,
                canEditPrompt = canEditPrompt,
                canRegenerate = false,
                canReadAloud = false,
                isSpeakingThis = false,
                revisionIndex = message.revisionIndex,
                siblingIds = message.siblingIds,
                onSelectBranch = onSelectBranch,
                onCopy = {
                    onCopyMessage(message.text)
                    justCopied = true
                },
                onToggleReadAloud = {},
                onEdit = onEditPrompt,
                onRegenerate = onRegenerate,
                onDelete = onDeleteMessage
            )
        }
    } else {
        Column(
            modifier = modifier.widthIn(max = 680.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (message.thinkingText.isNotBlank() || message.isThinking) {
                ThinkingProcessCard(
                    messageId = message.id,
                    thinkingText = message.thinkingText,
                    isThinking = message.isThinking,
                    thoughtTokens = message.telemetry?.thoughtTokens ?: 0,
                    keepExpandedOnEmptyAnswer = !message.isStreaming && message.text.isBlank()
                )
            }

            val showAnswerText = message.text.isNotEmpty() || (message.isStreaming && !message.isThinking)
            if (showAnswerText) {
                if (message.text.isEmpty() && message.isStreaming) {
                    Text(
                        text = if (message.thinkingText.isNotEmpty()) {
                            "Synthesizing response..."
                        } else {
                            "Generating..."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    MarkdownText(
                        markdown = message.text,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge,
                        isStreaming = message.isStreaming && !message.isThinking,
                        codeContainerColor = MaterialTheme.colorScheme.surface,
                        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerLow,
                        accentColor = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (message.citations.isNotEmpty()) {
                CitationsChipRow(
                    citations = message.citations,
                    onInspectCitation = onInspectCitation
                )
            }

            message.telemetry?.let { stats ->
                val thoughtPart = if (stats.thoughtTokens > 0) " • ${stats.thoughtTokens} thought" else ""
                Text(
                    text = "${stats.activeAccelerator.label} • Prefill ${"%.1f".format(stats.prefillTokensPerSec)} t/s • Decode ${"%.1f".format(stats.decodeTokensPerSec)} t/s (${stats.decodeTokens} tok$thoughtPart)",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                )
            }

            if (!message.isStreaming && (message.text.isNotEmpty() || message.thinkingText.isNotBlank())) {
                MessageActionRow(
                    justCopied = justCopied,
                    canEditPrompt = false,
                    canRegenerate = canRegenerate,
                    canReadAloud = message.text.isNotBlank(),
                    isSpeakingThis = isSpeakingThis,
                    revisionIndex = message.revisionIndex,
                    siblingIds = message.siblingIds,
                    onSelectBranch = onSelectBranch,
                    onCopy = {
                        onCopyMessage(message.text.ifEmpty { message.thinkingText })
                        justCopied = true
                    },
                    onToggleReadAloud = onToggleReadAloud,
                    onEdit = onEditPrompt,
                    onRegenerate = onRegenerate,
                    onDelete = onDeleteMessage
                )
            }
        }
    }
}

@Composable
private fun MessageActionRow(
    justCopied: Boolean,
    canEditPrompt: Boolean,
    canRegenerate: Boolean,
    canReadAloud: Boolean,
    isSpeakingThis: Boolean,
    revisionIndex: Int,
    siblingIds: List<String>,
    onSelectBranch: (String) -> Unit,
    onCopy: () -> Unit,
    onToggleReadAloud: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit
) {
    val copyInteraction = remember { MutableInteractionSource() }
    val speakInteraction = remember { MutableInteractionSource() }
    val editInteraction = remember { MutableInteractionSource() }
    val regenInteraction = remember { MutableInteractionSource() }
    val deleteInteraction = remember { MutableInteractionSource() }
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(
            onClick = onCopy,
            interactionSource = copyInteraction,
            modifier = Modifier
                .size(48.dp)
                .pressScale(copyInteraction, pressedScale = 0.90f)
                .testTag("copy_message_button")
        ) {
            Icon(
                imageVector = if (justCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.btn_copy_message),
                tint = if (justCopied) LocalSemanticColors.current.liveEmerald else iconTint,
                modifier = Modifier.size(17.dp)
            )
        }

        if (canReadAloud) {
            IconButton(
                onClick = onToggleReadAloud,
                interactionSource = speakInteraction,
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(speakInteraction, pressedScale = 0.90f)
                    .testTag("read_aloud_button")
            ) {
                Icon(
                    imageVector = if (isSpeakingThis) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = if (isSpeakingThis) {
                        stringResource(R.string.btn_stop_speaking)
                    } else {
                        stringResource(R.string.btn_read_aloud)
                    },
                    tint = if (isSpeakingThis) LocalSemanticColors.current.liveEmerald else iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        if (canEditPrompt) {
            IconButton(
                onClick = onEdit,
                interactionSource = editInteraction,
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(editInteraction, pressedScale = 0.90f)
                    .testTag("edit_prompt_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.btn_edit_prompt),
                    tint = iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        if (canRegenerate) {
            IconButton(
                onClick = onRegenerate,
                interactionSource = regenInteraction,
                modifier = Modifier
                    .size(48.dp)
                    .pressScale(regenInteraction, pressedScale = 0.90f)
                    .testTag("regenerate_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Replay,
                    contentDescription = stringResource(R.string.btn_regenerate),
                    tint = iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        if (siblingIds.size > 1) {
            BranchSwitcherPill(
                revisionIndex = revisionIndex,
                siblingIds = siblingIds,
                onSelectBranch = onSelectBranch,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        IconButton(
            onClick = onDelete,
            interactionSource = deleteInteraction,
            modifier = Modifier
                .size(48.dp)
                .pressScale(deleteInteraction, pressedScale = 0.90f)
                .testTag("delete_message_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = stringResource(R.string.btn_delete_message),
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@Composable
private fun ThinkingProcessCard(
    messageId: String,
    thinkingText: String,
    isThinking: Boolean,
    thoughtTokens: Int,
    keepExpandedOnEmptyAnswer: Boolean = false
) {
    val reducedMotion = rememberPrefersReducedMotion()
    var expanded by rememberSaveable(messageId) { mutableStateOf(isThinking || keepExpandedOnEmptyAnswer) }
    LaunchedEffect(isThinking, keepExpandedOnEmptyAnswer) {
        if (isThinking || keepExpandedOnEmptyAnswer) {
            expanded = true
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reducedMotion) tween(0) else tween(200, easing = MotionTokens.EaseInOutStrong),
        label = "thinkingChevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("thinking_block")
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isThinking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (isThinking) {
                    "Thinking"
                } else if (thoughtTokens > 0) {
                    "Thought  $thoughtTokens tok"
                } else {
                    "Thought"
                },
                style = MaterialTheme.typography.titleSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse thought process" else "Expand thought process",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }

        AnimatedVisibility(
            visible = expanded && thinkingText.isNotBlank(),
            enter = if (reducedMotion) EnterTransition.None else (
                fadeIn(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong)) +
                    expandVertically(tween(MotionTokens.DurationPanelMs, easing = MotionTokens.EaseOutStrong))
                ),
            exit = if (reducedMotion) ExitTransition.None else (
                fadeOut(tween(MotionTokens.DurationQuickMs)) +
                    shrinkVertically(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong))
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 2.dp, bottom = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(
                            if (isThinking) {
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                )
                MarkdownText(
                    markdown = thinkingText.trim(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    isStreaming = isThinking,
                    codeContainerColor = MaterialTheme.colorScheme.surface,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
