package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.storage.AppThemeMode
import com.example.ui.components.TelemetryAndTuningSheet
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ModelManagerScreen
import com.example.ui.screens.ThreadsHubScreen
import com.example.ui.theme.LocalMindTheme
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MotionTokens
import com.example.ui.theme.rememberPrefersReducedMotion
import com.example.ui.viewmodel.LocalMindViewModel
import kotlinx.serialization.Serializable

@Serializable
object WorkbenchRoute

@Serializable
object ThreadsRoute

@Serializable
object VaultRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: LocalMindViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val useDarkTheme = when (uiState.themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            LocalMindTheme(darkTheme = useDarkTheme) {
                LocalMindRootScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun LocalMindRootScreen(
    viewModel: LocalMindViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val registeredModels by viewModel.registeredModels.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModelEntry.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val threads by viewModel.threads.collectAsStateWithLifecycle()
    val attachedDocuments by viewModel.attachedDocuments.collectAsStateWithLifecycle()
    val slashCommands by viewModel.slashCommands.collectAsStateWithLifecycle()
    val hubState by viewModel.hubState.collectAsStateWithLifecycle()
    val hubDownloads by viewModel.hubDownloads.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val speakingMessageId by viewModel.speakingMessageId.collectAsStateWithLifecycle()

    val reducedMotion = rememberPrefersReducedMotion()
    val semantic = LocalSemanticColors.current

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isWorkbenchSelected = currentDest?.hasRoute<WorkbenchRoute>() ?: true
    val isThreadsSelected = currentDest?.hasRoute<ThreadsRoute>() ?: false
    val isVaultSelected = currentDest?.hasRoute<VaultRoute>() ?: false

    var showTelemetrySheet by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusBanner) {
        val message = uiState.statusBanner
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearStatusBanner()
        }
    }

    if (showTelemetrySheet) {
        TelemetryAndTuningSheet(
            memoryStats = uiState.memoryStats,
            livePrefillTokPerSec = uiState.livePrefillTokensPerSec,
            liveDecodeTokPerSec = uiState.liveDecodeTokensPerSec,
            tuning = uiState.tuning,
            themeMode = uiState.themeMode,
            autoLoadLastModel = uiState.autoLoadLastModel,
            keepScreenOnDuringInference = uiState.keepScreenOnDuringInference,
            onUpdateTuning = viewModel::updateTuning,
            onUpdateThemeMode = viewModel::updateThemeMode,
            onSetAutoLoadLastModel = viewModel::setAutoLoadLastModel,
            onSetKeepScreenOn = viewModel::setKeepScreenOn,
            onDismiss = { showTelemetrySheet = false },
            estimatedContextTokens = uiState.estimatedContextTokens,
            canTrimContext = uiState.messages.count { !it.isOutsideContextWindow } > 2,
            onTrimOlderContextTurns = viewModel::trimOlderContextTurns,
            slashCommands = slashCommands,
            onAddCustomTemplate = viewModel::addCustomTemplate,
            onDeleteCustomTemplate = viewModel::deleteCustomTemplate
        )
    }

    val navigateToRoute: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val railItemColors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 84.dp)
                ) { data ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = semantic.floatingSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        tonalElevation = 0.dp,
                        shadowElevation = if (semantic.isDark) 4.dp else 8.dp,
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .fillMaxWidth()
                    ) {
                        Snackbar(
                            snackbarData = data,
                            containerColor = semantic.floatingSurface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            actionColor = MaterialTheme.colorScheme.secondary,
                            dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NavigationRailItem(
                            selected = isWorkbenchSelected,
                            onClick = { navigateToRoute(WorkbenchRoute) },
                            icon = {
                                Icon(
                                    imageVector = if (isWorkbenchSelected) Icons.Rounded.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.nav_chat),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = railItemColors,
                            modifier = Modifier.testTag("rail_workbench")
                        )
                        NavigationRailItem(
                            selected = isThreadsSelected,
                            onClick = { navigateToRoute(ThreadsRoute) },
                            icon = {
                                Icon(
                                    imageVector = if (isThreadsSelected) Icons.Rounded.Forum else Icons.Outlined.Forum,
                                    contentDescription = null
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.nav_threads),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = railItemColors,
                            modifier = Modifier.testTag("rail_threads")
                        )
                        NavigationRailItem(
                            selected = isVaultSelected,
                            onClick = { navigateToRoute(VaultRoute) },
                            icon = {
                                Icon(
                                    imageVector = if (isVaultSelected) Icons.Rounded.Storage else Icons.Outlined.Storage,
                                    contentDescription = null
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.nav_models),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = railItemColors,
                            modifier = Modifier.testTag("rail_vault")
                        )
                    }
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = WorkbenchRoute,
                        enterTransition = {
                            if (reducedMotion) EnterTransition.None
                            else fadeIn(tween(MotionTokens.DurationQuickMs, easing = MotionTokens.EaseOutStrong))
                        },
                        exitTransition = {
                            if (reducedMotion) ExitTransition.None
                            else fadeOut(tween(110, easing = MotionTokens.EaseOutStrong))
                        }
                    ) {
                        composable<WorkbenchRoute> {
                            ChatScreen(
                                uiState = uiState,
                                engineState = engineState,
                                activeModel = activeModel,
                                onSendPrompt = viewModel::sendPrompt,
                                onStopGeneration = viewModel::stopGeneration,
                                onResetConversation = viewModel::resetConversation,
                                onRegenerateLastResponse = viewModel::regenerateLastResponse,
                                onEditLastUserMessage = viewModel::editLastUserMessageAndResend,
                                onDeleteMessage = viewModel::deleteMessage,
                                onBuildMarkdownTranscript = viewModel::buildMarkdownTranscript,
                                onShowStatusBanner = viewModel::showStatusBanner,
                                onAttachImageUri = viewModel::attachImageUri,
                                onClearPendingImage = viewModel::clearPendingImage,
                                onToggleThinking = viewModel::toggleThinking,
                                onOpenModelVault = { navigateToRoute(VaultRoute) },
                                onOpenTelemetrySheet = { showTelemetrySheet = true },
                                attachedDocuments = attachedDocuments,
                                slashCommands = slashCommands,
                                isListening = isListening,
                                partialTranscript = partialTranscript,
                                speakingMessageId = speakingMessageId,
                                onAttachDocumentUri = { uri -> viewModel.attachDocumentUri(uri) },
                                onRemoveDocument = viewModel::removeDocument,
                                onExecuteSlashAction = viewModel::executeSlashAction,
                                onSelectBranch = viewModel::selectMessageBranch,
                                onTrimOlderContextTurns = viewModel::trimOlderContextTurns,
                                onStartVoiceDictation = viewModel::startVoiceDictation,
                                onStopVoiceDictation = viewModel::stopVoiceDictation,
                                onToggleReadAloud = viewModel::toggleReadAloud,
                                onOpenThreadsHub = { navigateToRoute(ThreadsRoute) },
                                onDismissMemoryWarning = viewModel::dismissMemoryPressureWarning,
                                onTrimContextFromMemoryWarning = viewModel::trimContextFromMemoryWarning,
                                onUnloadFromMemoryWarning = viewModel::unloadFromMemoryWarning
                            )
                        }

                        composable<ThreadsRoute> {
                            ThreadsHubScreen(
                                threads = threads,
                                workspaces = workspaces,
                                activeThreadId = uiState.activeThreadId,
                                activeWorkspaceId = uiState.activeWorkspaceId,
                                onSelectThread = viewModel::selectThread,
                                onCreateThread = viewModel::createNewThread,
                                onRenameThread = viewModel::renameThread,
                                onTogglePinThread = viewModel::togglePinThread,
                                onToggleArchiveThread = viewModel::toggleArchiveThread,
                                onDeleteThread = viewModel::deleteThread,
                                onSelectWorkspace = viewModel::selectWorkspace,
                                onCreateWorkspace = viewModel::createWorkspace,
                                onDeleteWorkspace = viewModel::deleteWorkspace,
                                onOpenModelVault = { navigateToRoute(VaultRoute) },
                                onBackToWorkbench = { navigateToRoute(WorkbenchRoute) }
                            )
                        }

                        composable<VaultRoute> {
                            ModelManagerScreen(
                                models = registeredModels,
                                activeModelId = activeModel?.id,
                                engineState = engineState,
                                memoryStats = uiState.memoryStats,
                                importProgress = importProgress,
                                pendingPreflightWarning = uiState.pendingPreflightWarning,
                                onImportModelUri = viewModel::importModel,
                                onPairMmprojUri = viewModel::pairMmproj,
                                onLoadModel = { entry, bypass ->
                                    viewModel.requestLoadModel(entry, bypass)
                                },
                                onUnloadModel = viewModel::unloadActiveModel,
                                onDeleteModel = viewModel::deleteModel,
                                onDismissPreflightWarning = viewModel::dismissPreflightWarning,
                                onBackToWorkbench = { navigateToRoute(WorkbenchRoute) },
                                hubState = hubState,
                                hubDownloads = hubDownloads,
                                onSearchHubModels = viewModel::searchHubModels,
                                onLoadHubRepoFiles = viewModel::loadHubRepoFiles,
                                onDownloadHubFile = viewModel::downloadHubFile,
                                onPauseHubDownload = viewModel::pauseHubDownload,
                                onCancelHubDownload = viewModel::cancelHubDownload
                            )
                        }
                    }
                }
            }
        }
    }
}
