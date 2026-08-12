package com.anilili.ui.shorts

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.anilili.data.model.ShortsItem

@OptIn(UnstableApi::class)
@Composable
fun ShortsPlayerItem(
    item: ShortsItem,
    isActive: Boolean,
    isMuted: Boolean,
    onRefreshStream: (String, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isUnavailable by remember { mutableStateOf(item.isUnavailable) }
    var hasAttemptedRefresh by remember { mutableStateOf(false) }

    val currentVideoUrl = item.videoUrl
    val currentStreamType = item.streamType

    val exoPlayer = remember(context, item.id) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    val prepareMediaSource = remember(exoPlayer, context) {
        { url: String, type: String ->
            if (url.isNotBlank()) {
                val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent("com.google.android.youtube/19.29.37 (Linux; U; Android 11; US)")
                    .setAllowCrossProtocolRedirects(true)
                val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
                val mediaItem = MediaItem.fromUri(Uri.parse(url))
                val mediaSource = if (type.equals("HLS", ignoreCase = true) || url.contains(".m3u8")) {
                    HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                } else {
                    ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                }
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
            }
        }
    }

    // FIX #1: Immediately prepare & start player whenever item.videoUrl arrives (from prefetch or on-demand).
    // item.videoUrl and item.streamType are plain vals reading directly from the ShortsItem,
    // so this LaunchedEffect fires the moment the ViewModel pushes a new URL into the StateFlow.
    LaunchedEffect(currentVideoUrl, currentStreamType) {
        if (!currentVideoUrl.isNullOrBlank()) {
            // Reset retry flag so the new stream URL can be retried if it also expires
            hasAttemptedRefresh = false
            prepareMediaSource(currentVideoUrl, currentStreamType ?: "MP4")
            // If this Short is already the active one, start playing immediately
            if (isActive) {
                exoPlayer.playWhenReady = true
            }
        }
    }

    LaunchedEffect(isActive, isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
        if (isActive) {
            exoPlayer.playWhenReady = true
        } else {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> isLoading = true
                    Player.STATE_READY -> {
                        isLoading = false
                        isPlaying = exoPlayer.isPlaying
                        if (isActive) {
                            exoPlayer.play()
                        }
                    }
                    Player.STATE_ENDED -> isLoading = false
                    Player.STATE_IDLE -> isLoading = false
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                val errorCode = error.errorCode
                val message = error.message.orEmpty()
                val isStaleStream = errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ||
                        message.contains("403") || message.contains("410")

                if (isStaleStream && !hasAttemptedRefresh) {
                    hasAttemptedRefresh = true
                    isLoading = true
                    onRefreshStream(item.id) { newUrl ->
                        if (!newUrl.isNullOrBlank()) {
                            prepareMediaSource(newUrl, currentStreamType ?: "MP4")
                            exoPlayer.seekTo(0)
                            exoPlayer.playWhenReady = true
                        } else {
                            isUnavailable = true
                            isLoading = false
                        }
                    }
                } else {
                    isUnavailable = true
                    isLoading = false
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable {
                if (isActive) {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                }
            },
    ) {
        val imagePoster = item.posterUrl ?: item.thumbnailUrl

        if (!isPlaying && !imagePoster.isNullOrBlank() && !isUnavailable) {
            AsyncImage(
                model = imagePoster,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exoPlayer
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (isLoading && !isUnavailable) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (!isPlaying && !isLoading && !isUnavailable) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Paused",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.Center),
            )
        }

        if (isUnavailable) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Unavailable",
                        tint = Color.Red,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = "Video Unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = "The stream URL expired or is unreachable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
