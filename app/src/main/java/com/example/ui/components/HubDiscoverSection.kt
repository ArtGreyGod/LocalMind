package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.hub.DownloadStatus
import com.example.data.hub.HfModelFile
import com.example.data.hub.HfRepoSummary
import com.example.data.hub.ModelDownloadTask
import com.example.ui.components.LocalMindIcons
import com.example.domain.engine.MemoryStats
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MotionTokens
import com.example.ui.theme.rememberPrefersReducedMotion
import com.example.ui.theme.tabular
import com.example.ui.viewmodel.HubBrowserState
import java.util.Locale

@Composable
fun HubDiscoverColumn(
    hubState: HubBrowserState,
    downloads: Map<String, ModelDownloadTask>,
    memoryStats: MemoryStats?,
    onSearchHubModels: (String) -> Unit,
    onLoadRepoFiles: (String) -> Unit,
    onDownloadFile: (HfModelFile) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val haptics = LocalHapticFeedback.current
    var searchText by rememberSaveable { mutableStateOf(hubState.query) }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var expandedRepoIds by rememberSaveable { mutableStateOf(setOf<String>()) }

    val categories = listOf("All", "Qwen", "Llama", "Gemma", "Thinking")

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Search Hugging Face GGUF / LiteRT models...") },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = {
                        searchText = ""
                        onSearchHubModels("")
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear hub search", modifier = Modifier.size(18.dp))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchHubModels(searchText) }),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("hub_search_input")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { cat ->
                val selected = selectedCategory == cat
                Surface(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedCategory = cat
                        val query = if (cat == "All") "" else cat
                        searchText = query
                        onSearchHubModels(query)
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else semantic.floatingSurface,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = cat,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (hubState.isSearching) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "Querying Hugging Face Hub...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        hubState.errorMessage?.let { err ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        val activeTasks = downloads.values.filter { it.status != DownloadStatus.COMPLETED }
        if (activeTasks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Active Transfers",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                activeTasks.forEach { task ->
                    DownloadProgressCard(
                        task = task,
                        onPause = { onPauseDownload(task.taskId) },
                        onResume = { onResumeDownload(task.taskId) },
                        onCancel = { onCancelDownload(task.taskId) }
                    )
                }
            }
        }

        // Recommended for this device: curated starters filtered by RAM fit, smallest first
        val availBytes = memoryStats?.availableDeviceRamBytes ?: 0L
        val recommended = hubState.repos.take(5)
        if (availBytes > 0L && recommended.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = LocalMindIcons.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Recommended for this device",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "Sized against ${(availBytes / (1024.0 * 1024.0 * 1024.0)).let { "%.1f GB".format(it) }} currently free RAM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    WrapFlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalSpacing = 8.dp,
                        verticalSpacing = 8.dp
                    ) {
                        recommended.forEach { repo ->
                            Surface(
                                onClick = {
                                    expandedRepoIds = expandedRepoIds + repo.repoId
                                    if (hubState.repoFiles[repo.repoId].isNullOrEmpty()) onLoadRepoFiles(repo.repoId)
                                },
                                shape = RoundedCornerShape(20.dp),
                                color = semantic.floatingSurface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text(
                                    text = repo.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        hubState.repos.forEachIndexed { index, repo ->
            val isExpanded = repo.repoId in expandedRepoIds
            val files = hubState.repoFiles[repo.repoId].orEmpty()
            val isLoadingFiles = repo.repoId in hubState.loadingRepoIds

            RepoItemCard(
                repo = repo,
                isExpanded = isExpanded,
                isLoadingFiles = isLoadingFiles,
                files = files,
                downloads = downloads,
                memoryStats = memoryStats,
                onToggleExpand = {
                    expandedRepoIds = if (isExpanded) expandedRepoIds - repo.repoId else {
                        if (files.isEmpty()) onLoadRepoFiles(repo.repoId)
                        expandedRepoIds + repo.repoId
                    }
                },
                onDownloadFile = onDownloadFile,
                onPauseDownload = onPauseDownload,
                onCancelDownload = onCancelDownload
            )

            if (index < hubState.repos.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun RepoItemCard(
    repo: HfRepoSummary,
    isExpanded: Boolean,
    isLoadingFiles: Boolean,
    files: List<HfModelFile>,
    downloads: Map<String, ModelDownloadTask>,
    memoryStats: MemoryStats?,
    onToggleExpand: () -> Unit,
    onDownloadFile: (HfModelFile) -> Unit,
    onPauseDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberPrefersReducedMotion()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleExpand()
                }
                .padding(vertical = 4.dp)
                .testTag("hub_repo_${repo.repoId}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = repo.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${repo.author} • ${formatCompactCount(repo.downloads)} pulls • ${repo.tags.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (isExpanded) LocalMindIcons.ExpandLess else LocalMindIcons.ExpandMore,
                contentDescription = if (isExpanded) "Collapse files" else "Inspect files",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = if (reducedMotion) EnterTransition.None else (
                fadeIn(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong)) +
                    expandVertically(animationSpec = MotionTokens.snappySpring())
                ),
            exit = if (reducedMotion) ExitTransition.None else (
                fadeOut(tween(MotionTokens.DurationQuickMs)) +
                    shrinkVertically(animationSpec = MotionTokens.snappySpring())
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLoadingFiles) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Inspecting repository weights...", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (files.isEmpty()) {
                    Text(
                        text = "No downloadable .gguf / .litertlm weights found in main tree.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    files.forEach { file ->
                        HubFileRow(
                            file = file,
                            task = downloads[file.taskId],
                            availableRamBytes = memoryStats?.availableDeviceRamBytes ?: 0L,
                            onDownload = { onDownloadFile(file) },
                            onPause = { onPauseDownload(file.taskId) },
                            onCancel = { onCancelDownload(file.taskId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HubFileRow(
    file: HfModelFile,
    task: ModelDownloadTask?,
    availableRamBytes: Long,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    val fitsRam = availableRamBytes <= 0L || (file.sizeBytes * 1.25) <= (availableRamBytes * 0.85)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = file.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val ramText = if (fitsRam) "Fits RAM" else "Tight RAM"
                    Text(
                        text = "${file.format.label} · ${file.quantizationLabel} · ${formatHubBytes(file.sizeBytes)} · $ramText",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = if (fitsRam) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
                    )
                }

                when (task?.status) {
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(40.dp)) {
                            Icon(LocalMindIcons.Pause, contentDescription = "Pause download")
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel download")
                        }
                    }

                    DownloadStatus.PAUSED -> {
                        IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume download")
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel download")
                        }
                    }

                    DownloadStatus.COMPLETED -> {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = semantic.liveEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    else -> {
                        Surface(
                            onClick = onDownload,
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(LocalMindIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Get", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (task != null && (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED)) {
                LinearProgressIndicator(
                    progress = { task.fraction },
                    color = semantic.liveEmerald,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                val speedMb = task.bytesPerSec / (1024.0 * 1024.0)
                val statusLabel = if (task.status == DownloadStatus.PAUSED) "Paused" else String.format(Locale.US, "%.1f MB/s", speedMb)
                Text(
                    text = "${formatHubBytes(task.downloadedBytes)} / ${formatHubBytes(task.totalBytes)} · $statusLabel",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(
    task: ModelDownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    if (task.status == DownloadStatus.DOWNLOADING) {
                        IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                            Icon(LocalMindIcons.Pause, contentDescription = "Pause", modifier = Modifier.size(18.dp))
                        }
                    } else {
                        IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
                    }
                }
            }
            LinearProgressIndicator(
                progress = { task.fraction },
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Text(
                text = task.errorMessage ?: "${formatHubBytes(task.downloadedBytes)} / ${formatHubBytes(task.totalBytes)}",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = if (task.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private fun formatCompactCount(count: Long): String = when {
    count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000L -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else -> count.toString()
}

private fun formatHubBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
