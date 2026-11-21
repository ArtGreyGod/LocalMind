package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.storage.AppThemeMode
import com.example.ui.components.LocalMindIcons
import com.example.ui.components.TelemetryAndTuningSheet
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ModelManagerScreen
import com.example.ui.screens.ThreadsHubScreen
import com.example.ui.theme.LocalMindTheme
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MotionTokens
import com.example.ui.theme.rememberPrefersReducedMotion
import com.example.ui.viewmodel.LocalMindViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
object WorkbenchRoute

@Serializable
object ThreadsRoute

@Serializable
object VaultRoute

private fun NavDestination?.spatialOrder(): Int = when {
    this?.hasRoute<ThreadsRoute>() == true -> -1
    this?.hasRoute<WorkbenchRoute>() == true -> 0
    this?.hasRoute<VaultRoute>() == true -> 1
    else -> 0
}

class MainActivity : FragmentActivity() {
    private var isUnlocked by mutableStateOf(false)
    private val pendingSharedText = MutableStateFlow<String?>(null)
    private val pendingNewChat = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeLaunchIntent(intent)
        setContent {
            val viewModel: LocalMindViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
            val useDarkTheme = when (uiState.themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            LocalMindTheme(darkTheme = useDarkTheme) {
                if (appLockEnabled && !isUnlocked) {
                    LockScreen(onUnlocked = { isUnlocked = true })
                } else {
                    LocalMindRootScreen(viewModel = viewModel)
                }
            }
            // Consume launch intents (share-sheet text, new-chat shortcut) once composition is live.
            LaunchedEffect(Unit) {
                pendingSharedText.collect { text ->
                    if (!text.isNullOrBlank()) {
                        viewModel.handleSharedText(text)
                        pendingSharedText.value = null
                    }
                }
            }
            LaunchedEffect(Unit) {
                pendingNewChat.collect { requested ->
                    if (requested) {
                        viewModel.createNewThread()
                        pendingNewChat.value = false
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    private fun consumeLaunchIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            ACTION_NEW_CHAT -> pendingNewChat.value = true
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    pendingSharedText.value = text
                }
            }
        }
    }

    fun authenticate(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val bm = BiometricManager.from(this)
        val canAuth = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("No screen lock or biometrics enrolled on this device")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_screen_title))
                .setSubtitle(getString(R.string.lock_screen_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
        )
    }

    companion object {
        const val ACTION_NEW_CHAT = "com.example.localmind.action.NEW_CHAT"
    }
}

@Composable
private fun LockScreen(
    onUnlocked: () -> Unit,
    onAuthError: (String) -> Unit = {}
) {
    // The parent activity performs the actual BiometricPrompt flow via [MainActivity].
    // Reaching here means appLock is enabled; prompt immediately on first composition.
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    var lastError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        activity?.authenticate(
            onSuccess = onUnlocked,
            onError = { onAuthError(it); lastError = it }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = LocalMindIcons.ChatBubble,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.lock_screen_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.lock_screen_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        lastError?.let { err ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = err,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            activity?.authenticate(
                onSuccess = onUnlocked,
                onError = { onAuthError(it); lastError = it }
            )
        }) {
            Text(stringResource(R.string.btn_unlock))
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
    val haptics = LocalHapticFeedback.current

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
            if (message == "Thread deleted" && viewModel.hasUndoableThreadDelete()) {
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoDeleteThread()
                }
            } else {
                snackbarHostState.showSnackbar(message)
            }
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
            onDeleteCustomTemplate = viewModel::deleteCustomTemplate,
            semanticRetrievalEnabled = viewModel.semanticRetrievalEnabled.collectAsStateWithLifecycle().value,
            embeddingModelId = viewModel.embeddingModelId.collectAsStateWithLifecycle().value,
            embeddingCandidates = registeredModels.filter { it.format.label.isNotBlank() },
            onSetSemanticRetrieval = viewModel::setSemanticRetrieval,
            onSetEmbeddingModel = viewModel::setEmbeddingModel,
            appLockEnabled = viewModel.appLockEnabled.collectAsStateWithLifecycle().value,
            onSetAppLock = viewModel::setAppLock,
            nativeDiagnostics = uiState.nativeDiagnostics,
            onRefreshDiagnostics = viewModel::loadNativeDiagnostics
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
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigateToRoute(WorkbenchRoute)
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isWorkbenchSelected) LocalMindIcons.ChatBubble else LocalMindIcons.ChatBubbleOutline,
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
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigateToRoute(ThreadsRoute)
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isThreadsSelected) LocalMindIcons.Forum else LocalMindIcons.ForumOutlined,
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
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navigateToRoute(VaultRoute)
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isVaultSelected) LocalMindIcons.Storage else LocalMindIcons.StorageOutlined,
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
                            if (reducedMotion) {
                                EnterTransition.None
                            } else {
                                val delta = targetState.destination.spatialOrder() - initialState.destination.spatialOrder()
                                val dir = if (delta >= 0) 1 else -1
                                slideInHorizontally(animationSpec = MotionTokens.pageSpring()) { fullWidth ->
                                    (fullWidth * 0.22f * dir).roundToInt()
                                } + fadeIn(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong))
                            }
                        },
                        exitTransition = {
                            if (reducedMotion) {
                                ExitTransition.None
                            } else {
                                val delta = targetState.destination.spatialOrder() - initialState.destination.spatialOrder()
                                val dir = if (delta >= 0) 1 else -1
                                slideOutHorizontally(animationSpec = MotionTokens.pageSpring()) { fullWidth ->
                                    (-fullWidth * 0.16f * dir).roundToInt()
                                } + fadeOut(tween(130, easing = MotionTokens.EaseOutStrong))
                            }
                        },
                        popEnterTransition = {
                            if (reducedMotion) {
                                EnterTransition.None
                            } else {
                                val delta = targetState.destination.spatialOrder() - initialState.destination.spatialOrder()
                                val dir = if (delta >= 0) 1 else -1
                                slideInHorizontally(animationSpec = MotionTokens.pageSpring()) { fullWidth ->
                                    (fullWidth * 0.22f * dir).roundToInt()
                                } + fadeIn(tween(MotionTokens.DurationStandardMs, easing = MotionTokens.EaseOutStrong))
                            }
                        },
                        popExitTransition = {
                            if (reducedMotion) {
                                ExitTransition.None
                            } else {
                                val delta = targetState.destination.spatialOrder() - initialState.destination.spatialOrder()
                                val dir = if (delta >= 0) 1 else -1
                                slideOutHorizontally(animationSpec = MotionTokens.pageSpring()) { fullWidth ->
                                    (-fullWidth * 0.16f * dir).roundToInt()
                                } + fadeOut(tween(130, easing = MotionTokens.EaseOutStrong))
                            }
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
                                onRemovePendingImage = viewModel::removePendingImage,
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
                                onUnloadFromMemoryWarning = viewModel::unloadFromMemoryWarning,
                                onSaveDraft = viewModel::saveDraft,
                                onRestoreDraft = viewModel::restoreDraft,
                                pendingSharedText = uiState.pendingSharedText,
                                onConsumeSharedText = viewModel::clearPendingSharedText
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
                                onBackToWorkbench = { navigateToRoute(WorkbenchRoute) },
                                onExportThread = viewModel::exportThreadJson,
                                onImportThread = viewModel::importThreadJson,
                                onSearchMessages = viewModel::searchThreadMessages
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
                                onResumeHubDownload = viewModel::resumeHubDownload,
                                onCancelHubDownload = viewModel::cancelHubDownload
                            )
                        }
                    }
                }
            }
        }
    }
}
