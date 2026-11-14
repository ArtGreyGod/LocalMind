package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.hub.HfModelFile
import com.example.data.hub.ModelDownloadTask
import com.example.data.storage.ImportProgress
import com.example.domain.engine.EngineLifecycleState
import com.example.domain.engine.LocalModelEntry
import com.example.domain.engine.MemoryStats
import com.example.domain.engine.ModelFormat
import com.example.ui.components.FloatingCircleButton
import com.example.ui.components.FloatingPillButton
import com.example.ui.components.HubDiscoverColumn
import com.example.ui.components.LocalMindIcons
import com.example.ui.components.SegmentedPillSwitcher
import com.example.ui.components.WrapFlowRow
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MotionTokens
import com.example.ui.theme.pressScale
import com.example.ui.theme.rememberPrefersReducedMotion
import com.example.ui.theme.tabular
import com.example.ui.viewmodel.HubBrowserState
import com.example.ui.viewmodel.PendingPreflightWarning
import kotlinx.coroutines.launch
import java.util.Locale

private enum class VaultTab(val label: String) {
    INSTALLED("Installed"),
    DISCOVER("Discover")
}

@Composable
fun ModelManagerScreen(
    models: List<LocalModelEntry>,
    activeModelId: String?,
    engineState: EngineLifecycleState,
    memoryStats: MemoryStats?,
    importProgress: ImportProgress?,
    pendingPreflightWarning: PendingPreflightWarning?,
    onImportModelUri: (Uri) -> Unit,
    onPairMmprojUri: (modelId: String, uri: Uri) -> Unit,
    onLoadModel: (LocalModelEntry, Boolean) -> Unit,
    onUnloadModel: () -> Unit,
    onDeleteModel: (LocalModelEntry) -> Unit,
    onDismissPreflightWarning: () -> Unit,
    onBackToWorkbench: () -> Unit = {},
    modifier: Modifier = Modifier,
    hubState: HubBrowserState = HubBrowserState(),
    hubDownloads: Map<String, ModelDownloadTask> = emptyMap(),
    onSearchHubModels: (String) -> Unit = {},
    onLoadHubRepoFiles: (String) -> Unit = {},
    onDownloadHubFile: (HfModelFile) -> Unit = {},
    onPauseHubDownload: (String) -> Unit = {},
    onCancelHubDownload: (String) -> Unit = {}
) {
    var pendingPairTargetId by remember { mutableStateOf<String?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val reducedMotion = rememberPrefersReducedMotion()
    val semantic = LocalSemanticColors.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = VaultTab.INSTALLED.ordinal) { VaultTab.entries.size }
    val installedListState = rememberLazyListState()
    val discoverListState = rememberLazyListState()

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportModelUri(uri)
        }
    }

    val mmprojPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = pendingPairTargetId
        pendingPairTargetId = null
        if (uri != null && targetId != null) {
            onPairMmprojUri(targetId, uri)
        }
    }

    if (pendingPreflightWarning != null) {
        AlertDialog(
            onDismissRequest = onDismissPreflightWarning,
            icon = {
                Icon(
                    imageVector = LocalMindIcons.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
            },
            title = { Text(stringResource(R.string.ram_warning_title)) },
            text = {
                Text(
                    text = pendingPreflightWarning.preflight.warningMessage
                        ?: "Available RAM is tight for this model.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { onLoadModel(pendingPreflightWarning.model, true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ),
                    modifier = Modifier.testTag("preflight_confirm_button")
                ) {
                    Text(stringResource(R.string.ram_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissPreflightWarning,
                    modifier = Modifier.testTag("preflight_cancel_button")
                ) {
                    Text(stringResource(R.string.ram_warning_cancel))
                }
            }
        )
    }

    val filteredModels = remember(models, searchQuery) {
        if (searchQuery.isBlank()) models
        else {
            val q = searchQuery.trim().lowercase()
            models.filter {
                it.displayName.lowercase().contains(q) ||
                    it.fileName.lowercase().contains(q) ||
                    it.format.label.lowercase().contains(q)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Main scrollable workspace column with gliding switcher + swipeable Installed/Discover pager
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 74.dp, start = 20.dp, end = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Model Vault",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "On-device GGUF, LiteRT-LM & Hugging Face weights",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SegmentedPillSwitcher(
                    labels = VaultTab.entries.map { it.label },
                    selectedIndex = pagerState.currentPage,
                    onSelect = { index ->
                        coroutineScope.launch {
                            if (reducedMotion) {
                                pagerState.scrollToPage(index)
                            } else {
                                pagerState.animateScrollToPage(
                                    page = index,
                                    animationSpec = MotionTokens.pageSpring()
                                )
                            }
                        }
                    },
                    testTags = VaultTab.entries.map { "vault_tab_${it.name.lowercase()}" },
                    positionProvider = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                    itemHorizontalPadding = 16.dp,
                    itemVerticalPadding = 8.dp,
                    modifier = Modifier.padding(top = 4.dp)
                )

                AnimatedVisibility(
                    visible = importProgress != null,
                    enter = if (reducedMotion) EnterTransition.None else (
                        fadeIn(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong)) +
                            expandVertically(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong))
                        ),
                    exit = if (reducedMotion) ExitTransition.None else (
                        fadeOut(tween(MotionTokens.DurationQuickMs)) +
                            shrinkVertically(tween(MotionTokens.DurationQuickMs))
                        )
                ) {
                    importProgress?.let { prog ->
                        ImportProgressBanner(
                            progress = prog,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (VaultTab.entries[page]) {
                    VaultTab.INSTALLED -> {
                        LazyColumn(
                            state = installedListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                top = 4.dp,
                                bottom = 128.dp
                            )
                        ) {
                            item(key = "installed_search") {
                                AnimatedVisibility(
                                    visible = searchOpen,
                                    enter = if (reducedMotion) EnterTransition.None else (
                                        fadeIn(tween(MotionTokens.DurationStandardMs)) +
                                            expandVertically(animationSpec = MotionTokens.snappySpring())
                                        ),
                                    exit = if (reducedMotion) ExitTransition.None else (
                                        fadeOut(tween(MotionTokens.DurationQuickMs)) +
                                            shrinkVertically(animationSpec = MotionTokens.snappySpring())
                                        )
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = { Text("Filter local models...") },
                                        singleLine = true,
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                        },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(onClick = { searchQuery = "" }) {
                                                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(24.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 16.dp)
                                    )
                                }
                            }

                            if (models.isEmpty()) {
                                item(key = "empty_vault") {
                                    EmptyVaultSection(
                                        onImportClick = { modelPickerLauncher.launch(arrayOf("*/*")) }
                                    )
                                }
                            } else {
                                item(key = "installed_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp, bottom = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Installed",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "${filteredModels.size}",
                                            style = MaterialTheme.typography.labelMedium.tabular(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                itemsIndexed(filteredModels, key = { _, item -> item.id }) { index, entry ->
                                    val isLoaded = entry.id == activeModelId &&
                                        (engineState is EngineLifecycleState.Ready ||
                                            engineState is EngineLifecycleState.Generating)
                                    val isLoadingThis = entry.id == activeModelId &&
                                        engineState is EngineLifecycleState.Loading

                                    Column(
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = if (reducedMotion) null else tween(
                                                MotionTokens.DurationStandardMs,
                                                easing = MotionTokens.EaseOutStrong
                                            ),
                                            fadeOutSpec = if (reducedMotion) null else tween(MotionTokens.DurationQuickMs),
                                            placementSpec = if (reducedMotion) null else MotionTokens.listItemSpring<IntOffset>()
                                        )
                                    ) {
                                        ModelWorkspaceRow(
                                            entry = entry,
                                            isLoaded = isLoaded,
                                            isLoading = isLoadingThis,
                                            onLoadClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onLoadModel(entry, false)
                                            },
                                            onUnloadClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onUnloadModel()
                                            },
                                            onPairMmprojClick = {
                                                pendingPairTargetId = entry.id
                                                mmprojPickerLauncher.launch(arrayOf("*/*"))
                                            },
                                            onDeleteClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onDeleteModel(entry)
                                            }
                                        )

                                        if (index < filteredModels.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    }
                                }

                                item(key = "add_model_footer") {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .minimumInteractiveComponentSize()
                                            .clickable { modelPickerLauncher.launch(arrayOf("*/*")) }
                                            .padding(vertical = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = LocalMindIcons.FolderOpen,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "Add Model",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    VaultTab.DISCOVER -> {
                        LazyColumn(
                            state = discoverListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 20.dp,
                                end = 20.dp,
                                top = 4.dp,
                                bottom = 128.dp
                            )
                        ) {
                            item(key = "discover_hub") {
                                HubDiscoverColumn(
                                    hubState = hubState,
                                    downloads = hubDownloads,
                                    memoryStats = memoryStats,
                                    onSearchHubModels = onSearchHubModels,
                                    onLoadRepoFiles = onLoadHubRepoFiles,
                                    onDownloadFile = onDownloadHubFile,
                                    onPauseDownload = onPauseHubDownload,
                                    onCancelDownload = onCancelHubDownload
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top soft gradient fade + floating circular navigation & action bar
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
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
            Row(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FloatingCircleButton(
                    onClick = onBackToWorkbench,
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Back to Workbench",
                    iconSize = 24.dp,
                    modifier = Modifier.testTag("nav_workbench")
                )

                // Center RAM pill (like Cursor's "Archived" / filter pill)
                if (memoryStats != null) {
                    val availGb = memoryStats.availableDeviceRamBytes / (1024.0 * 1024.0 * 1024.0)
                    val totalGb = memoryStats.totalDeviceRamBytes / (1024.0 * 1024.0 * 1024.0)
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = semantic.floatingSurface,
                        shadowElevation = if (semantic.isDark) 2.dp else 5.dp,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (memoryStats.isLowMemory) MaterialTheme.colorScheme.error
                                        else semantic.liveEmerald
                                    )
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f/%.1f GB free", availGb, totalGb),
                                style = MaterialTheme.typography.labelMedium.tabular(),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(
                        visible = pagerState.currentPage == VaultTab.INSTALLED.ordinal && models.isNotEmpty(),
                        enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(150)),
                        exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(110))
                    ) {
                        FloatingCircleButton(
                            onClick = { searchOpen = !searchOpen },
                            icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = "Search models",
                            iconSize = 20.dp
                        )
                    }
                    FloatingCircleButton(
                        onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                        icon = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.btn_import_model),
                        iconSize = 22.dp,
                        modifier = Modifier.testTag("import_model_fab")
                    )
                }
            }
        }

        // Bottom soft gradient + Cursor-style floating bottom capsule (jumps straight to Workbench)
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
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            val capsuleInteraction = remember { MutableInteractionSource() }
            Surface(
                onClick = onBackToWorkbench,
                interactionSource = capsuleInteraction,
                shape = RoundedCornerShape(28.dp),
                color = semantic.floatingSurface,
                shadowElevation = if (semantic.isDark) 4.dp else 10.dp,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
                ),
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .pressScale(capsuleInteraction, pressedScale = 0.985f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.btn_import_model),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Text(
                        text = if (activeModelId != null) {
                            "Ask active model in Workbench..."
                        } else {
                            "Open Workbench..."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = LocalMindIcons.ArrowUpward,
                                contentDescription = "Go to Workbench",
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportProgressBanner(
    progress: ImportProgress,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Importing ${progress.fileName}...",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (progress.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            } else {
                LinearProgressIndicator(
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
            Text(
                text = "${formatBytes(progress.bytesCopied)} copied",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyVaultSection(onImportClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Icon(
                imageVector = LocalMindIcons.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(18.dp)
                    .size(28.dp)
            )
        }
        Text(
            text = "No Local Models",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Import a .gguf, .litertlm, or .tflite file from storage. Pair an mmproj projector on GGUF models for offline Vision-Language.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        FloatingPillButton(
            onClick = onImportClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            borderColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("empty_import_button")
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.btn_import_model),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ModelWorkspaceRow(
    entry: LocalModelEntry,
    isLoaded: Boolean,
    isLoading: Boolean,
    onLoadClick: () -> Unit,
    onUnloadClick: () -> Unit,
    onPairMmprojClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    val reducedMotion = rememberPrefersReducedMotion()
    val pulseAlpha = if (isLoading && !reducedMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "modelLoadPulse")
        val anim by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = MotionTokens.EaseInOutStrong),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
        anim
    } else {
        1.0f
    }

    val loadInteraction = remember { MutableInteractionSource() }
    val pairInteraction = remember { MutableInteractionSource() }
    val deleteInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
            .testTag("model_card_${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Minimalist status dot (like Screenshot 1's leading indicator)
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .graphicsLayer { alpha = pulseAlpha }
                    .clip(CircleShape)
                    .background(
                        when {
                            isLoaded || isLoading -> semantic.liveEmerald
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isLoaded) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.format.label} • ${entry.quantizationLabel} • ${formatBytes(entry.totalFootprintBytes)}" +
                        if (entry.isVisionCapable) " • VL" else "",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isLoading) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Loading", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else if (isLoaded) {
                Surface(
                    onClick = onUnloadClick,
                    interactionSource = loadInteraction,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .pressScale(loadInteraction)
                        .testTag("unload_model_${entry.id}")
                ) {
                    Text(
                        text = stringResource(R.string.btn_unload_model),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            } else {
                Surface(
                    onClick = onLoadClick,
                    interactionSource = loadInteraction,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .pressScale(loadInteraction)
                        .testTag("load_model_${entry.id}")
                ) {
                    Text(
                        text = stringResource(R.string.btn_load_model),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }
        }

        // Secondary row: filename / mmproj pairing / delete (whisper-quiet hairline actions)
        WrapFlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 21.dp),
            horizontalSpacing = 10.dp,
            verticalSpacing = 6.dp
        ) {
            if (entry.format == ModelFormat.GGUF) {
                Surface(
                    onClick = onPairMmprojClick,
                    interactionSource = pairInteraction,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .pressScale(pairInteraction)
                        .testTag("pair_mmproj_${entry.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = LocalMindIcons.Visibility,
                            contentDescription = null,
                            tint = if (entry.mmprojFilePath != null) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (entry.mmprojFilePath == null) {
                                stringResource(R.string.btn_pair_mmproj)
                            } else {
                                "mmproj: ${entry.mmprojFileName ?: "paired"}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Surface(
                onClick = onDeleteClick,
                interactionSource = deleteInteraction,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .pressScale(deleteInteraction)
                    .testTag("delete_model_${entry.id}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = LocalMindIcons.DeleteOutline,
                        contentDescription = stringResource(R.string.btn_delete_model),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.btn_delete_model),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
