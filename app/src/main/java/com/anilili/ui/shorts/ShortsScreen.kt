package com.anilili.ui.shorts

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anilili.data.model.ShortsItem
import com.anilili.ui.components.ErrorBox
import com.anilili.ui.components.LoadingBox
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.OutlinedButton

@Composable
fun ShortsScreen(
    onWatchAnime: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    viewModel: ShortsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var isMuted by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            is ShortsUiState.Loading -> {
                LoadingBox(modifier = Modifier.fillMaxSize())
            }
            is ShortsUiState.NoApiKey -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "YouTube API Key Required",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = "Please enter your YouTube Data API key in Settings to watch Shorts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray,
                            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { viewModel.loadFeed(reset = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry")
                            }
                            if (onOpenSettings != null) {
                                OutlinedButton(
                                    onClick = onOpenSettings,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                ) {
                                    Text("Open Settings")
                                }
                            }
                        }
                    }
                }
            }
            is ShortsUiState.NoBackend -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Shorts API URL Not Configured",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = "Please configure the Shorts Backend API URL in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray,
                            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { viewModel.loadFeed(reset = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry")
                            }
                            if (onOpenSettings != null) {
                                OutlinedButton(
                                    onClick = onOpenSettings,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                ) {
                                    Text("Open Settings")
                                }
                            }
                        }
                    }
                }
            }
            is ShortsUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Could Not Load Shorts",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray,
                            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { viewModel.loadFeed(reset = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry")
                            }
                            if (onOpenSettings != null) {
                                OutlinedButton(
                                    onClick = onOpenSettings,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                ) {
                                    Text("Open Settings")
                                }
                            }
                        }
                    }
                }
            }
            is ShortsUiState.Loaded -> {
                if (s.items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No Shorts Available",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = "Verify your backend URL & API key in Settings, or try reloading.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.LightGray,
                                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { viewModel.loadFeed(reset = true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry")
                                }
                                if (onOpenSettings != null) {
                                    OutlinedButton(
                                        onClick = onOpenSettings,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    ) {
                                        Text("Open Settings")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val pagerState = rememberPagerState(pageCount = { s.items.size })

                    LaunchedEffect(pagerState.currentPage) {
                        viewModel.onPageChanged(pagerState.currentPage)
                        if (pagerState.currentPage >= s.items.size - 2) {
                            viewModel.loadFeed(reset = false)
                        }
                    }

                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                        key = { index -> s.items[index].id },
                    ) { pageIndex ->
                        val item = s.items[pageIndex]
                        val isActive = pagerState.currentPage == pageIndex

                        Box(modifier = Modifier.fillMaxSize()) {
                            ShortsPlayerItem(
                                item = item,
                                isActive = isActive,
                                isMuted = isMuted,
                                onRefreshStream = { shortId, callback ->
                                    viewModel.refreshStream(shortId) { resp ->
                                        callback(resp?.videoUrl)
                                    }
                                },
                            )

                            // Bottom gradient scrim
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            1f to Color.Black.copy(alpha = 0.85f),
                                        ),
                                    ),
                            )

                            // Right-side Action Controls
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 80.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                // Like Button
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val isLiked = item.userReaction == "LIKE"
                                    IconButton(
                                        onClick = { viewModel.toggleReaction(item.id, "LIKE") },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.4f)),
                                    ) {
                                        Icon(
                                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Like",
                                            tint = if (isLiked) Color.Red else Color.White,
                                        )
                                    }
                                    Text(
                                        text = formatCount(item.likeCount),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }

                                // Dislike Button
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val isDisliked = item.userReaction == "DISLIKE"
                                    IconButton(
                                        onClick = { viewModel.toggleReaction(item.id, "DISLIKE") },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.4f)),
                                    ) {
                                        Icon(
                                            imageVector = if (isDisliked) Icons.Default.ThumbDown else Icons.Default.ThumbDownOffAlt,
                                            contentDescription = "Dislike",
                                            tint = if (isDisliked) MaterialTheme.colorScheme.primary else Color.White,
                                        )
                                    }
                                    Text(
                                        text = formatCount(item.dislikeCount),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }

                                // Share Button
                                IconButton(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, item.youtubeUrl)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Short"))
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.4f)),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = Color.White,
                                    )
                                }

                                // Mute Button
                                IconButton(
                                    onClick = { isMuted = !isMuted },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.4f)),
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                                        contentDescription = "Mute Toggle",
                                        tint = Color.White,
                                    )
                                }
                            }

                            // Bottom-left details & action
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(0.75f)
                                    .padding(start = 16.dp, bottom = 32.dp),
                            ) {
                                if (!item.channelName.isNullOrBlank()) {
                                    Text(
                                        text = "@${item.channelName}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                    )
                                }

                                Text(
                                    text = item.title,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )

                                val canWatch = item.anilistId != null && item.resolutionConfidence >= 0.75f
                                if (canWatch && item.anilistId != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            val ep = item.episodeNumber ?: 1
                                            onWatchAnime(item.anilistId, ep)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = Color.Black,
                                        ),
                                        shape = RoundedCornerShape(20.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val seriesText = item.seriesTitle ?: "Full Episode"
                                        val epText = item.episodeNumber?.let { " Ep $it" } ?: ""
                                        Text(
                                            text = "Watch $seriesText$epText",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
