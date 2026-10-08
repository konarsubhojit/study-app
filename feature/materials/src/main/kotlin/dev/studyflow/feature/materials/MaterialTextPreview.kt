package dev.studyflow.feature.materials

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.coroutines.StandardDispatcherProvider
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The [MaterialKind.TEXT] branch: plain text, Markdown or code read straight off the cached file
 * (issue #40). Reading happens off the main thread and stops well short of the whole file for
 * anything large, since a multi-hundred-megabyte log should never be loaded into memory just to
 * show a preview.
 */
@Composable
internal fun TextPreview(
    source: MaterialPreviewSource,
    modifier: Modifier = Modifier,
    dispatcherProvider: DispatcherProvider = StandardDispatcherProvider,
) {
    if (source !is MaterialPreviewSource.Local) {
        EmptyState(
            message = "Download this file for offline use before previewing it.",
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    var wrap by remember { mutableStateOf(true) }
    val previewState by produceState<TextPreviewState>(initialValue = TextPreviewState.Loading, source.uri) {
        value =
            withContext(dispatcherProvider.io) {
                source.uri
                    .toLocalFileOrNull()
                    ?.let(::readTextPreview)
                    ?.let(TextPreviewState::Loaded)
                    ?: TextPreviewState.Failed
            }
    }

    when (val current = previewState) {
        TextPreviewState.Loading -> {
            LoadingState(modifier = modifier.fillMaxSize())
        }

        TextPreviewState.Failed -> {
            EmptyState(message = "This file could not be opened.", modifier = modifier.fillMaxSize())
        }

        is TextPreviewState.Loaded -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                TextButton(onClick = { wrap = !wrap }) {
                    Text(text = if (wrap) "Turn off wrap" else "Wrap text")
                }
                TextPreviewBody(content = current.content, wrap = wrap, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TextPreviewBody(
    content: TextPreviewContent,
    wrap: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (content.truncated) {
            Text(
                text = "Truncated — showing the first ${content.readBytes} of ${content.totalBytes} bytes.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
            )
        }
        val verticalScroll = rememberScrollState()
        SelectionContainer(modifier = Modifier.weight(1f).verticalScroll(verticalScroll)) {
            if (wrap) {
                Text(
                    text = content.text,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.medium),
                )
            } else {
                val horizontalScroll = rememberScrollState()
                Text(
                    text = content.text,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier =
                        Modifier
                            .horizontalScroll(horizontalScroll)
                            .padding(MaterialTheme.spacing.medium),
                )
            }
        }
    }
}

private sealed interface TextPreviewState {
    data object Loading : TextPreviewState

    data object Failed : TextPreviewState

    data class Loaded(
        val content: TextPreviewContent,
    ) : TextPreviewState
}

private data class TextPreviewContent(
    val text: String,
    val readBytes: Int,
    val totalBytes: Long,
) {
    val truncated: Boolean get() = readBytes < totalBytes
}

/** Reads up to [maxBytes] of [file] as UTF-8 text, or `null` when the file cannot be read at all. */
private fun readTextPreview(
    file: File,
    maxBytes: Int = TEXT_PREVIEW_MAX_BYTES,
): TextPreviewContent? =
    runCatching {
        val totalBytes = file.length()
        val buffer = ByteArray(minOf(maxBytes.toLong(), totalBytes).coerceAtLeast(0).toInt())
        var read = 0
        file.inputStream().use { input ->
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count == -1) break
                read += count
            }
        }
        TextPreviewContent(text = String(buffer, 0, read, Charsets.UTF_8), readBytes = read, totalBytes = totalBytes)
    }.getOrNull()

/** ~300 KB of characters — generous for notes or code, far short of loading a huge log whole. */
private const val TEXT_PREVIEW_MAX_BYTES = 300_000
