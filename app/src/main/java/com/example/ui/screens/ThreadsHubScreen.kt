package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ChatThreadEntity
import com.example.data.local.WorkspaceEntity
import com.example.ui.components.FloatingCircleButton
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.pressScale
import com.example.ui.theme.rememberPrefersReducedMotion
import com.example.ui.theme.tabular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HubTab(val label: String) {
    THREADS("Threads"),
    WORKSPACES("Workspaces")
}

@Composable
fun ThreadsHubScreen(
    threads: List<ChatThreadEntity>,
    workspaces: List<WorkspaceEntity>,
    activeThreadId: String?,
    activeWorkspaceId: String,
    onSelectThread: (String) -> Unit,
    onCreateThread: (workspaceId: String?, initialPrompt: String?) -> Unit,
    onRenameThread: (threadId: String, newTitle: String) -> Unit,
    onTogglePinThread: (String) -> Unit,
    onToggleArchiveThread: (String) -> Unit,
    onDeleteThread: (String) -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onCreateWorkspace: (name: String, systemPrompt: String) -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onOpenModelVault: () -> Unit,
    onBackToWorkbench: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val reducedMotion = rememberPrefersReducedMotion()

    var selectedTab by rememberSaveable { mutableStateOf(HubTab.THREADS) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var quickPrompt by rememberSaveable { mutableStateOf("") }

    var renameTarget by remember { mutableStateOf<ChatThreadEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var showNewWorkspaceDialog by rememberSaveable { mutableStateOf(false) }
    var workspaceNameInput by rememberSaveable { mutableStateOf("") }
    var workspacePromptInput by rememberSaveable { mutableStateOf("") }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Thread") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    placeholder = { Text("Thread title") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        renameTarget?.let { onRenameThread(it.id, renameInput) }
                        renameTarget = null
                    },
                    enabled = renameInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showNewWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = { showNewWorkspaceDialog = false },
            title = { Text("New Workspace") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = workspaceNameInput,
                        onValueChange = { workspaceNameInput = it },
                        singleLine = true,
                        label = { Text("Workspace Name") },
                        placeholder = { Text("e.g. Research Papers") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = workspacePromptInput,
                        onValueChange = { workspacePromptInput = it },
                        minLines = 3,
                        maxLines = 5,
                        label = { Text("System Instruction") },
                        placeholder = { Text("Tailored persona or rules for threads in this workspace...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCreateWorkspace(workspaceNameInput, workspacePromptInput)
                        workspaceNameInput = ""
                        workspacePromptInput = ""
                        showNewWorkspaceDialog = false
                        onBackToWorkbench()
                    },
                    enabled = workspaceNameInput.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewWorkspaceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val workspaceMap = remember(workspaces) { workspaces.associateBy { it.id } }

    val filteredThreads = remember(threads, showArchived, searchQuery) {
        val q = searchQuery.trim().lowercase()
        threads.filter { thread ->
            thread.isArchived == showArchived && (
                q.isEmpty() ||
                    thread.title.lowercase().contains(q) ||
                    (thread.lastModelName?.lowercase()?.contains(q) == true)
                )
        }
    }

    val pinnedThreads = remember(filteredThreads) { filteredThreads.filter { it.isPinned } }
    val recentThreads = remember(filteredThreads) { filteredThreads.filterNot { it.isPinned } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 104.dp,
                bottom = 148.dp
            )
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (selectedTab == HubTab.THREADS) "All Threads" else "Workspaces",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.testTag("threads_hub_title")
                        )
                        Text(
                            text = if (selectedTab == HubTab.THREADS) {
                                "${filteredThreads.size} ${if (showArchived) "archived" else "active"} conversations"
                            } else {
                                "${workspaces.size} local workspaces with scoped instructions"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (selectedTab == HubTab.THREADS) {
                        Surface(
                            onClick = { showArchived = !showArchived },
                            shape = RoundedCornerShape(20.dp),
                            color = if (showArchived) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                semantic.floatingSurface
                            },
                            contentColor = if (showArchived) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            border = BorderStroke(
                                1.dp,
                                if (showArchived) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("toggle_archived_filter")
                        ) {
                            Text(
                                text = if (showArchived) "Archived" else "Active",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                    exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search threads by title or model...") },
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

            if (selectedTab == HubTab.THREADS) {
                if (filteredThreads.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = if (showArchived) "No archived threads" else "No matching threads",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Start a new on-device conversation below or switch filter.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    if (pinnedThreads.isNotEmpty()) {
                        item {
                            Text(
                                text = "Pinned",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                            )
                        }
                        itemsIndexed(pinnedThreads, key = { _, t -> "pin_${t.id}" }) { index, thread ->
                            ThreadListRow(
                                thread = thread,
                                workspaceName = workspaceMap[thread.workspaceId]?.name,
                                isSelected = thread.id == activeThreadId,
                                onClick = {
                                    onSelectThread(thread.id)
                                    onBackToWorkbench()
                                },
                                onRename = {
                                    renameInput = thread.title
                                    renameTarget = thread
                                },
                                onTogglePin = { onTogglePinThread(thread.id) },
                                onToggleArchive = { onToggleArchiveThread(thread.id) },
                                onDelete = { onDeleteThread(thread.id) }
                            )
                            if (index < pinnedThreads.lastIndex || recentThreads.isNotEmpty()) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }

                    if (recentThreads.isNotEmpty()) {
                        item {
                            Text(
                                text = if (showArchived) "Archived" else "Recents",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                            )
                        }
                        itemsIndexed(recentThreads, key = { _, t -> t.id }) { index, thread ->
                            ThreadListRow(
                                thread = thread,
                                workspaceName = workspaceMap[thread.workspaceId]?.name,
                                isSelected = thread.id == activeThreadId,
                                onClick = {
                                    onSelectThread(thread.id)
                                    onBackToWorkbench()
                                },
                                onRename = {
                                    renameInput = thread.title
                                    renameTarget = thread
                                },
                                onTogglePin = { onTogglePinThread(thread.id) },
                                onToggleArchive = { onToggleArchiveThread(thread.id) },
                                onDelete = { onDeleteThread(thread.id) }
                            )
                            if (index < recentThreads.lastIndex) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            } else {
                // Workspaces view modeled after Cursor iOS Screenshot 2
                itemsIndexed(workspaces, key = { _, ws -> ws.id }) { _, workspace ->
                    val count = threads.count { it.workspaceId == workspace.id && !it.isArchived }
                    val isCurrent = workspace.id == activeWorkspaceId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable {
                                onSelectWorkspace(workspace.id)
                                onBackToWorkbench()
                            }
                            .padding(vertical = 15.dp)
                            .testTag("workspace_row_${workspace.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = if (workspace.isDefault) Icons.Rounded.Home else Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(21.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = workspace.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = workspace.systemPrompt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!workspace.isDefault) {
                            IconButton(
                                onClick = { onDeleteWorkspace(workspace.id) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete workspace",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                // Trailing "Add Workspace" row
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable { showNewWorkspaceDialog = true }
                            .padding(vertical = 16.dp)
                            .testTag("add_workspace_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp)
                        )
                        Text(
                            text = "Add Workspace",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }

        // Top Floating Header Bar (< Back | Threads / Workspaces toggle | Search & + New Thread)
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

                // Segmented Cursor Pill Switch (Threads | Workspaces)
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = semantic.floatingSurface,
                    shadowElevation = if (semantic.isDark) 2.dp else 5.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f))
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HubTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            Surface(
                                onClick = { selectedTab = tab },
                                shape = RoundedCornerShape(20.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("hub_tab_${tab.name.lowercase()}")
                            ) {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingCircleButton(
                        onClick = { searchOpen = !searchOpen },
                        icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = "Search threads",
                        iconSize = 20.dp
                    )
                    FloatingCircleButton(
                        onClick = {
                            onCreateThread(activeWorkspaceId, null)
                            onBackToWorkbench()
                        },
                        icon = Icons.Rounded.Add,
                        contentDescription = "New thread",
                        iconSize = 22.dp,
                        modifier = Modifier.testTag("new_thread_fab")
                    )
                }
            }
        }

        // Bottom Floating Capsule (Start a new thread straight from the hub)
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
            val sendInteraction = remember { MutableInteractionSource() }
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = semantic.floatingSurface,
                shadowElevation = if (semantic.isDark) 4.dp else 10.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)),
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .align(Alignment.Center)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = onOpenModelVault,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .size(44.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("hub_open_vault_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Storage,
                                contentDescription = stringResource(R.string.btn_open_vault),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (quickPrompt.isEmpty()) {
                            Text(
                                text = "Plan, ask, explore in new thread...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = quickPrompt,
                            onValueChange = { quickPrompt = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val canStart = quickPrompt.isNotBlank()
                    Surface(
                        onClick = {
                            val prompt = quickPrompt.trim()
                            quickPrompt = ""
                            onCreateThread(activeWorkspaceId, prompt)
                            onBackToWorkbench()
                        },
                        enabled = true,
                        interactionSource = sendInteraction,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(44.dp)
                            .minimumInteractiveComponentSize()
                            .pressScale(sendInteraction, pressedScale = 0.90f)
                            .testTag("hub_quick_send_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (canStart) Icons.Rounded.ArrowUpward else Icons.Rounded.Add,
                                contentDescription = "Start new thread",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadListRow(
    thread: ChatThreadEntity,
    workspaceName: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit
) {
    val semantic = LocalSemanticColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
            .testTag("thread_row_${thread.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) semantic.liveEmerald
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                )
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (thread.isPinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = thread.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val timeStr = remember(thread.updatedAtMillis) {
                SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(thread.updatedAtMillis))
            }
            val metaParts = listOfNotNull(
                workspaceName,
                thread.lastModelName,
                timeStr
            )
            Text(
                text = metaParts.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .size(44.dp)
                    .minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = "Thread actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (thread.isPinned) "Unpin" else "Pin to top") },
                    leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onTogglePin()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (thread.isArchived) "Unarchive" else "Archive") },
                    leadingIcon = {
                        Icon(
                            if (thread.isArchived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleArchive()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}
