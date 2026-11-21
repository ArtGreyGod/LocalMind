package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.SlashCommandItem
import com.example.data.storage.AppThemeMode
import com.example.data.storage.SystemPromptPresets
import com.example.domain.engine.HardwareAccelerator
import com.example.domain.engine.MemoryStats
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.tabular
import com.example.ui.viewmodel.InferenceTuningConfig
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryAndTuningSheet(
    memoryStats: MemoryStats?,
    livePrefillTokPerSec: Double,
    liveDecodeTokPerSec: Double,
    tuning: InferenceTuningConfig,
    themeMode: AppThemeMode,
    autoLoadLastModel: Boolean,
    keepScreenOnDuringInference: Boolean,
    onUpdateTuning: ((InferenceTuningConfig) -> InferenceTuningConfig) -> Unit,
    onUpdateThemeMode: (AppThemeMode) -> Unit,
    onSetAutoLoadLastModel: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    estimatedContextTokens: Int = 0,
    canTrimContext: Boolean = false,
    onTrimOlderContextTurns: () -> Unit = {},
    slashCommands: List<SlashCommandItem> = emptyList(),
    onAddCustomTemplate: (trigger: String, label: String, promptBody: String) -> Unit = { _, _, _ -> },
    onDeleteCustomTemplate: (id: String) -> Unit = {},
    semanticRetrievalEnabled: Boolean = false,
    embeddingModelId: String? = null,
    embeddingCandidates: List<com.example.domain.engine.LocalModelEntry> = emptyList(),
    onSetSemanticRetrieval: (Boolean) -> Unit = {},
    onSetEmbeddingModel: (String?) -> Unit = {},
    appLockEnabled: Boolean = false,
    onSetAppLock: (Boolean) -> Unit = {},
    nativeDiagnostics: String? = null,
    onRefreshDiagnostics: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semantic = LocalSemanticColors.current

    val cursorSwitchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.secondary,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        uncheckedBorderColor = Color.Transparent
    )

    val cursorSliderColors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Cursor-inspired Customize Top Header (X | Customize | Done)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FloatingCircleButton(
                    onClick = onDismiss,
                    icon = Icons.Rounded.Close,
                    contentDescription = "Close customize sheet",
                    iconSize = 20.dp
                )

                Text(
                    text = "Customize",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                FloatingPillButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("customize_done_button")
                ) {
                    Text(
                        text = "Done",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // Runtime Telemetry Section
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionCaption(text = "Runtime")
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val activeBackend = memoryStats?.activeBackend ?: "Idle"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = LocalMindIcons.Memory,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Active Runtime",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = activeBackend,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (memoryStats?.activeAccelerator != null) {
                                        semantic.liveEmerald
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }

                            if (memoryStats != null) {
                                val totalMb = (memoryStats.totalDeviceRamBytes / (1024 * 1024)).coerceAtLeast(1L)
                                val availMb = memoryStats.availableDeviceRamBytes / (1024 * 1024)
                                val usedMb = (totalMb - availMb).coerceAtLeast(0L)
                                val ramFraction = (usedMb.toFloat() / totalMb.toFloat()).coerceIn(0f, 1f)

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "System RAM",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "$usedMb / $totalMb MB (${availMb} MB free)",
                                            style = MaterialTheme.typography.labelSmall.tabular(),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { ramFraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = if (memoryStats.isLowMemory) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            semantic.liveEmerald
                                        },
                                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                }

                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )

                                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                    if (maxWidth >= 320.dp) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            MetricTile(
                                                label = "HEAP",
                                                value = "${memoryStats.nativeHeapAllocatedBytes / (1024 * 1024)} MB",
                                                modifier = Modifier.weight(1f)
                                            )
                                            MetricTile(
                                                label = "KV CACHE",
                                                value = "${memoryStats.kvCacheUsedTokens}/${memoryStats.kvCacheMaxTokens}",
                                                modifier = Modifier.weight(1f)
                                            )
                                            MetricTile(
                                                label = "PREFILL",
                                                value = "${"%.1f".format(livePrefillTokPerSec)} t/s",
                                                modifier = Modifier.weight(1f)
                                            )
                                            MetricTile(
                                                label = "DECODE",
                                                value = "${"%.1f".format(liveDecodeTokPerSec)} t/s",
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                MetricTile(
                                                    label = "HEAP",
                                                    value = "${memoryStats.nativeHeapAllocatedBytes / (1024 * 1024)} MB",
                                                    modifier = Modifier.weight(1f)
                                                )
                                                MetricTile(
                                                    label = "KV CACHE",
                                                    value = "${memoryStats.kvCacheUsedTokens}/${memoryStats.kvCacheMaxTokens}",
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                MetricTile(
                                                    label = "PREFILL",
                                                    value = "${"%.1f".format(livePrefillTokPerSec)} t/s",
                                                    modifier = Modifier.weight(1f)
                                                )
                                                MetricTile(
                                                    label = "DECODE",
                                                    value = "${"%.1f".format(liveDecodeTokPerSec)} t/s",
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (canTrimContext || estimatedContextTokens > 0) {
                        FloatingPillButton(
                            onClick = onTrimOlderContextTurns,
                            enabled = canTrimContext,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trim_context_button")
                        ) {
                            Icon(
                                imageVector = LocalMindIcons.ContentCut,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Trim Older Turns from Active Context (~$estimatedContextTokens tok)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // Reasoning Section (Cursor Customize row aesthetic)
                Column {
                    SectionCaption(text = "Reasoning")
                    Spacer(modifier = Modifier.height(6.dp))
                    CustomizeSwitchRow(
                        icon = LocalMindIcons.Psychology,
                        title = "Thinking Mode",
                        subtitle = "Step-by-step thought stream before final response",
                        checked = tuning.enableThinking,
                        onCheckedChange = { enabled ->
                            onUpdateTuning { it.copy(enableThinking = enabled) }
                        },
                        switchColors = cursorSwitchColors,
                        switchTag = "thinking_switch"
                    )
                    Hairline()
                    AnimatedVisibility(
                        visible = tuning.enableThinking,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            SliderHeaderRow(
                                title = "Thinking Budget",
                                value = "${tuning.thinkingBudgetTokens} tok"
                            )
                            Slider(
                                value = tuning.thinkingBudgetTokens.toFloat(),
                                onValueChange = { v ->
                                    // Snap to the 256-tok grid; no `steps` ticks, so every
                                    // multiple of 256 (incl. 2048) stays reachable.
                                    val snapped = ((v / 256f).roundToInt() * 256).coerceIn(256, 4096)
                                    onUpdateTuning { it.copy(thinkingBudgetTokens = snapped) }
                                },
                                valueRange = 256f..4096f,
                                colors = cursorSliderColors,
                                modifier = Modifier.testTag("thinking_budget_slider")
                            )
                            Hairline()
                        }
                    }
                }

                // Appearance & Behavior Section
                Column {
                    SectionCaption(text = "Appearance & Behavior")
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = LocalMindIcons.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Theme",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    WrapFlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalSpacing = 8.dp,
                        verticalSpacing = 8.dp
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            SelectablePill(
                                label = mode.label,
                                selected = themeMode == mode,
                                onClick = { onUpdateThemeMode(mode) },
                                modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}")
                            )
                        }
                    }
                    Hairline()
                    CustomizeSwitchRow(
                        icon = LocalMindIcons.Bedtime,
                        title = "Keep Screen Awake",
                        subtitle = "Prevent sleep while generating tokens",
                        checked = keepScreenOnDuringInference,
                        onCheckedChange = onSetKeepScreenOn,
                        switchColors = cursorSwitchColors,
                        switchTag = "keep_screen_on_switch"
                    )
                    Hairline()
                    CustomizeSwitchRow(
                        icon = LocalMindIcons.AutoAwesome,
                        title = "Auto-Load Last Model",
                        subtitle = "Restore previous weights on launch if RAM allows",
                        checked = autoLoadLastModel,
                        onCheckedChange = onSetAutoLoadLastModel,
                        switchColors = cursorSwitchColors,
                        switchTag = "autoload_model_switch"
                    )
                    Hairline()
                }

                // System Prompt Section
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionCaption(text = "System Prompt")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SystemPromptPresets.all.forEach { preset ->
                            SelectablePill(
                                label = preset.label,
                                selected = tuning.systemPrompt == preset.prompt,
                                onClick = { onUpdateTuning { it.copy(systemPrompt = preset.prompt) } },
                                modifier = Modifier.testTag("preset_chip_${preset.id}")
                            )
                        }
                    }
                    OutlinedTextField(
                        value = tuning.systemPrompt,
                        onValueChange = { next -> onUpdateTuning { it.copy(systemPrompt = next) } },
                        placeholder = { Text("System instruction...") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("system_prompt_input")
                    )
                }

                // Slash Prompt Templates Section
                SlashTemplatesCustomizeSection(
                    templates = slashCommands.filterNot { it.isActionCommand },
                    onAddTemplate = onAddCustomTemplate,
                    onDeleteTemplate = onDeleteCustomTemplate
                )

                // Privacy, Retrieval & Diagnostics Section
                PrivacyRetrievalSection(
                    semanticRetrievalEnabled = semanticRetrievalEnabled,
                    embeddingModelId = embeddingModelId,
                    embeddingCandidates = embeddingCandidates,
                    onSetSemanticRetrieval = onSetSemanticRetrieval,
                    onSetEmbeddingModel = onSetEmbeddingModel,
                    appLockEnabled = appLockEnabled,
                    onSetAppLock = onSetAppLock,
                    nativeDiagnostics = nativeDiagnostics,
                    onRefreshDiagnostics = onRefreshDiagnostics
                )

                // Hardware & Sampling Section
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = LocalMindIcons.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        SectionCaption(text = "Hardware & Sampling")
                    }

                    WrapFlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalSpacing = 8.dp,
                        verticalSpacing = 8.dp
                    ) {
                        HardwareAccelerator.entries.forEach { acc ->
                            SelectablePill(
                                label = when (acc) {
                                    HardwareAccelerator.NPU -> "NPU -> GPU -> CPU"
                                    HardwareAccelerator.GPU -> "GPU -> CPU"
                                    HardwareAccelerator.CPU -> "CPU Only"
                                },
                                selected = tuning.preferredAccelerator == acc,
                                onClick = { onUpdateTuning { it.copy(preferredAccelerator = acc) } },
                                modifier = Modifier.testTag("accelerator_chip_${acc.name.lowercase()}")
                            )
                        }
                    }

                    Hairline()

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SliderHeaderRow(
                            title = "Context Window (n_ctx)",
                            value = "${tuning.contextLength} tok"
                        )
                        WrapFlowRow(
                            horizontalSpacing = 8.dp,
                            verticalSpacing = 8.dp
                        ) {
                            listOf(2048, 4096, 8192).forEach { ctx ->
                                SelectablePill(
                                    label = "$ctx",
                                    selected = tuning.contextLength == ctx,
                                    onClick = { onUpdateTuning { it.copy(contextLength = ctx) } },
                                    modifier = Modifier.testTag("ctx_chip_$ctx")
                                )
                            }
                        }
                    }

                    Hairline()

                    Column {
                        SliderHeaderRow(
                            title = "Max Output Tokens",
                            value = "${tuning.maxTokens}"
                        )
                        Slider(
                            value = tuning.maxTokens.toFloat(),
                            onValueChange = { v ->
                                val snapped = ((v / 128f).roundToInt() * 128).coerceIn(128, 4096)
                                onUpdateTuning { it.copy(maxTokens = snapped) }
                            },
                            valueRange = 128f..4096f,
                            colors = cursorSliderColors,
                            modifier = Modifier.testTag("max_tokens_slider")
                        )
                    }

                    Column {
                        SliderHeaderRow(
                            title = "CPU Threads (n_threads)",
                            value = "${tuning.threads}"
                        )
                        Slider(
                            value = tuning.threads.toFloat(),
                            onValueChange = { v -> onUpdateTuning { it.copy(threads = v.roundToInt()) } },
                            valueRange = 1f..8f,
                            // No `steps`: evenly-spaced ticks would make values like 4 unreachable
                            colors = cursorSliderColors,
                            modifier = Modifier.testTag("threads_slider")
                        )
                    }

                    Column {
                        SliderHeaderRow(
                            title = "GGUF GPU Layers",
                            value = "${tuning.gpuLayers}"
                        )
                        Slider(
                            value = tuning.gpuLayers.toFloat(),
                            onValueChange = { v -> onUpdateTuning { it.copy(gpuLayers = v.roundToInt()) } },
                            valueRange = 0f..40f,
                            // No `steps`: keeps 10/20/30 and all other integer values reachable
                            colors = cursorSliderColors,
                            modifier = Modifier.testTag("gpu_layers_slider")
                        )
                    }

                    Column {
                        SliderHeaderRow(
                            title = "Temperature",
                            value = "%.2f".format(tuning.temperature)
                        )
                        Slider(
                            value = tuning.temperature,
                            onValueChange = { v -> onUpdateTuning { it.copy(temperature = v) } },
                            valueRange = 0.0f..1.5f,
                            colors = cursorSliderColors,
                            modifier = Modifier.testTag("temperature_slider")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            SliderHeaderRow(
                                title = "Top-P",
                                value = "%.2f".format(tuning.topP)
                            )
                            Slider(
                                value = tuning.topP,
                                onValueChange = { v -> onUpdateTuning { it.copy(topP = v) } },
                                valueRange = 0.1f..1.0f,
                                colors = cursorSliderColors,
                                modifier = Modifier.testTag("top_p_slider")
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            SliderHeaderRow(
                                title = "Top-K",
                                value = "${tuning.topK}"
                            )
                            Slider(
                                value = tuning.topK.toFloat(),
                                onValueChange = { v -> onUpdateTuning { it.copy(topK = v.roundToInt().coerceIn(1, 100)) } },
                                valueRange = 1f..100f,
                                colors = cursorSliderColors,
                                modifier = Modifier.testTag("top_k_slider")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Hairline() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun CustomizeSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchColors: androidx.compose.material3.SwitchColors,
    switchTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = switchColors,
            modifier = Modifier.testTag(switchTag)
        )
    }
}

@Composable
private fun SelectablePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = modifier.minimumInteractiveComponentSize()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun SliderHeaderRow(
    title: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SlashTemplatesCustomizeSection(
    templates: List<SlashCommandItem>,
    onAddTemplate: (trigger: String, label: String, promptBody: String) -> Unit,
    onDeleteTemplate: (String) -> Unit
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var triggerInput by rememberSaveable { mutableStateOf("") }
    var labelInput by rememberSaveable { mutableStateOf("") }
    var bodyInput by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionCaption(text = "Slash Templates (/)")
            FloatingPillButton(
                onClick = { adding = !adding },
                modifier = Modifier.testTag("toggle_add_template_button")
            ) {
                Icon(
                    imageVector = if (adding) Icons.Rounded.Close else Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (adding) "Cancel" else "New Template",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        AnimatedVisibility(visible = adding) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = triggerInput,
                        onValueChange = { triggerInput = it },
                        singleLine = true,
                        label = { Text("Trigger (e.g. translate)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        singleLine = true,
                        label = { Text("Display Label") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bodyInput,
                        onValueChange = { bodyInput = it },
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("Prompt Template") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onAddTemplate(triggerInput, labelInput, bodyInput)
                            triggerInput = ""
                            labelInput = ""
                            bodyInput = ""
                            adding = false
                        },
                        enabled = triggerInput.isNotBlank() && bodyInput.isNotBlank(),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save Template")
                    }
                }
            }
        }

        templates.forEach { tpl ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = "/${tpl.trigger}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = tpl.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!tpl.isBuiltIn) {
                    IconButton(
                        onClick = { onDeleteTemplate(tpl.id) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = LocalMindIcons.DeleteOutline,
                            contentDescription = "Delete template",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Hairline()
        }
    }
}

@Composable
private fun PrivacyRetrievalSection(
    semanticRetrievalEnabled: Boolean,
    embeddingModelId: String?,
    embeddingCandidates: List<com.example.domain.engine.LocalModelEntry>,
    onSetSemanticRetrieval: (Boolean) -> Unit,
    onSetEmbeddingModel: (String?) -> Unit,
    appLockEnabled: Boolean,
    onSetAppLock: (Boolean) -> Unit,
    nativeDiagnostics: String?,
    onRefreshDiagnostics: () -> Unit
) {
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.secondary,
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        uncheckedBorderColor = Color.Transparent
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCaption(text = "Privacy & Retrieval")
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                CustomizeSwitchRow(
                    icon = LocalMindIcons.Visibility,
                    title = "Biometric App Lock",
                    subtitle = "Require device unlock to open LocalMind",
                    checked = appLockEnabled,
                    onCheckedChange = onSetAppLock,
                    switchColors = switchColors,
                    switchTag = "app_lock_switch"
                )
                Hairline()
                CustomizeSwitchRow(
                    icon = LocalMindIcons.Psychology,
                    title = "Semantic Retrieval",
                    subtitle = "Rerank document search results with an embedding model",
                    checked = semanticRetrievalEnabled,
                    onCheckedChange = onSetSemanticRetrieval,
                    switchColors = switchColors,
                    switchTag = "semantic_retrieval_switch"
                )
                AnimatedVisibility(visible = semanticRetrievalEnabled) {
                    Column(
                        modifier = Modifier.padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Embedding model (BERT-family GGUF):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (embeddingCandidates.isEmpty()) {
                            Text(
                                text = "No candidates found. Import a small embedding GGUF via the Model Vault first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            WrapFlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalSpacing = 8.dp,
                                verticalSpacing = 8.dp
                            ) {
                                embeddingCandidates.forEach { candidate ->
                                    SelectablePill(
                                        label = candidate.displayName,
                                        selected = embeddingModelId == candidate.id,
                                        onClick = { onSetEmbeddingModel(candidate.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionCaption(text = "Diagnostics")
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Recent native engine warnings/errors",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SelectablePill(
                        label = "Refresh",
                        selected = false,
                        onClick = onRefreshDiagnostics
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = nativeDiagnostics ?: "Tap refresh to pull the last 200 native log lines.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = com.example.ui.theme.JetBrainsMonoFontFamily
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
