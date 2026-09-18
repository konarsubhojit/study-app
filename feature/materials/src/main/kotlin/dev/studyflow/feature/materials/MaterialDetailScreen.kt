package dev.studyflow.feature.materials

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import dev.studyflow.core.model.MaterialKind
import dev.studyflow.core.ui.components.DurationText
import dev.studyflow.core.ui.components.StudyFlowTopAppBar
import dev.studyflow.core.ui.state.EmptyState
import dev.studyflow.core.ui.state.LoadingState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MaterialDetailUiEffect.CloseMaterial -> onBack()
            }
        }
    }

    MaterialDetailScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onShare = { material, source -> shareLocalMaterial(context, material, source) },
        onExport = { material, source -> shareLocalMaterial(context, material, source) },
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
            onPdfPageChanged = { pageIndex -> onEvent(MaterialDetailUiEvent.PdfPageChanged(pageIndex)) },
            onPlaybackChanged = { position, speed ->
                onEvent(MaterialDetailUiEvent.PlaybackChanged(position, speed))
            },
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
    onPdfPageChanged: (Int) -> Unit,
    onPlaybackChanged: (Long, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val material = state.material
    val source = state.previewSource
    when {
        state.previewSourceLoading -> LoadingState(modifier = modifier.fillMaxSize())
        state.previewSourceMessage != null -> EmptyState(message = state.previewSourceMessage, modifier = modifier.fillMaxSize())
        material == null || source == null -> EmptyState(message = "No preview is available for this material.", modifier = modifier)
        material.kind == MaterialKind.IMAGE -> ImagePreview(material = material, source = source, modifier = modifier)
        material.kind == MaterialKind.VIDEO || material.kind == MaterialKind.AUDIO ->
            MediaPreview(material = material, source = source, onPlaybackChanged = onPlaybackChanged, modifier = modifier)
        material.kind == MaterialKind.PDF ->
            PdfPreview(material = material, source = source, onPageChanged = onPdfPageChanged, modifier = modifier)
        else -> EmptyState(message = "This file type is saved in your library, but has no in-app preview yet.", modifier = modifier)
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
            scale = (scale * zoomChange).coerceIn(1f, 5f)
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
    onPlaybackChanged: (Long, Float) -> Unit,
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

    DisposableEffect(player) {
        onDispose {
            onPlaybackChanged(player.currentPosition.coerceAtLeast(0), player.playbackParameters.speed)
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
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                TextButton(
                    onClick = {
                        player.setPlaybackSpeed(speed)
                        onPlaybackChanged(player.currentPosition.coerceAtLeast(0), speed)
                    },
                ) {
                    Text(text = "${speed}x")
                }
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
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (source !is MaterialPreviewSource.Local) {
        EmptyState(message = "Download this PDF for offline use before previewing it.", modifier = modifier.fillMaxSize())
        return
    }
    val document = rememberPdfDocument(source.uri)
    if (document == null) {
        EmptyState(message = "This PDF could not be opened.", modifier = modifier.fillMaxSize())
        return
    }

    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = material.previewPageIndex.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)),
        )
    val coroutineScope = rememberCoroutineScope()
    var jumpPage by remember { mutableStateOf((listState.firstVisibleItemIndex + 1).toString()) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { pageIndex ->
                jumpPage = (pageIndex + 1).toString()
                onPageChanged(pageIndex)
            }
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small), verticalAlignment = Alignment.CenterVertically) {
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
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, document, pageIndex) {
        value =
            withContext(Dispatchers.Default) {
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
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, material.displayName))
}

private const val MEDIA_CACHE_BYTES = 512L * 1024L * 1024L
