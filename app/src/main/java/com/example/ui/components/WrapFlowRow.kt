package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Dependency-version-independent wrapping horizontal layout built on stable Compose [Layout].
 * Avoids experimental [androidx.compose.foundation.layout.FlowRow] binary signature changes across Compose versions.
 */
@Composable
fun WrapFlowRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val hSpacePx = horizontalSpacing.roundToPx()
        val vSpacePx = verticalSpacing.roundToPx()
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val rows = mutableListOf<MutableList<Placeable>>()
        val rowWidths = mutableListOf<Int>()
        val rowHeights = mutableListOf<Int>()

        var currentRow = mutableListOf<Placeable>()
        var currentRowWidth = 0
        var currentRowHeight = 0

        for (measurable in measurables) {
            val placeable = measurable.measure(childConstraints)
            val neededWidth = if (currentRow.isEmpty()) placeable.width else hSpacePx + placeable.width
            if (currentRow.isNotEmpty() && currentRowWidth + neededWidth > maxWidth) {
                rows.add(currentRow)
                rowWidths.add(currentRowWidth)
                rowHeights.add(currentRowHeight)
                currentRow = mutableListOf(placeable)
                currentRowWidth = placeable.width
                currentRowHeight = placeable.height
            } else {
                currentRow.add(placeable)
                currentRowWidth += neededWidth
                currentRowHeight = max(currentRowHeight, placeable.height)
            }
        }
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
            rowWidths.add(currentRowWidth)
            rowHeights.add(currentRowHeight)
        }

        val totalWidth = (rowWidths.maxOrNull() ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val totalHeight = if (rowHeights.isEmpty()) {
            constraints.minHeight
        } else {
            val contentHeight = rowHeights.sum() + vSpacePx * (rowHeights.size - 1)
            contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        }

        layout(totalWidth, totalHeight) {
            var y = 0
            rows.forEachIndexed { rowIdx, row ->
                var x = 0
                val rowHeight = rowHeights[rowIdx]
                for (placeable in row) {
                    val yOffset = (rowHeight - placeable.height) / 2
                    placeable.placeRelative(x = x, y = y + yOffset)
                    x += placeable.width + hSpacePx
                }
                y += rowHeight + vSpacePx
            }
        }
    }
}
