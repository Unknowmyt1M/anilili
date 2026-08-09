package com.anilili.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The shared touch-player chrome: a center transport cluster (prev / −10s / play-pause / +10s /
 * next) over a bottom bar (seek slider, elapsed/total time, and a caller-supplied icon row). Both
 * the native and embed players render this so they are visually identical; each supplies its own
 * wiring and its own trailing icons (the embed has only a settings gear, the native adds subtitle,
 * cast, and fullscreen).
 */
@Composable
internal fun PlayerControlsScaffold(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long = 0L,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    seriesTitle: String? = null,
    episodeTitle: String? = null,
    onBack: (() -> Unit)? = null,
    autoplay: Boolean = false,
    onToggleAutoplay: (() -> Unit)? = null,
    onInteract: () -> Unit = {},
    topRightIcons: @Composable RowScope.() -> Unit = {},
    bottomRightIcons: @Composable RowScope.() -> Unit = {},
) {
    var isLocked by remember { mutableStateOf(false) }

    if (isLocked) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { onInteract() },
            contentAlignment = Alignment.BottomStart,
        ) {
            IconButton(
                onClick = { isLocked = false },
                modifier = Modifier
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Lock,
                    contentDescription = "Unlock controls",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        return
    }

    Box(modifier.fillMaxSize()) {
        // ── YouTube Top Bar (Header) ──
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)),
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                onBack?.let { backAction ->
                    IconButton(onClick = backAction) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Collapse player",
                            tint = Color.White,
                        )
                    }
                }
                if (!seriesTitle.isNullOrBlank()) {
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(
                            text = seriesTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (!episodeTitle.isNullOrBlank()) {
                            Text(
                                text = episodeTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // YouTube Autoplay Switch Pill
                onToggleAutoplay?.let { toggle ->
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape)
                            .clickable(onClick = toggle)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (autoplay) "Autoplay ON" else "Autoplay OFF",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (autoplay) Color(0xFFFF0000) else Color.White.copy(alpha = 0.8f),
                        )
                    }
                }
                IconButton(onClick = { isLocked = true }) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.LockOpen,
                        contentDescription = "Lock controls",
                        tint = Color.White,
                    )
                }
                topRightIcons()
            }
        }

        // ── YouTube Center Transport Cluster ──
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlIconButton("Previous episode", Icons.Default.SkipPrevious, enabled = hasPrevious, onClick = onPrevious)
            PlayerControlIconButton("Rewind 10 seconds", Icons.Default.FastRewind, onClick = onRewind)

            // YouTube Big Central Play/Pause Button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(68.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }

            PlayerControlIconButton("Forward 10 seconds", Icons.Default.FastForward, onClick = onForward)
            PlayerControlIconButton("Next episode", Icons.Default.SkipNext, enabled = hasNext, onClick = onNext)
        }

        // ── YouTube Bottom Bar (Red Scrubber & Controls) ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // YouTube 3-Layer Scrubber Bar (Played = Red, Loaded/Buffered = Semi-Transparent White, Unloaded = Dark)
            YouTubeScrubberBar(
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
                onSeek = onSeek,
                onInteract = onInteract,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${formatPlayerTime(positionMs)} / ${formatPlayerTime(durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Row(verticalAlignment = Alignment.CenterVertically, content = bottomRightIcons)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun YouTubeScrubberBar(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onInteract: () -> Unit = {},
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val playedFraction = scrubFraction
        ?: if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (durationMs > 0L) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // YouTube 3-Layer Scrubber Canvas (Thin 3dp line)
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .padding(horizontal = 6.dp)
        ) {
            val width = size.width
            val height = size.height
            val radius = height / 2f

            // 1. Total Unbuffered Track (Dark Gray Translucent Line)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f),
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            )

            // 2. Buffered / Loaded Track (Light Gray / White Highlight)
            if (bufferedFraction > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.6f),
                    size = androidx.compose.ui.geometry.Size(width * bufferedFraction, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                )
            }

            // 3. Played Track (YouTube Red #FF0000)
            if (playedFraction > 0f) {
                drawRoundRect(
                    color = Color(0xFFFF0000),
                    size = androidx.compose.ui.geometry.Size(width * playedFraction, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                )
            }
        }

        // YouTube Touch Slider Overlay with Round Circle Red Dot Thumb
        Slider(
            value = playedFraction,
            onValueChange = {
                if (durationMs > 0L) scrubFraction = it
                onInteract()
            },
            onValueChangeFinished = {
                scrubFraction?.let { onSeek((it * durationMs).toLong()) }
                scrubFraction = null
            },
            enabled = durationMs > 0L,
            thumb = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color(0xFFFF0000), CircleShape)
                )
            },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(0xFFFF0000),
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun PlayerControlIconButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
        )
    }
}

internal fun formatPlayerTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
