package dev.studyflow.feature.materials

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.studyflow.core.common.coroutines.DispatcherProvider
import dev.studyflow.core.common.coroutines.StandardDispatcherProvider
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.ui.components.DurationText
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The material detail screen: what the catalogue knows about one imported file (issue #37).
 *
 * Reached either from the catalogue list or from a duplicate import's "view existing" action —
 * both dispatch through [dev.studyflow.app.navigation] to the same typed route, so this is the one
 * destination either path ends at rather than a second, screen-local notion of "viewing" a
 * material.
 */
@Composable
public fun MaterialDetailRoute(
    materialId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onStartStudySession: (String?) -> Unit = {},
    viewModel: MaterialDetailViewModel = hiltViewModel(key = materialId),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(materialId) {
        viewModel.onEvent(MaterialDetailUiEvent.Load(materialId))
    }

    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MaterialDetailUiEffect.CloseMaterial -> currentOnBack()
            }
        }
    }

    MaterialDetailScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onShare = { material, source -> shareLocalMaterial(context, material, source) },
        onExport = { material, source -> exportLocalMaterialToDownloads(context, material, source) },
        onStartStudySession = onStartStudySession,
        modifier = modifier,
    )
}

@Composable
public fun MaterialDetailScreen(
    state: MaterialDetailUiState,
    onEvent: (MaterialDetailUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onShare: (Material, MaterialPreviewSource?) -> Unit = { _, _ -> },
    onExport: (Material, MaterialPreviewSource?) -> Unit = { _, _ -> },
    onStartStudySession: (String?) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        StudyFlowTopAppBar(
            title = state.material?.displayName ?: "Material",
            navigationIcon = {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Back to materials" },
                ) {
                    Text(text = "Back")
                }
            },
        )

        when {
            state.loading -> {
                LoadingState(modifier = Modifier.fillMaxSize())
            }

            state.notFound -> {
                EmptyState(
                    message = "This material no longer exists. It may have been deleted on another device.",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                MaterialDetailContent(
                    state = state,
                    onEvent = onEvent,
                    onShare = onShare,
                    onExport = onExport,
                    onStartStudySession = onStartStudySession,
                )
            }
        }
    }
}

@Composable
private fun MaterialDetailContent(
    state: MaterialDetailUiState,
    onEvent: (MaterialDetailUiEvent) -> Unit,
    onShare: (Material, MaterialPreviewSource?) -> Unit,
    onExport: (Material, MaterialPreviewSource?) -> Unit,
    onStartStudySession: (String?) -> Unit,
) {
    val material = requireNotNull(state.material)
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text(text = material.displayName, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${material.mimeType} · ${formatSize(material.sizeBytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        material.pageCount?.let { pages ->
            Text(text = "$pages page${if (pages == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
        }
        material.duration?.let { duration -> DurationText(duration = duration) }
        material.notes?.let { notes -> Text(text = notes, style = MaterialTheme.typography.bodyMedium) }
        PreviewActions(
            material = material,
            source = state.previewSource,
            onShare = onShare,
            onExport = onExport,
            onDelete = { onEvent(MaterialDetailUiEvent.DeleteMaterial) },
            onStartStudySession = onStartStudySession,
        )
        MaterialPreview(
            state = state,
            onPdfPageChange = { pageIndex -> onEvent(MaterialDetailUiEvent.PdfPageChanged(pageIndex)) },
            onPlaybackChange = { position, speed ->
                onEvent(MaterialDetailUiEvent.PlaybackChanged(position, speed))
            },
            onExtractArchiveEntry = { path -> onEvent(MaterialDetailUiEvent.ExtractArchiveEntry(path)) },
            onCancelArchiveExtraction = { path -> onEvent(MaterialDetailUiEvent.CancelArchiveExtraction(path)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PreviewActions(
    material: Material,
    source: MaterialPreviewSource?,
    onShare: (Material, MaterialPreviewSource?) -> Unit,
    onExport: (Material, MaterialPreviewSource?) -> Unit,
    onDelete: () -> Unit,
    onStartStudySession: (String?) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(onClick = { onShare(material, source) }, enabled = source is MaterialPreviewSource.Local) {
            Text(text = "Share")
        }
        TextButton(onClick = { onExport(material, source) }, enabled = source is MaterialPreviewSource.Local) {
            Text(text = "Export")
        }
        TextButton(onClick = onDelete) {
            Text(text = "Delete")
        }
        TextButton(onClick = { onStartStudySession(material.subjectId) }) {
            Text(text = "Study")
        }
    }
}

@Composable
private fun MaterialPreview(
    state: MaterialDetailUiState,
    onPdfPageChange: (Int) -> Unit,
    onPlaybackChange: (Long, Float) -> Unit,
    onExtractArchiveEntry: (String) -> Unit,
    onCancelArchiveExtraction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val material = state.material
    val source = state.previewSource
    when {
        state.previewSourceLoading -> {
            LoadingState(modifier = modifier.fillMaxSize())
        }

        state.previewSourceMessage != null -> {
            EmptyState(message = state.previewSourceMessage, modifier = modifier.fillMaxSize())
        }

        material == null || source == null -> {
            EmptyState(message = "No preview is available for this material.", modifier = modifier)
        }

        material.kind == MaterialKind.IMAGE -> {
            ImagePreview(material = material, source = source, modifier = modifier)
        }

        material.kind == MaterialKind.VIDEO || material.kind == MaterialKind.AUDIO -> {
            MediaPreview(material = material, source = source, onPlaybackChange = onPlaybackChange, modifier = modifier)
        }

        material.kind == MaterialKind.PDF -> {
            PdfPreview(material = material, source = source, onPageChange = onPdfPageChange, modifier = modifier)
        }

        material.kind == MaterialKind.ARCHIVE -> {
            ArchivePreview(
                archivePreview = state.archivePreview,
                onExtract = onExtractArchiveEntry,
                onCancel = onCancelArchiveExtraction,
                modifier = modifier,
            )
        }

        material.kind == MaterialKind.TEXT -> {
            TextPreview(source = source, modifier = modifier)
        }

        else -> {
            // DOCUMENT, SPREADSHEET, PRESENTATION and OTHER: no bundled renderer, so the only
            // in-app affordance is handing the file to whatever the device already has installed.
            OpenExternallyPreview(material = material, source = source, modifier = modifier)
        }
    }
}

@Composable
private fun ImagePreview(
    material: Material,
    source: MaterialPreviewSource,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val transformableState =
        rememberTransformableState { _, zoomChange, offsetChange, _ ->
            scale = (scale * zoomChange).coerceIn(IMAGE_ZOOM_MIN_SCALE, IMAGE_ZOOM_MAX_SCALE)
            offsetX += offsetChange.x
            offsetY += offsetChange.y
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = source.previewModel(),
            contentDescription = "${material.displayName} preview",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }.transformable(transformableState),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaPreview(
    material: Material,
    source: MaterialPreviewSource,
    onPlaybackChange: (Long, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cache = rememberMediaCache(context)
    val dataSourceFactory =
        remember(cache, context) {
            CacheDataSource
                .Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
        }
    val player =
        remember(source.uri) {
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(source.playerUri()))
                    seekTo(material.previewPositionMillis)
                    setPlaybackSpeed(material.playbackSpeed)
                    prepare()
                }
        }

    val currentOnPlaybackChange by rememberUpdatedState(onPlaybackChange)

    DisposableEffect(player) {
        onDispose {
            currentOnPlaybackChange(player.currentPosition.coerceAtLeast(0), player.playbackParameters.speed)
            player.release()
        }
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    this.player = player
                    useController = true
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        )
        PlaybackSpeedRow(player = player, onPlaybackChange = onPlaybackChange)
    }
}

@Composable
private fun PlaybackSpeedRow(
    player: ExoPlayer,
    onPlaybackChange: (Long, Float) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        PLAYBACK_SPEEDS.forEach { speed ->
            TextButton(
                onClick = {
                    player.setPlaybackSpeed(speed)
                    onPlaybackChange(player.currentPosition.coerceAtLeast(0), speed)
                },
            ) {
                Text(text = "${speed}x")
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun rememberMediaCache(context: Context): SimpleCache {
    val cache =
        remember(context) {
            SimpleCache(
                File(context.cacheDir, "media-preview"),
                LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES),
                StandaloneDatabaseProvider(context),
            )
        }
    DisposableEffect(cache) {
        onDispose { cache.release() }
    }
    return cache
}

@Composable
private fun PdfPreview(
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
            val document = rememberPdfDocument(source.uri)
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
                PdfPage(document = document, pageIndex = pageIndex)
            }
        }
    }
}

@Composable
private fun rememberPdfDocument(path: String): PdfDocument? {
    val document =
        remember(path) {
            runCatching {
                val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
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
            Modifier
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

/**
 * The archive branch of the preview (issue #40): a read-only listing of what
 * [ArchiveEntryUiState] already knows is safe, with per-entry extraction and a plain-language
 * explanation whenever [ArchivePreviewUiState.message] says something was refused.
 */
@Composable
private fun ArchivePreview(
    archivePreview: ArchivePreviewUiState?,
    onExtract: (String) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        archivePreview == null || archivePreview.loading -> {
            LoadingState(modifier = modifier.fillMaxSize())
        }

        archivePreview.entries.isEmpty() -> {
            EmptyState(
                message = archivePreview.message ?: "This archive has nothing safe to show.",
                modifier = modifier.fillMaxSize(),
            )
        }

        else -> {
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                archivePreview.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
                    )
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(archivePreview.entries, key = { it.path }) { entry ->
                        ArchiveEntryRow(
                            entry = entry,
                            onExtract = { onExtract(entry.path) },
                            onCancel = { onCancel(entry.path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveEntryRow(
    entry: ArchiveEntryUiState,
    onExtract: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.medium)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = formatSize(entry.sizeBytes), style = MaterialTheme.typography.bodySmall)
            }
            ArchiveEntryAction(entry = entry, onExtract = onExtract, onCancel = onCancel)
        }
        (entry.extraction as? ArchiveExtractionUiState.Failed)?.let { failed ->
            Text(
                text = failed.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ArchiveEntryAction(
    entry: ArchiveEntryUiState,
    onExtract: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    when (val extraction = entry.extraction) {
        ArchiveExtractionUiState.Idle -> {
            TextButton(onClick = onExtract) { Text(text = "Extract") }
        }

        ArchiveExtractionUiState.Extracting -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Extracting…", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancel) { Text(text = "Cancel") }
            }
        }

        is ArchiveExtractionUiState.Done -> {
            TextButton(
                onClick = { context.openLocalFile(File(extraction.localPath), guessMimeType(entry.name)) },
            ) {
                Text(text = "Open")
            }
        }

        is ArchiveExtractionUiState.Failed -> {
            TextButton(onClick = onExtract) { Text(text = "Retry") }
        }
    }
}

/**
 * The fallback preview for kinds with no bundled renderer — [MaterialKind.DOCUMENT],
 * [MaterialKind.SPREADSHEET], [MaterialKind.PRESENTATION] and [MaterialKind.OTHER] (issue #40).
 *
 * "Open" hands the cached file to whatever the device already has installed instead of shipping a
 * document engine. When nothing can open it, [ActivityNotFoundException] is caught rather than
 * left to crash the screen, and the existing Share/Export actions in [PreviewActions] stay usable
 * either way.
 */
@Composable
private fun OpenExternallyPreview(
    material: Material,
    source: MaterialPreviewSource?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var noViewerAvailable by remember(material.id) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
    ) {
        Text(
            text = "This file type is saved in your library, but has no in-app preview yet.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (noViewerAvailable) {
            Text(
                text = "No app on this device can open this file. You can still share or export it below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                noViewerAvailable =
                    source !is MaterialPreviewSource.Local ||
                    !context.openLocalFile(File(source.uri), material.mimeType)
            },
            enabled = source is MaterialPreviewSource.Local,
        ) {
            Text(text = "Open")
        }
    }
}

/**
 * The [MaterialKind.TEXT] branch: plain text, Markdown or code read straight off the cached file
 * (issue #40). Reading happens off the main thread and stops well short of the whole file for
 * anything large, since a multi-hundred-megabyte log should never be loaded into memory just to
 * show a preview.
 */
@Composable
private fun TextPreview(
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
                readTextPreview(File(source.uri))?.let(TextPreviewState::Loaded) ?: TextPreviewState.Failed
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

/** Guesses a MIME type from a filename extension; falls back to a wildcard when unknown or absent. */
private fun guessMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: ALL_MIME_TYPES
}

/** Opens [file] with an installed viewer via [FileProvider], never a raw `file://` Uri. */
private fun Context.openLocalFile(
    file: File,
    mimeType: String,
): Boolean {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType.ifBlank { ALL_MIME_TYPES })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return start(intent)
}

/** Starts [intent], tolerating a device with nothing installed to handle it. */
private fun Context.start(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

private fun MaterialPreviewSource.previewModel(): Any =
    when (this) {
        is MaterialPreviewSource.Local -> File(uri)
        is MaterialPreviewSource.Remote -> uri
    }

private fun MaterialPreviewSource.playerUri(): Uri =
    when (this) {
        is MaterialPreviewSource.Local -> Uri.fromFile(File(uri))
        is MaterialPreviewSource.Remote -> uri.toUri()
    }

private fun shareLocalMaterial(
    context: Context,
    material: Material,
    source: MaterialPreviewSource?,
) {
    if (source !is MaterialPreviewSource.Local) return
    val intent =
        Intent(Intent.ACTION_SEND)
            .setType(material.mimeType)
            .putExtra(
                Intent.EXTRA_STREAM,
                FileProvider.getUriForFile(context, "${context.packageName}.files", File(source.uri)),
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, material.displayName))
}

internal fun exportLocalMaterialToDownloads(
    context: Context,
    material: Material,
    source: MaterialPreviewSource?,
    insertDownload: (ContentValues) -> Uri? = { values ->
        context.contentResolver.insert(downloadsCollectionUri(), values)
    },
    openOutput: (Uri) -> OutputStream? = { uri -> context.contentResolver.openOutputStream(uri) },
    markFinished: (Uri) -> Unit = { uri -> context.markDownloadFinished(uri) },
    deleteDownload: (Uri) -> Unit = { uri -> context.contentResolver.delete(uri, null, null) },
): Boolean {
    if (source !is MaterialPreviewSource.Local) return false
    val sourceFile = File(source.uri)
    if (!sourceFile.isFile) return false

    val downloadUri =
        try {
            insertDownload(material.downloadContentValues())
        } catch (_: SecurityException) {
            null
        } ?: return false

    return try {
        val output =
            openOutput(downloadUri)
                ?: run {
                    deleteDownload(downloadUri)
                    return false
                }
        output.use { target ->
            sourceFile.inputStream().use { input -> input.copyTo(target) }
        }
        markFinished(downloadUri)
        true
    } catch (_: IOException) {
        deleteDownload(downloadUri)
        false
    } catch (_: SecurityException) {
        deleteDownload(downloadUri)
        false
    }
}

private fun Material.downloadContentValues(): ContentValues =
    ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifBlank { ALL_MIME_TYPES })
        put(MediaStore.MediaColumns.SIZE, sizeBytes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

private fun Context.markDownloadFinished(uri: Uri) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val values =
        ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
    contentResolver.update(uri, values, null, null)
}

private fun downloadsCollectionUri(): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Files.getContentUri("external")
    }

private const val MEDIA_CACHE_BYTES = 512L * 1024L * 1024L
private const val IMAGE_ZOOM_MIN_SCALE = 1f
private const val IMAGE_ZOOM_MAX_SCALE = 5f
private val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private const val ALL_MIME_TYPES = "*/*"

/** ~300 KB of characters — generous for notes or code, far short of loading a huge log whole. */
private const val TEXT_PREVIEW_MAX_BYTES = 300_000
