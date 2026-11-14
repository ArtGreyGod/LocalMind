package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.DocumentCitationUi
import com.example.data.local.IndexedDocumentEntity
import com.example.data.local.SlashCommandItem
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MotionTokens
import com.example.ui.theme.pressScale
import com.example.ui.theme.rememberPrefersReducedMotion
import com.example.ui.theme.tabular

@Composable
fun AttachedDocumentsStrip(
    documents: List<IndexedDocumentEntity>,
    isIndexing: Boolean,
    onRemoveDocument: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isIndexing) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = LocalSemanticColors.current.floatingSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Indexing document...",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        documents.forEach { doc ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = LocalSemanticColors.current.floatingSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.testTag("attached_doc_${doc.id}")
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "${doc.displayName} (${doc.chunkCount})",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = { onRemoveDocument(doc.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Detach ${doc.displayName}",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SlashCommandPopover(
    commands: List<SlashCommandItem>,
    onSelectCommand: (SlashCommandItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = semantic.floatingSurface,
        shadowElevation = if (semantic.isDark) 6.dp else 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .testTag("slash_command_popover")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            commands.forEachIndexed { idx, cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clickable { onSelectCommand(cmd) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("slash_item_${cmd.trigger}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (cmd.isActionCommand) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    ) {
                        Text(
                            text = "/${cmd.trigger}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (cmd.isActionCommand) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = cmd.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (cmd.description.isNotBlank()) {
                            Text(
                                text = cmd.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (idx < commands.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
fun VoiceListeningBanner(
    partialTranscript: String,
    onStopDictation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val reducedMotion = rememberPrefersReducedMotion()
    val alpha = if (!reducedMotion) {
        val transition = rememberInfiniteTransition(label = "voicePulse")
        val anim by transition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = MotionTokens.EaseInOutStrong),
                repeatMode = RepeatMode.Reverse
            ),
            label = "voiceAlpha"
        )
        anim
    } else 1.0f

    Surface(
        onClick = onStopDictation,
        shape = RoundedCornerShape(18.dp),
        color = semantic.floatingSurface,
        border = BorderStroke(1.dp, semantic.liveEmerald.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("voice_listening_banner")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = semantic.liveEmerald,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { this.alpha = alpha }
            )
            Text(
                text = partialTranscript.ifBlank { "Listening offline... tap to stop" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Stop",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun ContextWindowGaugePill(
    usedTokens: Int,
    maxTokens: Int,
    canTrim: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val safeMax = maxTokens.coerceAtLeast(1)
    val fraction = (usedTokens.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
    val pct = (fraction * 100f).toInt()
    val dotColor = when {
        fraction >= 0.90f -> MaterialTheme.colorScheme.error
        fraction >= 0.75f -> MaterialTheme.colorScheme.tertiary
        else -> semantic.liveEmerald
    }

    Surface(
        onClick = onClick,
        enabled = canTrim || fraction >= 0.75f,
        shape = RoundedCornerShape(18.dp),
        color = semantic.floatingSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (fraction >= 0.75f) dotColor.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
        ),
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .testTag("context_window_gauge_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = "CTX $pct% ($usedTokens/$safeMax)",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (canTrim && fraction >= 0.75f) {
                Text(
                    text = "• Trim",
                    style = MaterialTheme.typography.labelSmall,
                    color = dotColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun BranchSwitcherPill(
    revisionIndex: Int,
    siblingIds: List<String>,
    onSelectBranch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (siblingIds.size <= 1) return
    val currentIdx = revisionIndex.coerceIn(0, siblingIds.lastIndex)
    val hasPrev = currentIdx > 0
    val hasNext = currentIdx < siblingIds.lastIndex

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.testTag("branch_switcher_pill")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = { if (hasPrev) onSelectBranch(siblingIds[currentIdx - 1]) },
                enabled = hasPrev,
                modifier = Modifier
                    .size(36.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("prev_branch_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.btn_prev_branch),
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = "${currentIdx + 1} / ${siblingIds.size}",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(
                onClick = { if (hasNext) onSelectBranch(siblingIds[currentIdx + 1]) },
                enabled = hasNext,
                modifier = Modifier
                    .size(36.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("next_branch_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.btn_next_branch),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun CitationsChipRow(
    citations: List<DocumentCitationUi>,
    onInspectCitation: (DocumentCitationUi) -> Unit,
    modifier: Modifier = Modifier
) {
    if (citations.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        citations.forEachIndexed { idx, cit ->
            Surface(
                onClick = { onInspectCitation(cit) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.testTag("citation_chip_$idx")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "${cit.documentName} · ${cit.sectionLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun PendingAttachmentCard(
    imageUri: String?,
    resolutionLabel: String?,
    isPreparing: Boolean,
    visionWarning: Boolean,
    onPickVisionPrompt: (String) -> Unit,
    onClear: () -> Unit
) {
    val clearInteraction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LocalSemanticColors.current.floatingSurface,
        shadowElevation = if (LocalSemanticColors.current.isDark) 2.dp else 5.dp,
        border = BorderStroke(
            1.dp,
            if (visionWarning) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Resizing & normalizing bitmap...", style = MaterialTheme.typography.bodySmall)
                } else {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "Attached image preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = resolutionLabel ?: "Prepared Image",
                                style = MaterialTheme.typography.labelMedium.tabular()
                            )
                        }
                        if (visionWarning) {
                            Text(
                                text = "Active model has no mmproj/vision graph paired",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    IconButton(
                        onClick = onClear,
                        interactionSource = clearInteraction,
                        modifier = Modifier
                            .size(48.dp)
                            .pressScale(clearInteraction, pressedScale = 0.92f)
                            .testTag("clear_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove attached image"
                        )
                    }
                }
            }

            if (!isPreparing) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "Describe this image in detail.",
                        "Extract all visible text verbatim.",
                        "Explain what is happening here."
                    ).forEach { quick ->
                        Surface(
                            onClick = { onPickVisionPrompt(quick) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = quick,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
