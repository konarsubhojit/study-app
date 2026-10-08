package dev.studyflow.feature.materials

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.core.graphics.createBitmap
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.coroutines.StandardDispatcherProvider
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import dev.studyflow.core.ui.state.EmptyState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PdfPreview(
    material: Material,
    source: MaterialPreviewSource,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        source !is MaterialPreviewSource.Local -> {
            EmptyState(
                message = "Download this PDF for offline use before previewing it.",
                modifier = modifier.fillMaxSize(),
            )
        }

        else -> {
            val document = rememberPdfDocument(source.uri.toLocalFileOrNull())
            if (document == null) {
                EmptyState(message = "This PDF could not be opened.", modifier = modifier.fillMaxSize())
            } else {
                PdfPreviewContent(
                    material = material,
                    document = document,
                    onPageChange = onPageChange,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun PdfPreviewContent(
    material: Material,
    document: PdfDocument,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                material.previewPageIndex.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)),
        )
    val coroutineScope = rememberCoroutineScope()
    var jumpPage by remember { mutableStateOf((listState.firstVisibleItemIndex + 1).toString()) }
    val currentOnPageChange by rememberUpdatedState(onPageChange)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { pageIndex ->
                jumpPage = (pageIndex + 1).toString()
                currentOnPageChange(pageIndex)
            }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Page", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = jumpPage,
                onValueChange = { jumpPage = it.filter(Char::isDigit) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val page = jumpPage.toIntOrNull()?.minus(1)?.coerceIn(0, document.pageCount - 1) ?: return@Button
                    coroutineScope.launch { listState.animateScrollToItem(page) }
                },
            ) {
                Text(text = "Go")
            }
            Text(text = "of ${document.pageCount}", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
            modifier = Modifier.fillMaxSize(),
        ) {
            items((0 until document.pageCount).toList(), key = { it }) { pageIndex ->
                PdfPage(document = document, pageIndex = pageIndex, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun rememberPdfDocument(file: File?): PdfDocument? {
    val document =
        remember(file) {
            if (file == null) return@remember null
            runCatching {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfDocument(renderer = PdfRenderer(descriptor), descriptor = descriptor)
            }.getOrNull()
        }
    DisposableEffect(document) {
        onDispose { document?.close() }
    }
    return document
}

@Composable
private fun PdfPage(
    document: PdfDocument,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    dispatcherProvider: DispatcherProvider = StandardDispatcherProvider,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, document, pageIndex) {
        value =
            withContext(dispatcherProvider.default) {
                synchronized(document.renderer) {
                    document.renderer.openPage(pageIndex).use { page ->
                        createBitmap(page.width, page.height).also { bitmap ->
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
    }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            CircularProgressIndicator(modifier = Modifier.padding(MaterialTheme.spacing.medium))
        } else {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = "PDF page ${pageIndex + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class PdfDocument(
    val renderer: PdfRenderer,
    val descriptor: ParcelFileDescriptor,
) {
    val pageCount: Int get() = renderer.pageCount

    fun close() {
        renderer.close()
        descriptor.close()
    }
}
