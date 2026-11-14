package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.pressScale

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
