package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MotionTokens
import com.example.ui.theme.pressScale
import com.example.ui.theme.rememberPrefersReducedMotion
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Floating circular icon button modeled after Cursor iOS's top bar controls (<, X, +, ...).
 * Guaranteed 48.dp touch target, crisp hairline border, and subtle floating elevation.
 */
@Composable
fun FloatingCircleButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = LocalSemanticColors.current.floatingSurface,
    iconSize: Dp = 20.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = CircleShape,
        color = containerColor,
        shadowElevation = if (LocalSemanticColors.current.isDark) 2.dp else 6.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
        ),
        modifier = modifier
            .size(48.dp)
            .minimumInteractiveComponentSize()
            .pressScale(interactionSource, enabled = enabled, pressedScale = 0.92f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.38f),
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Floating capsule pill modeled after Cursor iOS's floating action pills ("Archived", "View PR", "Mark Ready", "Save").
 * Guaranteed >= 48.dp interactive touch height when clickable.
 */
@Composable
fun FloatingPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = LocalSemanticColors.current.floatingSurface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
    shadowElevation: Dp = if (LocalSemanticColors.current.isDark) 2.dp else 5.dp,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = shadowElevation,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .minimumInteractiveComponentSize()
            .pressScale(interactionSource, enabled = enabled, pressedScale = 0.96f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

/**
 * Segmented capsule switcher with a gliding active indicator pill that interpolates width and X
 * position smoothly in the draw phase from either a pager's fractional offset or spring-physics state.
 */
@Composable
fun SegmentedPillSwitcher(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    testTags: List<String>,
    modifier: Modifier = Modifier,
    positionProvider: (() -> Float)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    shadowElevation: Dp = 0.dp,
    itemHorizontalPadding: Dp = 14.dp,
    itemVerticalPadding: Dp = 8.dp
) {
    if (labels.isEmpty()) return
    val reducedMotion = rememberPrefersReducedMotion()
    val haptics = LocalHapticFeedback.current
    val indicatorColor = MaterialTheme.colorScheme.primary
    val selectedTextColor = MaterialTheme.colorScheme.onPrimary
    val unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reducedMotion) tween(0) else MotionTokens.indicatorSpring(),
        label = "segmentedPillIndex"
    )

    val tabBounds = remember(labels.size) {
        mutableStateListOf<Pair<Float, Float>>().apply {
            repeat(labels.size) { add(0f to 0f) }
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        shadowElevation = shadowElevation,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(4.dp)
                .drawBehind {
                    val rawPos = if (positionProvider != null && !reducedMotion) {
                        positionProvider()
                    } else if (positionProvider != null && reducedMotion) {
                        selectedIndex.toFloat()
                    } else {
                        animatedIndex
                    }
                    val maxIdx = (labels.size - 1).coerceAtLeast(0)
                    val clamped = rawPos.coerceIn(0f, maxIdx.toFloat())
                    val floorIdx = floor(clamped).toInt().coerceIn(0, maxIdx)
                    val ceilIdx = ceil(clamped).toInt().coerceIn(0, maxIdx)
                    val fraction = (clamped - floorIdx).coerceIn(0f, 1f)
                    val (startX, startW) = tabBounds[floorIdx]
                    val (endX, endW) = tabBounds[ceilIdx]
                    val indicatorX = startX + (endX - startX) * fraction
                    val indicatorW = startW + (endW - startW) * fraction
                    if (indicatorW > 0f && size.height > 0f) {
                        val radiusPx = 20.dp.toPx()
                        drawRoundRect(
                            color = indicatorColor,
                            topLeft = Offset(indicatorX, 0f),
                            size = Size(indicatorW, size.height),
                            cornerRadius = CornerRadius(radiusPx, radiusPx)
                        )
                    }
                }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEachIndexed { index, label ->
                    val isSelected = selectedIndex == index
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) selectedTextColor else unselectedTextColor,
                        animationSpec = if (reducedMotion) {
                            tween(0)
                        } else {
                            tween(durationMillis = MotionTokens.DurationQuickMs, easing = MotionTokens.EaseOutStrong)
                        },
                        label = "segmentedTabTextColor"
                    )
                    val interaction = remember(index) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .onPlaced { coordinates ->
                                val left = coordinates.positionInParent().x
                                val width = coordinates.size.width.toFloat()
                                val existing = tabBounds[index]
                                if (existing.first != left || existing.second != width) {
                                    tabBounds[index] = left to width
                                }
                            }
                            .clip(RoundedCornerShape(20.dp))
                            .minimumInteractiveComponentSize()
                            .pressScale(interaction, pressedScale = 0.96f)
                            .clickable(
                                interactionSource = interaction,
                                indication = null
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(index)
                            }
                            .testTag(testTags.getOrElse(index) { "segmented_tab_$index" }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor,
                            maxLines = 1,
                            modifier = Modifier.padding(
                                horizontal = itemHorizontalPadding,
                                vertical = itemVerticalPadding
                            )
                        )
                    }
                }
            }
        }
    }
}

