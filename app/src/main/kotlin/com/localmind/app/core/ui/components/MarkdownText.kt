package com.localmind.app.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.app.core.ui.markdown.CodeTokenType
import com.localmind.app.core.ui.markdown.MarkdownBlock
import com.localmind.app.core.ui.markdown.MarkdownListItem
import com.localmind.app.core.ui.markdown.highlightCode
import com.localmind.app.core.ui.markdown.parseInlineSpans
import com.localmind.app.core.ui.markdown.parseMarkdown
import com.localmind.app.core.ui.theme.CodeTextStyle
import com.localmind.app.core.ui.theme.LocalMindExtras
import kotlinx.coroutines.delay

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> InlineText(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                is MarkdownBlock.Heading -> InlineText(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 0.dp),
                )

                is MarkdownBlock.Code -> CodeBlock(block)

                is MarkdownBlock.Quote -> {
                    Row(
                        modifier = Modifier.height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                        )
                        InlineText(
                            text = block.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item -> ListRow(item) }
                }

                MarkdownBlock.Divider -> HorizontalDivider(
                    color = LocalMindExtras.current.border,
                    modifier = Modifier.padding(vertical = 2.dp),
                )

                is MarkdownBlock.Table -> SimpleTable(block)
            }
        }
    }
}

@Composable
private fun ListRow(item: MarkdownListItem) {
    Row(
        modifier = Modifier.padding(start = (item.indent * 14).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (item.ordered) "${item.number}." else "•",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp),
        )
        InlineText(
            text = item.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InlineText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val extras = LocalMindExtras.current
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val muted = extras.mutedText
    val annotated = remember(text, color, codeBackground, linkColor, muted) {
        val spans = parseInlineSpans(text)
        buildAnnotatedString {
            spans.forEach { span ->
                if (span.code) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = codeBackground,
                            color = color,
                        ),
                    ) { append(span.text) }
                } else if (span.link != null) {
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Url(
                            url = span.link,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) { append(span.text) }
                } else {
                    withStyle(
                        SpanStyle(
                            fontWeight = if (span.bold) FontWeight.SemiBold else null,
                            fontStyle = if (span.italic) FontStyle.Italic else null,
                            textDecoration = if (span.strikethrough) TextDecoration.LineThrough else null,
                            color = if (span.italic) muted else color,
                        ),
                    ) { append(span.text) }
                }
            }
        }
    }
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    val extras = LocalMindExtras.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val codeColor = MaterialTheme.colorScheme.onSurface
    val keyword = MaterialTheme.colorScheme.primary
    val string = MaterialTheme.colorScheme.secondary
    val number = MaterialTheme.colorScheme.tertiary
    val comment = extras.mutedText
    val annotation = extras.warning

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }

    val highlighted = remember(block.code, block.language, block.closed) {
        if (!block.closed) return@remember null
        val tokens = highlightCode(block.code, block.language)
        buildAnnotatedString {
            tokens.forEach { token ->
                val tokenColor = when (token.type) {
                    CodeTokenType.KEYWORD -> keyword
                    CodeTokenType.STRING -> string
                    CodeTokenType.NUMBER -> number
                    CodeTokenType.COMMENT -> comment
                    CodeTokenType.ANNOTATION -> annotation
                    CodeTokenType.PLAIN -> codeColor
                }
                withStyle(
                    SpanStyle(
                        color = tokenColor,
                        fontStyle = if (token.type == CodeTokenType.COMMENT) FontStyle.Italic else null,
                        fontWeight = if (token.type == CodeTokenType.KEYWORD) FontWeight.Medium else null,
                    ),
                ) { append(token.text) }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = extras.codeBackground,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, extras.border.copy(alpha = 0.8f)),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language?.lowercase() ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.mutedText,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(block.code))
                        copied = true
                    },
                    enabled = block.closed,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        tint = extras.mutedText,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            HorizontalDivider(color = extras.border.copy(alpha = 0.6f))
            SelectionContainer {
                Text(
                    text = highlighted ?: AnnotatedString(block.code),
                    style = CodeTextStyle,
                    color = codeColor,
                    softWrap = false,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun SimpleTable(block: MarkdownBlock.Table) {
    val extras = LocalMindExtras.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, extras.border.copy(alpha = 0.8f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                block.headers.forEach { header ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            block.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = extras.border.copy(alpha = 0.5f))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    row.forEach { cell ->
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
