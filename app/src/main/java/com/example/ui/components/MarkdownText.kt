package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.pressScale
import kotlinx.coroutines.delay

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String, val isClosed: Boolean) : MarkdownBlock
    data class Blockquote(val lines: List<String>) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class OrderedList(val items: List<Pair<Int, String>>) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

internal object MarkdownParser {
    private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
    private val orderedItemRegex = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
    private val bulletItemRegex = Regex("^\\s*[-*+]\\s+(.+)$")
    private val hrRegex = Regex("^\\s*(?:[-*_]\\s*){3,}$")
    private val tableSepRegex = Regex("^\\s*\\|?(?:\\s*:?-{2,}:?\\s*\\|)+\\s*:?-{2,}:?\\s*\\|?\\s*$")

    fun parseBlocks(markdown: String): List<MarkdownBlock> {
        if (markdown.isEmpty()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // Fenced code block (``` or ~~~), streaming-safe even if unclosed
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                val lang = trimmed.removePrefix(fence).trim()
                val codeLines = mutableListOf<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trim().startsWith(fence)) {
                        closed = true
                        i++
                        break
                    }
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(
                    MarkdownBlock.CodeBlock(
                        language = lang,
                        code = codeLines.joinToString("\n"),
                        isClosed = closed
                    )
                )
                continue
            }

            // Horizontal rule
            if (hrRegex.matches(trimmed)) {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++
                continue
            }

            // Heading
            headingRegex.matchEntire(trimmed)?.let { match ->
                blocks.add(
                    MarkdownBlock.Heading(
                        level = match.groupValues[1].length,
                        text = match.groupValues[2].trim()
                    )
                )
                i++
                continue
            }

            // Table (header row + separator row)
            if (trimmed.contains('|') && i + 1 < lines.size && tableSepRegex.matches(lines[i + 1].trim())) {
                val headers = parseTableRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val rowTrimmed = lines[i].trim()
                    if (rowTrimmed.isEmpty() || !rowTrimmed.contains('|')) break
                    rows.add(parseTableRow(rowTrimmed))
                    i++
                }
                blocks.add(MarkdownBlock.Table(headers = headers, rows = rows))
                continue
            }

            // Blockquote
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(MarkdownBlock.Blockquote(quoteLines))
                continue
            }

            // Bullet list
            if (bulletItemRegex.matches(line)) {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val m = bulletItemRegex.matchEntire(lines[i]) ?: break
                    items.add(m.groupValues[1].trim())
                    i++
                }
                blocks.add(MarkdownBlock.BulletList(items))
                continue
            }

            // Ordered list
            if (orderedItemRegex.matches(line)) {
                val items = mutableListOf<Pair<Int, String>>()
                while (i < lines.size) {
                    val m = orderedItemRegex.matchEntire(lines[i]) ?: break
                    val num = m.groupValues[1].toIntOrNull() ?: (items.size + 1)
                    items.add(num to m.groupValues[2].trim())
                    i++
                }
                blocks.add(MarkdownBlock.OrderedList(items))
                continue
            }

            // Paragraph (collect contiguous plain lines)
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrimmed = cur.trim()
                if (curTrimmed.isEmpty() ||
                    curTrimmed.startsWith("```") ||
                    curTrimmed.startsWith("~~~") ||
                    curTrimmed.startsWith(">") ||
                    hrRegex.matches(curTrimmed) ||
                    headingRegex.matches(curTrimmed) ||
                    bulletItemRegex.matches(cur) ||
                    orderedItemRegex.matches(cur) ||
                    (curTrimmed.contains('|') && i + 1 < lines.size && tableSepRegex.matches(lines[i + 1].trim()))
                ) {
                    break
                }
                paraLines.add(cur)
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString("\n")))
            } else {
                // Safety advance if line didn't match anything
                blocks.add(MarkdownBlock.Paragraph(line))
                i++
            }
        }
        return blocks
    }

    private fun parseTableRow(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.drop(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split('|').map { it.trim() }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    isStreaming: Boolean = false,
    codeContainerColor: Color = MaterialTheme.colorScheme.surface,
    inlineCodeBackground: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    accentColor: Color = MaterialTheme.colorScheme.secondary,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { MarkdownParser.parseBlocks(markdown) }

    if (blocks.isEmpty()) {
        if (isStreaming) {
            Text(text = "▍", style = style, color = color, modifier = modifier)
        }
        return
    }

    if (isStreaming) {
        MarkdownBlocksColumn(
            blocks = blocks,
            color = color,
            style = style,
            isStreaming = true,
            codeContainerColor = codeContainerColor,
            inlineCodeBackground = inlineCodeBackground,
            accentColor = accentColor,
            modifier = modifier
        )
    } else {
        SelectionContainer(modifier = modifier) {
            MarkdownBlocksColumn(
                blocks = blocks,
                color = color,
                style = style,
                isStreaming = false,
                codeContainerColor = codeContainerColor,
                inlineCodeBackground = inlineCodeBackground,
                accentColor = accentColor
            )
        }
    }
}

@Composable
private fun MarkdownBlocksColumn(
    blocks: List<MarkdownBlock>,
    color: Color,
    style: TextStyle,
    isStreaming: Boolean,
    codeContainerColor: Color,
    inlineCodeBackground: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEachIndexed { index, block ->
            val isLast = index == blocks.lastIndex
            val appendCursor = isStreaming && isLast
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val headingStyle = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            3 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                            else -> style.copy(fontWeight = FontWeight.SemiBold)
                        }
                        // Cache annotation per unchanged block text: during token streaming
                        // every stable prefix block skips regex re-annotation entirely.
                        val annotated = remember(block.text, appendCursor) {
                            buildInlineMarkdown(
                                text = if (appendCursor) "${block.text} ▍" else block.text,
                                inlineCodeBg = inlineCodeBackground,
                                linkColor = accentColor
                            )
                        }
                        Text(
                            text = annotated,
                            style = headingStyle,
                            color = color
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        val annotated = remember(block.text, appendCursor) {
                            buildInlineMarkdown(
                                text = if (appendCursor) "${block.text} ▍" else block.text,
                                inlineCodeBg = inlineCodeBackground,
                                linkColor = accentColor
                            )
                        }
                        Text(
                            text = annotated,
                            style = style,
                            color = color
                        )
                    }

                    is MarkdownBlock.CodeBlock -> {
                        MarkdownCodeBlock(
                            language = block.language,
                            code = if (appendCursor && !block.isClosed) "${block.code} ▍" else block.code,
                            rawCodeToCopy = block.code,
                            containerColor = codeContainerColor,
                            accentColor = accentColor
                        )
                    }

                    is MarkdownBlock.Blockquote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(8.dp))
                                .background(codeContainerColor.copy(alpha = 0.45f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(accentColor)
                            )
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val text = block.lines.joinToString("\n")
                                val annotated = remember(text, appendCursor) {
                                    buildInlineMarkdown(
                                        text = if (appendCursor) "$text ▍" else text,
                                        inlineCodeBg = inlineCodeBackground,
                                        linkColor = accentColor
                                    )
                                }
                                Text(
                                    text = annotated,
                                    style = style.copy(fontStyle = FontStyle.Italic),
                                    color = color.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }

                    is MarkdownBlock.BulletList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            block.items.forEachIndexed { itemIdx, item ->
                                val showCursorOnItem = appendCursor && itemIdx == block.items.lastIndex
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "•",
                                        style = style.copy(fontWeight = FontWeight.Bold),
                                        color = accentColor
                                    )
                                    val annotated = remember(item, showCursorOnItem) {
                                        buildInlineMarkdown(
                                            text = if (showCursorOnItem) "$item ▍" else item,
                                            inlineCodeBg = inlineCodeBackground,
                                            linkColor = accentColor
                                        )
                                    }
                                    Text(
                                        text = annotated,
                                        style = style,
                                        color = color,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.OrderedList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            block.items.forEachIndexed { itemIdx, (num, item) ->
                                val showCursorOnItem = appendCursor && itemIdx == block.items.lastIndex
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "$num.",
                                        style = style.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = accentColor
                                    )
                                    val annotated = remember(item, showCursorOnItem) {
                                        buildInlineMarkdown(
                                            text = if (showCursorOnItem) "$item ▍" else item,
                                            inlineCodeBg = inlineCodeBackground,
                                            linkColor = accentColor
                                        )
                                    }
                                    Text(
                                        text = annotated,
                                        style = style,
                                        color = color,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.Table -> {
                        MarkdownTable(
                            headers = block.headers,
                            rows = block.rows,
                            color = color,
                            style = style,
                            headerBackground = codeContainerColor,
                            inlineCodeBg = inlineCodeBackground,
                            accentColor = accentColor
                        )
                    }

                    MarkdownBlock.HorizontalRule -> {
                        HorizontalDivider(
                            color = color.copy(alpha = 0.22f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

@Composable
private fun MarkdownCodeBlock(
    language: String,
    code: String,
    rawCodeToCopy: String,
    containerColor: Color,
    accentColor: Color
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    val copyInteractionSource = remember { MutableInteractionSource() }
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    val gutterText = remember(lineCount) {
        buildString {
            for (line in 1..lineCount) {
                if (line > 1) append('\n')
                append(line)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("markdown_code_block")
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 52.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Single-Text line number gutter avoids N-composable churn while code streams
                if (lineCount > 1 || language.isBlank()) {
                    Text(
                        text = gutterText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.End
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (language.isNotBlank()) {
                            Text(
                                text = language.lowercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            )
                        }
                        Text(
                            text = code,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 12.5.sp,
                                lineHeight = 19.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Crisp floating circular copy action tucked in top-right corner
            Surface(
                onClick = {
                    clipboardManager.setText(AnnotatedString(rawCodeToCopy))
                    copied = true
                },
                interactionSource = copyInteractionSource,
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(48.dp)
                    .pressScale(copyInteractionSource, pressedScale = 0.90f)
                    .testTag("copy_code_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else LocalMindIcons.ContentCopy,
                        contentDescription = if (copied) "Copied code" else "Copy code",
                        tint = if (copied) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    headers: List<String>,
    rows: List<List<String>>,
    color: Color,
    style: TextStyle,
    headerBackground: Color,
    inlineCodeBg: Color,
    accentColor: Color
) {
    val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            Row(modifier = Modifier.background(headerBackground)) {
                for (c in 0 until colCount) {
                    val cell = headers.getOrElse(c) { "" }
                    Text(
                        text = buildInlineMarkdown(cell, inlineCodeBg, accentColor),
                        style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                        color = color,
                        modifier = Modifier
                            .width(128.dp)
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
            rows.forEach { row ->
                HorizontalDivider(color = borderColor, thickness = 0.5.dp)
                Row {
                    for (c in 0 until colCount) {
                        val cell = row.getOrElse(c) { "" }
                        Text(
                            text = buildInlineMarkdown(cell, inlineCodeBg, accentColor),
                            style = style.copy(fontSize = 13.sp),
                            color = color,
                            modifier = Modifier
                                .width(128.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Parses inline markdown syntax (`code`, ***bold italic***, **bold**, *italic*, ~~strike~~, [label](url)).
 */
internal fun buildInlineMarkdown(
    text: String,
    inlineCodeBg: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val len = text.length

    while (i < len) {
        val ch = text[i]

        // Escaped character
        if (ch == '\\' && i + 1 < len) {
            append(text[i + 1])
            i += 2
            continue
        }

        // Inline code: `...`
        if (ch == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                val codeText = text.substring(i + 1, end)
                withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        background = inlineCodeBg,
                        fontSize = 13.sp
                    )
                ) {
                    append(" $codeText ")
                }
                i = end + 1
                continue
            }
        }

        // Link: [label](url)
        if (ch == '[') {
            val closeBracket = text.indexOf(']', i + 1)
            if (closeBracket > i + 1 && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket + 2)
                if (closeParen > closeBracket + 2) {
                    val label = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen).trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            )
                        ) {
                            append(label)
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }
        }

        // Bold + Italic: ***...*** or ___...___
        if ((text.startsWith("***", i) || text.startsWith("___", i))) {
            val marker = text.substring(i, i + 3)
            val end = text.indexOf(marker, i + 3)
            if (end > i + 3) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(buildInlineMarkdown(text.substring(i + 3, end), inlineCodeBg, linkColor))
                }
                i = end + 3
                continue
            }
        }

        // Bold: **...** or __...__
        if ((text.startsWith("**", i) || text.startsWith("__", i))) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(buildInlineMarkdown(text.substring(i + 2, end), inlineCodeBg, linkColor))
                }
                i = end + 2
                continue
            }
        }

        // Strikethrough: ~~...~~
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(buildInlineMarkdown(text.substring(i + 2, end), inlineCodeBg, linkColor))
                }
                i = end + 2
                continue
            }
        }

        // Italic: *...* or _..._ (avoid snake_case underscores mid-word)
        if (ch == '*' || (ch == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) {
            val end = text.indexOf(ch, i + 1)
            if (end > i + 1 && !text[i + 1].isWhitespace() &&
                (ch == '*' || end + 1 >= len || !text[end + 1].isLetterOrDigit())
            ) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(buildInlineMarkdown(text.substring(i + 1, end), inlineCodeBg, linkColor))
                }
                i = end + 1
                continue
            }
        }

        append(ch)
        i++
    }
}
