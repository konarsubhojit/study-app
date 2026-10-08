package dev.studyflow.feature.materials

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
import dev.studyflow.core.designsystem.theme.spacing
import dev.studyflow.core.model.Material
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaPreview(
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

private const val MEDIA_CACHE_BYTES = 512L * 1024L * 1024L

private val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
