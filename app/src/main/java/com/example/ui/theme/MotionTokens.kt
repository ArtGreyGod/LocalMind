package com.example.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * Motion tokens aligned with responsive tactile feel and reduced-motion accessibility:
 * - Fast-out easing (`EaseOutStrong`) for UI entrances, panels, and state transitions.
 * - Symmetric strong easing (`EaseInOutStrong`) for on-screen morphs and icon rotations.
 * - Critically damped springs for layout expansion without overshoot wobble.
 */
object MotionTokens {
    val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOutStrong = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    const val DurationQuickMs = 130
    const val DurationStandardMs = 190
    const val DurationPanelMs = 240

    fun <T> snappySpring() = spring<T>(
        dampingRatio = 1.0f,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> tactileSpring() = spring<T>(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> pageSpring() = spring<T>(
        dampingRatio = 0.88f,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> indicatorSpring() = spring<T>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> listItemSpring() = spring<T>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow
    )
}

@Composable
fun rememberPrefersReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Applies GPU-only tactile scale feedback on press (0.97f by default) without triggering layout passes.
 * Respects system reduced-motion settings automatically.
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.965f
): Modifier = composed {
    val reducedMotion = rememberPrefersReducedMotion()
    val isPressed by interactionSource.collectIsPressedAsState()
    val target = if (enabled && !reducedMotion && isPressed) pressedScale else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = if (isPressed) {
            tween(durationMillis = 80, easing = MotionTokens.EaseOutStrong)
        } else {
            tween(durationMillis = 150, easing = MotionTokens.EaseOutStrong)
        },
        label = "pressScale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
