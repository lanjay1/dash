package com.ztune.libretune.ui.screens.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ztune.libretune.core.ini.types.HelpTopic

/**
 * Renders a [HelpTopic] as a scrollable, Markdown-like document.
 *
 * Supported syntax in [HelpTopic.text]:
 * - `# Heading` / `## Sub-heading` — rendered as H1 / H2
 * - `**bold text**` — rendered with bold weight
 * - `- bullet item` — rendered as a bulleted list item
 * - Blank lines separate paragraphs
 *
 * Lines not matching any special syntax are rendered as body paragraphs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpViewerScreen(
    topic: HelpTopic,
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topic.title.ifEmpty { "Help" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (topic.title.isNotEmpty()) {
                Text(
                    topic.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            val blocks = parseMarkdownBlocks(topic.text)
            for (block in blocks) {
                when (block) {
                    is MarkdownBlock.Heading1 -> {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                    }
                    is MarkdownBlock.Heading2 -> {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                        )
                    }
                    is MarkdownBlock.Bullet -> {
                        Text(
                            text = renderInlineFormatting(block.text),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textIndent = TextIndent(restLine = 16.sp, firstLine = 0.sp)
                            ),
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                        )
                    }
                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = renderInlineFormatting(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ======================================================================
// Markdown parsing model
// ======================================================================

private sealed class MarkdownBlock {
    data class Heading1(val text: String) : MarkdownBlock()
    data class Heading2(val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
}

/**
 * Split raw markdown text into typed blocks.
 * Consecutive blank lines are collapsed. Lines are trimmed.
 */
private fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    if (raw.isBlank()) return emptyList()
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = raw.lines()
    var paragraphBuffer = StringBuilder()

    fun flushParagraph() {
        val text = paragraphBuffer.toString().trim()
        if (text.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(text))
        }
        paragraphBuffer = StringBuilder()
    }

    for (line in lines) {
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flushParagraph()
            trimmed.startsWith("## ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Heading2(trimmed.removePrefix("## ").trim()))
            }
            trimmed.startsWith("# ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Heading1(trimmed.removePrefix("# ").trim()))
            }
            trimmed.startsWith("- ") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Bullet(trimmed.removePrefix("- ").trim()))
            }
            else -> {
                if (paragraphBuffer.isNotEmpty()) paragraphBuffer.append(" ")
                paragraphBuffer.append(trimmed)
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * Render inline Markdown formatting into an [AnnotatedString].
 *
 * Currently handles `**bold**` spans.
 */
private fun renderInlineFormatting(text: String): AnnotatedString = buildAnnotatedString {
    var pos = 0
    var boldDepth = 0
    var segmentStart = 0

    while (pos < text.length) {
        if (pos + 1 < text.length && text[pos] == '*' && text[pos + 1] == '*') {
            // Flush current segment
            if (pos > segmentStart) {
                val segment = text.substring(segmentStart, pos)
                if (boldDepth > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment) }
                } else {
                    append(segment)
                }
            }
            boldDepth++
            segmentStart = pos + 2
            pos += 2
        } else {
            pos++
        }
    }

    // Flush remaining
    if (segmentStart < text.length) {
        val segment = text.substring(segmentStart)
        if (boldDepth > 0) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(segment) }
        } else {
            append(segment)
        }
    }
}
