package com.anilili.ui.watch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.anilili.data.ProviderCatalog
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.playback.EpisodeDownload
import com.anilili.playback.EpisodeDownloadMetadata
import com.anilili.playback.EpisodeDownloadState
import com.anilili.playback.EpisodeDownloadSubtitle
import com.anilili.playback.BulkDownloadOutcome
import com.anilili.playback.BulkEpisodeDownloads
import com.anilili.playback.DownloadStorage
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.EpisodeDownloadUi
import com.anilili.playback.EpisodeExport
import com.anilili.playback.episodeDownloadBadges
import com.anilili.ui.components.DownloadCoverBadge
import com.anilili.ui.components.AnimeListStatusDialog
import com.anilili.playback.OfflineEpisode
import com.anilili.playback.offlineEpisodes
import com.anilili.playback.PlaybackService
import com.anilili.data.settings.DownloadDestination
import com.anilili.data.settings.DownloadQuality
import com.anilili.data.settings.EpisodeLayout
import com.anilili.data.settings.SettingsStore
import com.anilili.ui.UiState
import com.anilili.ui.components.EPISODE_BROWSER_MIN_EPISODES
import com.anilili.ui.components.EpisodeBrowserBar
import com.anilili.ui.components.EpisodeNumberChip
import com.anilili.ui.components.EpisodeArtwork
import com.anilili.ui.components.ErrorBox
import com.anilili.ui.components.LoadingBox
import com.anilili.ui.components.blockIndexContaining
import com.anilili.ui.components.episodeBlocks
import com.anilili.ui.components.episodeWatchFraction
import com.anilili.ui.components.episodeArtworkImage
import com.anilili.ui.components.filterEpisodes
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun WatchScreen(
    animeId: Int,
    provider: String,
    category: String,
    episode: String,
    showEpisodeListInitially: Boolean = false,
    inPictureInPicture: Boolean = false,
    onPictureInPictureReadyChanged: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    onOpenAnime: () -> Unit,
    vm: WatchViewModel = viewModel(),
) {
    LaunchedEffect(animeId, provider, category, episode) {
        DiagnosticsLog.event("WatchScreen composed id=$animeId provider=$provider category=$category episode=$episode")
        vm.start(animeId, provider, category, episode)
    }
    val state by vm.state.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    var webFallback by remember { mutableStateOf(false) }
    // TV plays fullscreen from the start; Back drops to the episode/source screen.
    var fullscreen by remember(animeId, showEpisodeListInitially, device.isTv) {
        mutableStateOf(device.isTv && !showEpisodeListInitially)
    }
    val activity = remember(context) { context.findActivity() }
    val currentOnBack by rememberUpdatedState(onBack)
    var embeddedPlaybackStopper by remember { mutableStateOf<(() -> Unit)?>(null) }
    val currentEmbeddedPlaybackStopper by rememberUpdatedState(embeddedPlaybackStopper)
    val pictureInPictureReady = state is UiState.Success
    DisposableEffect(pictureInPictureReady) {
        onPictureInPictureReadyChanged(pictureInPictureReady)
        onDispose {
            if (pictureInPictureReady) onPictureInPictureReadyChanged(false)
        }
    }
    // YouTube-style leave: native playback PAUSES (media stays loaded, so the notification can
    // resume it and re-entering the episode continues in place); the position is committed past
    // the periodic-save throttle so "continue watching" lands exactly where the user left off.
    // Embeds still stop — their WebView dies with this screen, so there is nothing to resume.
    val pauseAndBack = remember {
        {
            currentEmbeddedPlaybackStopper?.invoke()
            vm.commitPlaybackPosition()
            PlaybackService.pauseActivePlayback()
            currentOnBack()
        }
    }

    LaunchedEffect(webFallback) {
        DiagnosticsLog.event("WatchScreen webFallback=$webFallback")
        if (webFallback) PlaybackService.stopActivePlayback()
    }

    LaunchedEffect(state, webFallback) {
        when (val s = state) {
            is UiState.Loading -> {
                delay(10_000)
                if (state is UiState.Loading && !webFallback) {
                    DiagnosticsLog.event("WatchScreen still loading after 10000ms id=$animeId provider=$provider")
                }
            }
            is UiState.Error -> DiagnosticsLog.event("WatchScreen error visible message=${s.message.take(160)}")
            is UiState.Success -> {
                val stream = s.data.chosenStream
                if (stream?.quality.equals("Downloaded", ignoreCase = true) ||
                    stream?.url?.startsWith("file:", ignoreCase = true) == true ||
                    stream?.url?.startsWith("content:", ignoreCase = true) == true
                ) {
                    fullscreen = true
                }
                DiagnosticsLog.event(
                    "WatchScreen success visible provider=${s.data.provider} episode=${s.data.current.displayNumber} " +
                        "stream=${stream?.let { if (it.isEmbed) "embed" else if (it.isHls) "hls" else "direct" } ?: "none"} " +
                        "resolving=${s.data.isResolving}",
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (device.isTv) releaseImageMemoryForPlayback(context)
    }

    // Drive orientation + system bars from the fullscreen flag; restore on leave.
    DisposableEffect(fullscreen, device.isTv) {
        val window = activity?.window
        if (activity != null && window != null) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (fullscreen) {
                if (!device.isTv) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                if (!device.isTv) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val w = activity?.window
            if (activity != null && w != null) {
                if (!device.isTv) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Back exits fullscreen first, then the screen.
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen) { pauseAndBack() }

    // Pause however the screen is left (back, Anime page, notification nav) — not just the
    // Back gesture. PiP keeps this screen composed, so picture-in-picture is unaffected.
    // Pause rather than stop: the loaded session powers the notification's resume button,
    // and background media-button events are suppressed so it can't restart by itself.
    DisposableEffect(Unit) {
        onDispose {
            currentEmbeddedPlaybackStopper?.invoke()
            vm.commitPlaybackPosition()
            PlaybackService.pauseActivePlayback()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (webFallback) {
            EmbedWebView(
                url = "https://www.miruro.to/info/$animeId",
                referer = "https://www.miruro.to/",
                modifier = Modifier.fillMaxSize(),
                onFullscreenChanged = { fullscreen = it },
                onProgress = vm::onProgress,
                onPlaybackStopperChanged = { embeddedPlaybackStopper = it },
            )
            BackButton(pauseAndBack, Modifier.align(Alignment.TopStart))
            return@Box
        }

        when (val s = state) {
            is UiState.Loading -> {
                // Only surface the "this can take a moment" note once loading is genuinely slow,
                // so it doesn't flash by on the common fast (Miruro-first) path.
                var showSlowNote by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(1_500)
                    showSlowNote = true
                }
                val loadingStatus by vm.loadingStatus.collectAsState()
                LoadingBox(
                    message = loadingStatus ?: if (showSlowNote) {
                        "Finding a source for this episode.\n" +
                            "The first playable server starts now; more options are checked after playback begins."
                    } else {
                        null
                    },
                )
                BackButton(pauseAndBack, Modifier.align(Alignment.TopStart))
            }
            is UiState.Error -> Column(Modifier.fillMaxSize()) {
                ErrorBox(s.message, onRetry = vm::retry, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { webFallback = true },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 24.dp)
                        .focusHighlight(RoundedCornerShape(20.dp)),
                ) { Text("Open in web player") }
                BackButton(pauseAndBack, Modifier.align(Alignment.Start))
            }
            is UiState.Success -> {
                val saved = watchlist.any { it.anilistId == s.data.anilistId }
                WatchContent(
                    data = s.data,
                    fullscreen = fullscreen || inPictureInPicture,
                    saved = saved,
                    onToggleSaved = {
                        LibraryStore.toggleWatchlist(
                            WatchlistEntry(
                                anilistId = s.data.anilistId,
                                title = s.data.seriesTitle,
                                cover = s.data.artworkUrl,
                                format = s.data.seriesFormat,
                                averageScore = s.data.averageScore,
                            ),
                        )
                    },
                    onBack = pauseAndBack,
                    onOpenAnime = onOpenAnime,
                    onPrev = vm::prev,
                    onNext = vm::next,
                    onChangeSource = vm::changeSource,
                    onChangeCategory = vm::changeCategory,
                    onRequestAllSources = vm::validateRemainingSources,
                    onRetryMiruroSources = vm::retryMiruroServers,
                    onSelectEpisode = { index ->
                        if (device.isTv) fullscreen = true
                        vm.playIndex(index)
                    },
                    onWebFallback = { webFallback = true },
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onFullscreenChanged = { fullscreen = it },
                    onProgress = vm::onProgress,
                    onPlaybackError = vm::onPlaybackError,
                    onPlaybackStopperChanged = { embeddedPlaybackStopper = it },
                    onPlayerClosed = vm::commitPlaybackPosition,
                    onDownloadSeries = { episodes, quality ->
                        BulkEpisodeDownloads.start(
                            context = context,
                            anilistId = s.data.anilistId,
                            seriesTitle = s.data.seriesTitle,
                            artworkUrl = s.data.artworkUrl,
                            category = s.data.category,
                            preferredProvider = s.data.provider,
                            episodes = episodes,
                            catalog = vm.episodeCatalog(),
                            quality = quality,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun WatchContent(
    data: WatchData,
    fullscreen: Boolean,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    onBack: () -> Unit,
    onOpenAnime: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onRequestAllSources: () -> Unit,
    onRetryMiruroSources: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onWebFallback: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onProgress: (String, Long, Long) -> Unit,
    onPlaybackError: (String, String, Long) -> Unit,
    onPlaybackStopperChanged: (((() -> Unit)?) -> Unit)? = null,
    onPlayerClosed: () -> Unit = {},
    /** Starts a batch download of [episodes]; the catalog it needs lives with the ViewModel. */
    onDownloadSeries: (List<EpisodeItem>, DownloadQuality) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    var listStatusDialogVisible by remember(data.anilistId) { mutableStateOf(false) }
    val downloads by EpisodeDownloads.downloads(context).collectAsState()
    val exportedEpisodes by EpisodeExport.exported(context).collectAsState()
    val preparingIds by EpisodeDownloads.preparingIds.collectAsState()
    val downloadId = EpisodeDownloads.idFor(
        data.anilistId,
        data.category.api,
        data.current.displayNumber,
    )
    val episodeDownload = downloads.firstOrNull { it.id == downloadId }
    // The chosen stream may be an embed (Bonk, Kiwi) that cannot be saved; fall back to
    // any natively downloadable stream so the episode stays downloadable either way.
    val streamForDownload = data.chosenStream?.takeIf(EpisodeDownloads::canDownload)
        ?: data.sources.streams.firstOrNull(EpisodeDownloads::canDownload)
    val canSaveToDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        EpisodeDownloads.canSaveToDevice(streamForDownload)
    val downloadPreparing = downloadId in preparingIds
    val defaultDownloadQuality by SettingsStore.downloadQuality.collectAsState()
    val defaultDownloadDestination by SettingsStore.downloadDestination.collectAsState()
    var downloadDialogVisible by remember(
        data.anilistId,
        data.category,
        data.current.pipeId,
        data.provider,
    ) { mutableStateOf(false) }

    if (listStatusDialogVisible) {
        AnimeListStatusDialog(
            anilistId = data.anilistId,
            title = data.seriesTitle,
            episodeLabel = "Episode ${data.current.displayNumber}" +
                (data.current.distinctTitle?.let { ": $it" } ?: ""),
            onDismiss = { listStatusDialogVisible = false },
        )
    }
    var selectedDownloadQuality by remember { mutableStateOf(defaultDownloadQuality) }
    // 1 means just the episode on screen; anything larger is a bounded batch.
    var downloadBatchSize by remember { mutableIntStateOf(1) }
    // Everything from the current episode onward that is not already saved — the batch a viewer
    // starting partway through actually wants, rather than re-fetching what they have.
    val remainingEpisodes = remember(
        data.episodes,
        data.current.number,
        data.anilistId,
        data.category,
        downloads,
        exportedEpisodes,
    ) {
        BulkEpisodeDownloads.pendingEpisodes(
            episodes = data.episodes,
            anilistId = data.anilistId,
            category = data.category.api,
            fromNumber = data.current.number,
            // Exported episodes count as held too. With the cache copy dropped after conversion,
            // going by the download index alone would offer to re-fetch a season already saved.
            alreadyHave = offlineEpisodes(downloads, exportedEpisodes)
                .filter(OfflineEpisode::isPlayable)
                .map(OfflineEpisode::id)
                .toSet(),
        )
    }
    var selectedDownloadDestination by remember { mutableStateOf(defaultDownloadDestination) }
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingDownloadAction
        pendingDownloadAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(
                context,
                "Notification permission is needed for background downloads.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val queueCurrentEpisode: (DownloadQuality, DownloadDestination) -> Unit = queue@{ quality, destination ->
        streamForDownload?.let { stream ->
            val selectedStream = selectDownloadStream(
                current = stream,
                available = data.sources.streams,
                quality = quality,
            )
            val effectiveDestination = if (canSaveToDevice) {
                destination
            } else {
                DownloadDestination.APP_ONLY
            }
            val metadata = EpisodeDownloadMetadata(
                anilistId = data.anilistId,
                seriesTitle = data.seriesTitle,
                episodeNumber = data.current.displayNumber,
                episodeTitle = data.current.title,
                artworkUrl = data.artworkUrl,
                provider = data.provider,
                category = data.category.api,
                referer = selectedStream.referer,
                headers = selectedStream.headers,
                subtitles = data.sources.subtitles.map { subtitle ->
                    EpisodeDownloadSubtitle(
                        url = subtitle.url,
                        label = subtitle.label,
                        language = subtitle.language,
                    )
                },
            )
            val appDownloadCanStart = episodeDownload == null ||
                episodeDownload.state == EpisodeDownloadState.FAILED
            // Downloads-folder copies always come from a library download that is then rewrapped,
            // so the cache download runs even when the viewer asked for Downloads alone.
            val exportsToDevice = effectiveDestination.includesDevice
            val needsAppDownload = effectiveDestination.includesApp || exportsToDevice
            val enqueue = {
                if (exportsToDevice) {
                    EpisodeExport.request(
                        context = context,
                        downloadId = downloadId,
                        // "Device Downloads" means don't leave behind a library copy *this action*
                        // created. An episode that was already in the library stays there — the
                        // dialog preselects this destination for those, and silently deleting a
                        // download the viewer already had is not what they asked for.
                        deleteAppCopyAfter = !effectiveDestination.includesApp && appDownloadCanStart,
                    )
                }
                if (needsAppDownload && appDownloadCanStart) {
                    EpisodeDownloads.enqueue(
                        context = context,
                        metadata = metadata,
                        stream = selectedStream,
                        quality = quality,
                    ) { appResult ->
                        val message = when {
                            appResult.isFailure -> appResult.exceptionOrNull()?.message
                                ?: "Could not start the download."
                            exportsToDevice && effectiveDestination.includesApp ->
                                "Episode added to Anilili. The MP4 is written to Downloads once it finishes."
                            exportsToDevice ->
                                "Downloading now. The MP4 is written to Downloads once it finishes."
                            else -> "Episode added to Anilili downloads."
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                } else {
                    val message = when {
                        exportsToDevice && episodeDownload?.isComplete == true ->
                            "Saving this episode to Downloads as MP4."
                        exportsToDevice ->
                            "Already downloading. The MP4 follows once it finishes."
                        effectiveDestination.includesApp -> "This episode is already in Anilili downloads."
                        else -> "Could not start the download."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            if (
                needsAppDownload &&
                appDownloadCanStart &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownloadAction = enqueue
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                enqueue()
            }
        }
        Unit
    }
    val showDownloadDialog: () -> Unit = {
        selectedDownloadQuality = defaultDownloadQuality
        selectedDownloadDestination = if (canSaveToDevice) {
            if (
                episodeDownload?.state == EpisodeDownloadState.COMPLETED &&
                defaultDownloadDestination == DownloadDestination.APP_ONLY
            ) {
                DownloadDestination.DEVICE_ONLY
            } else {
                defaultDownloadDestination
            }
        } else {
            DownloadDestination.APP_ONLY
        }
        downloadDialogVisible = true
    }

    if (downloadDialogVisible && streamForDownload != null) {
        EpisodeDownloadDialog(
            stream = streamForDownload,
            quality = selectedDownloadQuality,
            destination = selectedDownloadDestination,
            canSaveToDevice = canSaveToDevice,
            alreadyInApp = episodeDownload?.state == EpisodeDownloadState.COMPLETED,
            alreadyInDownloads = exportedEpisodes.any { it.downloadId == downloadId },
            onQualityChange = { selectedDownloadQuality = it },
            onDestinationChange = { selectedDownloadDestination = it },
            batchSize = downloadBatchSize,
            onBatchSizeChange = { downloadBatchSize = it },
            remainingCount = remainingEpisodes.size,
            onDismiss = { downloadDialogVisible = false },
            onConfirm = {
                val quality = selectedDownloadQuality
                val destination = selectedDownloadDestination
                val batch = downloadBatchSize
                downloadDialogVisible = false
                if (batch > 1 && remainingEpisodes.isNotEmpty()) {
                    onDownloadSeries(remainingEpisodes.take(batch), quality)
                } else {
                    queueCurrentEpisode(quality, destination)
                }
            },
        )
    }
    val summaryFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    val tvEpisodeListState = rememberLazyListState()
    var hasShownFullscreen by remember { mutableStateOf(fullscreen) }

    // TV: leaving fullscreen must hand focus to the episode/source area — otherwise it can
    // stay inside the player (or an embed WebView) and the D-pad never reaches the list.
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            hasShownFullscreen = true
        } else if (device.isTv) {
            // Start the remote in the episode summary, and return it there after fullscreen.
            // This avoids Android player views becoming a second, invisible D-pad focus owner.
            delay(if (hasShownFullscreen) 180 else 950)
            runCatching { summaryFocus.requestFocus() }
                .onSuccess { DiagnosticsLog.event("Watch TV selector focus requested") }
                .onFailure { DiagnosticsLog.throwable("Watch TV selector focus failed", it) }
        }
    }

    LaunchedEffect(data.currentIndex, fullscreen, device.isTv) {
        if (device.isTv && !fullscreen) {
            // Native player focus can briefly bring a lower control into view during load.
            // Restore the summary as the visual anchor once that handoff settles.
            val returningFromFullscreen = hasShownFullscreen
            delay(if (returningFromFullscreen) 260 else 850)
            tvEpisodeListState.scrollToItem(0)
            if (!returningFromFullscreen) {
                // The initial summary focus request lands just after the first reset; undo the
                // small bring-into-view offset it can apply to the title line.
                delay(300)
                tvEpisodeListState.scrollToItem(0)
            }
        }
    }

    // Fullscreen draws edge-to-edge; otherwise the player must stay clear of the status bar so
    // the clock/battery icons never overlap the video, and its size must come from the actual
    // constraints this screen was given (not LocalConfiguration, which ignores multi-window and
    // navigation rails) so the video always fits the space it really has.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(if (fullscreen) Modifier else Modifier.statusBarsPadding()),
    ) {
        val availableWidthDp = maxWidth
        val availableHeightDp = maxHeight
        Column(Modifier.fillMaxSize()) {
        val playerModifier = if (fullscreen) {
            Modifier.fillMaxSize()
        } else {
            val naturalHeight = availableWidthDp * 9f / 16f
            val landscape = availableWidthDp > availableHeightDp
            val maxHeightFraction = when {
                device.isTv -> 0.56f
                landscape -> 0.66f
                else -> 0.62f
            }
            val playerHeight = minOf(naturalHeight, availableHeightDp * maxHeightFraction)
            Modifier.fillMaxWidth().height(playerHeight)
        }
        // In the TV grid the Android player/WebView must not join the Compose focus graph: some
        // embeds retain native focus even after the ring moves, producing two remote owners.
        // Fullscreen remains available as an explicit selector below the summary.
        val playerFocusModifier = if (device.isTv && !fullscreen) {
            Modifier
                .semantics { contentDescription = "Video player" }
        } else {
            Modifier
        }
        Box(playerModifier.then(playerFocusModifier).background(Color.Black)) {
            val stream = data.chosenStream
            // Key on the KIND of player only, never per-episode/per-url: recreating PlayerSurface
            // for every Next tore down and rebuilt the PlayerView + MediaController each episode
            // (the new view was even created before the old one released). On weak TV hardware
            // (Fire TV's OMX.MS.AVC decoder, 192MB heap) that churn froze the UI for seconds and
            // could wedge the video decoder while audio played on. Both PlayerSurface and
            // EmbedWebView already handle stream-url changes internally, reusing the surface,
            // codec chain, and controller connection.
            val playerKind = when {
                device.isTv && !fullscreen -> "tv-idle"
                stream == null -> "none"
                stream.isEmbed || ProviderCatalog.isEmbed(data.provider) -> "embed"
                else -> "native"
            }
            key(playerKind) {
                when {
                    device.isTv && !fullscreen -> {
                        // Keep native PlayerView/WebView focus out of the TV episode grid: the
                        // player only exists in fullscreen. Leaving fullscreen stops playback, so
                        // first bank the position it reached — every resume path reads it.
                        LaunchedEffect(stream?.url) {
                            PlaybackService.stopActivePlayback()
                            onPlayerClosed()
                        }
                        // A real focusable play button (not a decoration): Fire TV users expect
                        // to click play here to continue watching, which re-enters fullscreen.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .semantics {
                                        contentDescription = "Play"
                                        onClick(label = "Play") {
                                            onToggleFullscreen()
                                            true
                                        }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        val keyCode = event.nativeKeyEvent.keyCode
                                        val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                                        if (!activate) {
                                            false
                                        } else {
                                            if (event.type == KeyEventType.KeyUp) onToggleFullscreen()
                                            true
                                        }
                                    }
                                    .focusHighlight(CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .clickable(onClick = onToggleFullscreen)
                                    .focusable()
                                    .size(72.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(46.dp),
                                )
                            }
                        }
                    }
                    stream == null -> NoSource(onWebFallback)
                    stream.isEmbed || ProviderCatalog.isEmbed(data.provider) ->
                        Box(Modifier.fillMaxSize()) {
                            LaunchedEffect(stream.url) { PlaybackService.stopActivePlayback() }
                            EmbedEpisodeNavigationEffect(
                                hasPrevious = data.hasPrev,
                                hasNext = data.hasNext,
                                onPrevious = onPrev,
                                onNext = onNext,
                            )
                            EmbedWebView(
                                url = stream.url,
                                referer = stream.referer,
                                modifier = Modifier.fillMaxSize(),
                                qualityStreams = data.sources.embedStreams,
                                startPositionMs = data.startPositionMs,
                                skip = data.sources.skip,
                                skipTimingStatus = data.skipTimingStatus,
                                onPreviousEpisode = onPrev,
                                onNextEpisode = onNext,
                                hasPreviousEpisode = data.hasPrev,
                                hasNextEpisode = data.hasNext,
                                focusPlayerOnStart = fullscreen,
                                isFullscreen = fullscreen,
                                onToggleFullscreen = onToggleFullscreen,
                                onFullscreenChanged = onFullscreenChanged,
                                onProgress = onProgress,
                                onPlaybackError = onPlaybackError.takeIf { data.provider == "allanime" },
                                onPlaybackStopperChanged = onPlaybackStopperChanged,
                                episodes = data.episodes,
                                currentIndex = data.currentIndex,
                                artworkUrl = data.artworkUrl,
                                seriesTitle = data.seriesTitle,
                                episodeTitle = data.current.distinctTitle
                                    ?: "Episode ${data.current.displayNumber}",
                                onSelectEpisode = onSelectEpisode,
                                onAddToList = { listStatusDialogVisible = true },
                            )
                            // Embed players often use CSS "web fullscreen" that never reaches the
                            // WebView fullscreen callback, so the app provides its own toggle. On
                            // touch it lives in the player's own control bar; a remote has no
                            // pointer to summon that bar with while the video owns focus, so TV
                            // keeps a corner button of its own.
                            if (device.isTv) IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .focusProperties { canFocus = fullscreen }
                                    .statusBarsPadding()
                                    .padding(4.dp)
                                    .focusHighlight(RoundedCornerShape(24.dp)),
                            ) {
                                Icon(
                                    if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen",
                                    tint = Color.White.copy(alpha = 0.85f),
                                )
                            }
                        }
                    else -> PlayerSurface(
                        stream = stream,
                        qualityStreams = data.sources.streams.filterNot(StreamItem::isEmbed),
                        subtitles = data.sources.subtitles,
                        subtitleOffsetMs = data.sources.subtitleOffsetMs,
                        skip = data.sources.skip,
                        skipTimingStatus = data.skipTimingStatus,
                        seriesTitle = data.seriesTitle,
                        episodeTitle = "Episode ${data.current.displayNumber}" +
                            (data.current.title?.let { ": $it" } ?: ""),
                        artworkUrl = data.artworkUrl,
                        animeId = data.anilistId,
                        provider = data.provider,
                        category = data.category.api,
                        episode = data.current.displayNumber,
                        onEnded = { if (com.anilili.data.settings.SettingsStore.autoplay.value) onNext() },
                        onNextEpisode = onNext,
                        onError = onPlaybackError,
                        modifier = Modifier.fillMaxSize(),
                        onToggleFullscreen = onToggleFullscreen,
                        startPositionMs = data.startPositionMs,
                        onProgress = onProgress,
                        onPreviousEpisode = onPrev,
                        hasNextEpisode = data.hasNext,
                        hasPreviousEpisode = data.hasPrev,
                        focusPlayerOnStart = fullscreen,
                        isFullscreen = fullscreen,
                        episodes = data.episodes,
                        currentIndex = data.currentIndex,
                        onSelectEpisode = onSelectEpisode,
                        onAddToList = { listStatusDialogVisible = true },
                    )
                }
            }
            if (data.isResolving) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    com.anilili.ui.components.WaterFillLogoIndicator(size = 72.dp)
                }
            }
            if (!fullscreen) BackButton(onBack, Modifier.align(Alignment.TopStart))
        }

        if (fullscreen) return@Column

        if (!device.isTv) {
            MobileWatchDetails(
                data = data,
                saved = saved,
                onToggleSaved = onToggleSaved,
                onOpenAnime = onOpenAnime,
                episodeDownload = episodeDownload,
                downloadPreparing = downloadPreparing,
                canDownload = streamForDownload != null,
                canSaveToDevice = canSaveToDevice,
                onDownload = showDownloadDialog,
                focusRequester = sourceFocus,
                onChangeSource = onChangeSource,
                onChangeCategory = onChangeCategory,
                onRequestAllSources = onRequestAllSources,
                onRetryMiruroSources = onRetryMiruroSources,
                onSelectEpisode = onSelectEpisode,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            return@Column
        }

        // Browsing state for long-runners. A null range means "not chosen yet", which opens on
        // the block holding the episode that is actually playing.
        var episodeQuery by remember(data.anilistId) { mutableStateOf("") }
        var chosenBlockIndex by remember(data.anilistId) { mutableStateOf<Int?>(null) }
        val blocks = remember(data.episodes) { episodeBlocks(data.episodes) }
        // Episodes are selected by their index in the full list, so filtering has to keep a way
        // back to it. pipeId is already the list key, so it is unique by contract.
        val indexByPipeId = remember(data.episodes) {
            data.episodes.withIndex().associate { (index, episode) -> episode.pipeId to index }
        }
        val tvBlockIndex = (chosenBlockIndex ?: blockIndexContaining(blocks, data.current.number))
            .coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
        val tvShownEpisodes = if (episodeQuery.isNotBlank()) {
            filterEpisodes(data.episodes, episodeQuery)
        } else {
            blocks.getOrNull(tvBlockIndex)?.episodes.orEmpty()
        }
        val episodeRows = remember(tvShownEpisodes, device.episodeColumns) {
            tvShownEpisodes.chunked(device.episodeColumns)
        }
        val tvBrowserVisible = data.episodes.size > EPISODE_BROWSER_MIN_EPISODES
        val tvBrowserFocus = remember { FocusRequester() }
        val tvHistory by LibraryStore.history.collectAsState()
        val tvResume = tvHistory.firstOrNull { it.anilistId == data.anilistId }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = tvEpisodeListState,
        ) {
            item {
                WatchEpisodeSummary(
                    data = data,
                    saved = saved,
                    onToggleSaved = onToggleSaved,
                    onOpenAnime = onOpenAnime,
                    episodeDownload = episodeDownload,
                    downloadPreparing = downloadPreparing,
                    canDownload = streamForDownload != null,
                    canSaveToDevice = canSaveToDevice,
                    onDownload = showDownloadDialog,
                    focusRequester = summaryFocus,
                    nextFocusRequester = sourceFocus,
                    modifier = Modifier.padding(
                        start = device.pagePadding,
                        end = device.pagePadding,
                        top = 12.dp,
                        bottom = 6.dp,
                    ),
                )
            }
            item {
                SourceSelectors(
                    data = data,
                    onChangeSource = onChangeSource,
                    onChangeCategory = onChangeCategory,
                    onRequestAllSources = onRequestAllSources,
                    onRetryMiruroSources = onRetryMiruroSources,
                    focusRequester = sourceFocus,
                    onToggleFullscreen = onToggleFullscreen,
                    downFocus = tvBrowserFocus.takeIf { tvBrowserVisible },
                )
                data.notice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            start = device.pagePadding,
                            end = device.pagePadding,
                            bottom = 6.dp,
                        ),
                    )
                }
            }
            if (tvBrowserVisible) {
                item {
                    EpisodeBrowserBar(
                        blocks = blocks,
                        selectedBlockIndex = tvBlockIndex,
                        onSelectBlock = { chosenBlockIndex = it },
                        query = episodeQuery,
                        onQueryChange = { episodeQuery = it },
                        layout = EpisodeLayout.GRID,
                        onToggleLayout = {},
                        showLayoutToggle = false,
                        focusRequester = tvBrowserFocus,
                        modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 6.dp),
                    )
                }
            }
            itemsIndexed(episodeRows) { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = device.pagePadding, vertical = 5.dp)
                        // Only the top row needs redirecting; the rest reach the bar through it.
                        .then(
                            if (rowIndex == 0 && tvBrowserVisible) {
                                Modifier.focusProperties { up = tvBrowserFocus }
                            } else {
                                Modifier
                            },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { episode ->
                        val index = indexByPipeId[episode.pipeId] ?: -1
                        EpisodeNumberChip(
                            episode = episode,
                            selected = index == data.currentIndex,
                            watchedFraction = episodeWatchFraction(tvResume, episode.number),
                            modifier = Modifier.weight(1f),
                            onClick = { if (index >= 0) onSelectEpisode(index) },
                        )
                    }
                    repeat(device.episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (episodeQuery.isNotBlank() && episodeRows.isEmpty()) {
                item {
                    Text(
                        text = "No episode matches “$episodeQuery”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = device.pagePadding, vertical = 12.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}
@Composable
private fun WatchEpisodeSummary(
    data: WatchData,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    onOpenAnime: () -> Unit,
    episodeDownload: EpisodeDownload?,
    downloadPreparing: Boolean,
    canDownload: Boolean,
    canSaveToDevice: Boolean,
    onDownload: () -> Unit,
    focusRequester: FocusRequester? = null,
    nextFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val description = remember(data.anilistId, data.description) {
        data.description?.cleanAniListDescription().orEmpty()
    }
    val providerDownloadUrl = remember(data.sources.download) {
        data.sources.download?.trim()?.takeIf { url ->
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank()
        }
    }
    var descriptionExpanded by remember(data.anilistId, data.current.number) { mutableStateOf(false) }
    val canExpand = description.length > 180
    val localMoreFocus = remember { FocusRequester() }
    val localHeartFocus = remember { FocusRequester() }
    val moreFocus = if (canExpand) focusRequester ?: localMoreFocus else localMoreFocus
    val heartFocus = if (!canExpand) focusRequester ?: localHeartFocus else localHeartFocus
    val focusScope = rememberCoroutineScope()
    var restoreHeartFocus by remember { mutableStateOf(false) }
    val toggleSaved = {
        restoreHeartFocus = nextFocusRequester != null
        onToggleSaved()
    }

    LaunchedEffect(saved) {
        if (restoreHeartFocus) {
            delay(60)
            runCatching { heartFocus.requestFocus() }
            restoreHeartFocus = false
        }
    }

    var showDescriptionSheet by remember { mutableStateOf(false) }

    if (showDescriptionSheet) {
        DescriptionBottomSheet(
            data = data,
            canDownload = canDownload,
            downloadPreparing = downloadPreparing,
            episodeDownload = episodeDownload,
            onDownload = onDownload,
            onDismiss = { showDescriptionSheet = false },
        )
    }

    Column(modifier = modifier.fillMaxWidth().focusGroup()) {
        // ── 1. Anime Title Section (YouTube-Style Main Title) ──
        Text(
            text = data.seriesTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            modifier = Modifier.clickable { showDescriptionSheet = true },
        )

        // Metadata Subtitle: e.g. "82% score · Episode 2"
        Text(
            text = buildList {
                data.averageScore?.let { add("$it% score") }
                add("Episode ${data.current.displayNumber}")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp),
        )

        // ── 2. Engagement / Action Row (YouTube Pill Buttons) ──
        WatchActionBar(
            anilistId = data.anilistId,
            seriesTitle = data.seriesTitle,
            episodeNumber = data.current.displayNumber,
            canDownload = canDownload,
            downloadPreparing = downloadPreparing,
            episodeDownload = episodeDownload,
            onDownload = onDownload,
        )

        // ── 3. Anime Identity / Channel Section (Avatar + Title + Popularity + Subscribe Pill Button) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Open ${data.seriesTitle}", onClick = onOpenAnime),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = data.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp).clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        text = data.seriesTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    data.popularity?.let { popularity ->
                        Text(
                            text = "${compactPopularity(popularity)} popularity",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                }
            }

            // YouTube-style Subscribe Button (Preserves old Heart/Favorite toggle action)
            Box(
                modifier = Modifier
                    .focusRequester(heartFocus)
                    .focusProperties {
                        if (canExpand) up = moreFocus
                        nextFocusRequester?.let { down = it }
                    }
                    .onPreviewKeyEvent { event ->
                        val keyCode = event.nativeKeyEvent.keyCode
                        if (keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                            keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                        ) {
                            if (event.type == KeyEventType.KeyUp) toggleSaved()
                            return@onPreviewKeyEvent true
                        }
                        false
                    }
                    .clip(CircleShape)
                    .background(
                        if (saved) Color.White.copy(alpha = 0.15f)
                        else Color.White
                    )
                    .clickable(onClick = toggleSaved)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (saved) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Subscribed",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            text = "Subscribe",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                        )
                    }
                }
            }
        }

        // ── 4. Collapsible Description / Synopsis Box ──
        if (description.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .clickable { if (canExpand) descriptionExpanded = !descriptionExpanded }
                    .padding(12.dp),
            ) {
                Column {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (canExpand) {
                        Text(
                            text = if (descriptionExpanded) "Less" else "...more",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeDownloadDialog(
    stream: StreamItem,
    quality: DownloadQuality,
    destination: DownloadDestination,
    canSaveToDevice: Boolean,
    alreadyInApp: Boolean,
    alreadyInDownloads: Boolean,
    onQualityChange: (DownloadQuality) -> Unit,
    onDestinationChange: (DownloadDestination) -> Unit,
    batchSize: Int,
    onBatchSizeChange: (Int) -> Unit,
    remainingCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val confirmEnabled = !alreadyInApp ||
        (canSaveToDevice && destination.includesDevice)
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    // Recomputed per chosen resolution: the estimate, and therefore the warning, depends on it.
    val freeBytes = remember(quality) { DownloadStorage.freeBytes(context) }
    val perEpisodeBytes = remember(quality) { DownloadStorage.estimatedEpisodeBytes(quality.maxHeight) }
    val batchCount = batchSize.coerceAtMost(remainingCount)
    val batching = batchCount > 1
    // The estimate has to follow the choice: twenty-five episodes is a different question to one.
    val estimatedBytes = perEpisodeBytes * (if (batching) batchCount.toLong() else 1L)
    val tight = freeBytes <= estimatedBytes + DownloadStorage.HEADROOM_BYTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download episode") },
        text = {
            Column {
                Text(
                    "Resolution",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    DownloadQuality.entries.forEach { option ->
                        FilterChip(
                            selected = quality == option,
                            onClick = { onQualityChange(option) },
                            label = { Text(option.label) },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                        )
                    }
                }
                Text(
                    if (stream.isHls) {
                        "Anilili saves the best available rendition at or below this resolution."
                    } else {
                        "Direct files use the closest resolution offered by this source."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Text(
                    "Save to",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp),
                )
                if (canSaveToDevice) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        DownloadDestination.entries.forEach { option ->
                            FilterChip(
                                selected = destination == option,
                                onClick = { onDestinationChange(option) },
                                enabled = option != DownloadDestination.APP_ONLY || !alreadyInApp,
                                label = { Text(option.label) },
                                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                            )
                        }
                    }
                    // Worth spelling out: a device-only download still shows up in the library
                    // while it runs, because the MP4 is built from that copy afterwards.
                    if (destination.includesDevice) {
                        Text(
                            "Episodes download to the library first, then get rewrapped into one MP4 " +
                                "under Downloads/Anilili — no re-encoding, and subtitles are saved alongside it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (alreadyInApp) {
                        Text(
                            "This episode is already in the Anilili offline library. You can still add a device copy.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // Otherwise the only clue would be a second numbered MP4 appearing later.
                    if (alreadyInDownloads) {
                        Text(
                            "An MP4 of this episode is already in your Downloads folder. Downloading " +
                                "again will save a second copy alongside it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    Text(
                        "Anilili offline library · Public device downloads require Android 10 or newer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                if (remainingCount > 0) {
                    Text(
                        "How much",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        FilterChip(
                            selected = !batching,
                            onClick = { onBatchSizeChange(1) },
                            label = { Text("This episode") },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                        )
                        // Bounded sizes rather than "everything": a 500-episode run is ~200 GB and
                        // hours of provider requests, which is not a choice worth offering.
                        BulkEpisodeDownloads.BATCH_SIZES
                            .filter { it <= remainingCount }
                            .forEach { size ->
                                FilterChip(
                                    selected = batchCount == size,
                                    onClick = { onBatchSizeChange(size) },
                                    label = { Text("Next $size") },
                                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                                )
                            }
                        // When fewer remain than the smallest batch, offer exactly what is left.
                        if (remainingCount > 1 && BulkEpisodeDownloads.BATCH_SIZES.none { it <= remainingCount }) {
                            FilterChip(
                                selected = batching,
                                onClick = { onBatchSizeChange(remainingCount) },
                                label = { Text("All $remainingCount left") },
                                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                            )
                        }
                    }
                    if (batching) {
                        Text(
                            "Episodes are fetched one at a time, and each one's source is resolved " +
                                "as its turn comes — links from these servers expire too quickly to " +
                                "collect up front. You can keep watching while it runs.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                Text(
                    "Storage",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    buildString {
                        append("About ")
                        append(Formatter.formatShortFileSize(context, estimatedBytes))
                        append(if (batching) " for $batchCount episodes · " else " for this episode · ")
                        append(Formatter.formatShortFileSize(context, freeBytes))
                        append(" free")
                        // A stick has a few GB total, so the count is a genuinely useful number
                        // there; on a phone with 200 GB free it would just be noise.
                        if (device.isTv && freeBytes > 0L) {
                            append(" (~")
                            append(freeBytes / estimatedBytes.coerceAtLeast(1L))
                            append(" episodes)")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (tight) {
                    Text(
                        "Storage is nearly full. Downloading now may fail part-way or crowd out " +
                            "other apps — free some space or pick a lower resolution first.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    when {
                        tight -> "Download anyway"
                        batching -> "Download $batchCount"
                        else -> "Download"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun selectDownloadStream(
    current: StreamItem,
    available: List<StreamItem>,
    quality: DownloadQuality,
): StreamItem {
    if (current.isHls) return current
    val directCandidates = (listOf(current) + available)
        .filter(EpisodeDownloads::canSaveToDevice)
        .filter { candidate ->
            current.audio == null || candidate.audio == null || candidate.audio == current.audio
        }
        .distinctBy(StreamItem::url)
        .mapNotNull { candidate ->
            val height = candidate.height ?: declaredVideoHeight(candidate.quality)
            height?.let { candidate to it }
        }
    if (directCandidates.isEmpty()) return current
    val maxHeight = quality.maxHeight
    return if (maxHeight == null) {
        directCandidates.maxByOrNull { it.second }?.first ?: current
    } else {
        directCandidates
            .filter { it.second <= maxHeight }
            .maxByOrNull { it.second }
            ?.first
            ?: directCandidates.minByOrNull { it.second }?.first
            ?: current
    }
}

private fun String.cleanAniListDescription(): String =
    replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun compactPopularity(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000f)
    else -> value.toString()
}.replace(".0K", "K").replace(".0M", "M")

@Composable
private fun MobileWatchDetails(
    data: WatchData,
    saved: Boolean,
    onToggleSaved: () -> Unit,
    onOpenAnime: () -> Unit,
    episodeDownload: EpisodeDownload?,
    downloadPreparing: Boolean,
    canDownload: Boolean,
    canSaveToDevice: Boolean,
    onDownload: () -> Unit,
    focusRequester: FocusRequester,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onRequestAllSources: () -> Unit,
    onRetryMiruroSources: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val pad = device.pagePadding
    val historyEntries by LibraryStore.history.collectAsState()
    val resume = historyEntries.firstOrNull { it.anilistId == data.anilistId }
    // Every episode row asks the same question, so resolve the three state sources once per list
    // rather than once per row.
    val badgeContext = LocalContext.current
    val downloadIndex by EpisodeDownloads.downloads(badgeContext).collectAsState()
    val exportStatuses by EpisodeExport.statuses(badgeContext).collectAsState()
    val exportedEpisodes by EpisodeExport.exported(badgeContext).collectAsState()
    val downloadBadges = remember(downloadIndex, exportStatuses, exportedEpisodes) {
        episodeDownloadBadges(downloadIndex, exportStatuses, exportedEpisodes)
    }
    // Browsing state for long-runners; a null range opens on the block that is playing.
    val episodeLayout by SettingsStore.episodeLayout.collectAsState()
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    var episodeQuery by remember(data.anilistId) { mutableStateOf("") }
    var chosenBlockIndex by remember(data.anilistId) { mutableStateOf<Int?>(null) }
    val blocks = remember(data.episodes) { episodeBlocks(data.episodes) }
    // Episodes are selected by index into the full list, so filtering must keep a way back to it.
    val indexByPipeId = remember(data.episodes) {
        data.episodes.withIndex().associate { (index, episode) -> episode.pipeId to index }
    }
    val blockIndex = (chosenBlockIndex ?: blockIndexContaining(blocks, data.current.number))
        .coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
    val shownEpisodes = if (episodeQuery.isNotBlank()) {
        filterEpisodes(data.episodes, episodeQuery)
    } else {
        blocks.getOrNull(blockIndex)?.episodes.orEmpty()
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Episodes, 1 = Recommendations

    LazyColumn(modifier = modifier) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                WatchEpisodeSummary(
                    data = data,
                    saved = saved,
                    onToggleSaved = onToggleSaved,
                    onOpenAnime = onOpenAnime,
                    episodeDownload = episodeDownload,
                    downloadPreparing = downloadPreparing,
                    canDownload = canDownload,
                    canSaveToDevice = canSaveToDevice,
                    onDownload = onDownload,
                    modifier = Modifier.padding(start = pad, end = pad, top = 16.dp, bottom = 4.dp),
                )
            }
            SourceSelectors(
                data = data,
                onChangeSource = onChangeSource,
                onChangeCategory = onChangeCategory,
                onRequestAllSources = onRequestAllSources,
                onRetryMiruroSources = onRetryMiruroSources,
                focusRequester = focusRequester,
            )
            data.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = pad, vertical = 5.dp),
                )
            }
            BulkDownloadStatus(modifier = Modifier.padding(horizontal = pad))

            // Segmented Control Rectangle Box for Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = pad, vertical = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selectedTab == 0) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { selectedTab = 0 }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Episodes (${data.episodes.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selectedTab == 1) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { selectedTab = 1 }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Recommendations",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (selectedTab == 1) {
            item {
                RecommendationsList(
                    media = data.fullMedia,
                    onOpenAnime = onOpenAnime,
                )
            }
        } else {
            if (data.episodes.size > EPISODE_BROWSER_MIN_EPISODES) {
                item {
                    EpisodeBrowserBar(
                        blocks = blocks,
                        selectedBlockIndex = blockIndex,
                        onSelectBlock = { chosenBlockIndex = it },
                        query = episodeQuery,
                        onQueryChange = { episodeQuery = it },
                        layout = episodeLayout,
                        onToggleLayout = { SettingsStore.setEpisodeLayout(episodeLayout.toggled()) },
                        modifier = Modifier.padding(horizontal = pad).padding(bottom = 6.dp),
                    )
                }
            }
            when {
                shownEpisodes.isNotEmpty() && episodeLayout == EpisodeLayout.GRID -> items(
                    shownEpisodes.chunked(device.episodeColumns),
                    key = { row -> row.first().pipeId },
                ) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { episode ->
                            val index = indexByPipeId[episode.pipeId] ?: -1
                            EpisodeNumberChip(
                                episode = episode,
                                selected = index == data.currentIndex,
                                watchedFraction = episodeWatchFraction(resume, episode.number),
                                modifier = Modifier.weight(1f),
                                onClick = { if (index >= 0) onSelectEpisode(index) },
                            )
                        }
                        repeat(device.episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                shownEpisodes.isNotEmpty() -> items(shownEpisodes, key = EpisodeItem::pipeId) { episode ->
                    val index = indexByPipeId[episode.pipeId] ?: -1
                    MobileEpisodeRow(
                        episode = episode,
                        fallbackImage = data.artworkUrl,
                        blurred = blurEpisodeImages,
                        onBlurredChange = SettingsStore::setBlurEpisodeImages,
                        selected = index == data.currentIndex,
                        watchedFraction = episodeWatchFraction(resume, episode.number),
                        downloadState = downloadBadges[
                            EpisodeDownloads.idFor(data.anilistId, data.category.api, episode.displayNumber)
                        ],
                        onClick = { if (index >= 0) onSelectEpisode(index) },
                    )
                }
                episodeQuery.isNotBlank() -> item {
                    Text(
                        text = "No episode matches “$episodeQuery”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = pad, vertical = 12.dp),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun MobileEpisodeRow(
    episode: EpisodeItem,
    fallbackImage: String?,
    blurred: Boolean,
    onBlurredChange: (Boolean) -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    watchedFraction: Float = 0f,
    downloadState: EpisodeDownloadUi? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalAppDeviceProfile.current.pagePadding, vertical = 5.dp)
            .focusHighlight(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(132.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(9.dp)),
        ) {
            EpisodeArtwork(
                image = episodeArtworkImage(episode.image, fallbackImage),
                blurred = blurred,
                onBlurredChange = onBlurredChange,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = "EP ${episode.displayNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(if (watchedFraction > 0.01f) Alignment.TopEnd else Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            com.anilili.ui.components.WatchProgressBar(
                fraction = watchedFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
            DownloadCoverBadge(
                state = downloadState,
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(9.dp)),
                compact = true,
            )
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            val title = episode.distinctTitle
            Text(
                text = title ?: "Episode ${episode.displayNumber}",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Only worth a second line when it says something the first one didn't: providers
            // without real episode titles already have the number above.
            val subtitle = when {
                selected -> "Now playing"
                title != null -> "Episode ${episode.displayNumber}"
                else -> null
            }
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

/** Compact server and audio selectors. Choosing a server also makes it the global preference. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSelectors(
    data: WatchData,
    onChangeSource: (String, String) -> Unit,
    onChangeCategory: (String) -> Unit,
    onRequestAllSources: () -> Unit,
    onRetryMiruroSources: () -> Unit,
    focusRequester: FocusRequester,
    onToggleFullscreen: (() -> Unit)? = null,
    // Where the D-pad should go when leaving this row downwards. The episode browser below is
    // invisible to 2D focus search, so it has to be named explicitly.
    downFocus: FocusRequester? = null,
) {
    val device = LocalAppDeviceProfile.current
    var audioFilter by rememberSaveable { mutableStateOf(AudioFilter.ANY) }
    var languageFilter by rememberSaveable { mutableStateOf<String?>(null) }
    // A language that no longer appears (different episode, different servers) would otherwise
    // silently filter everything away with no visible cause.
    LaunchedEffect(data.knownLanguages) {
        if (languageFilter != null && languageFilter !in data.knownLanguages) languageFilter = null
    }
    val filteredOptions = remember(data.sourceOptions, data.capabilities, audioFilter, languageFilter) {
        filterSourceOptions(data.sourceOptions, data.capabilities, audioFilter, languageFilter)
    }
    val servers = remember(filteredOptions) { filteredOptions.map { it.provider }.distinct() }
    val categoriesByServer = remember(data.sourceOptions) {
        data.sourceOptions
            .groupBy { it.provider }
            .mapValues { (_, options) -> options.map { it.category }.distinct() }
    }
    val audioForServer = remember(data.sourceOptions, data.provider) {
        data.sourceOptions.filter { it.provider == data.provider }.map { it.category }.distinct()
    }
    var showServerDialog by remember { mutableStateOf(false) }
    val mobileSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tvDialogFocus = remember { FocusRequester() }

    LaunchedEffect(showServerDialog, device.isTv) {
        if (showServerDialog && device.isTv) {
            delay(120)
            runCatching { tvDialogFocus.requestFocus() }
                .onFailure { DiagnosticsLog.throwable("TV server dialog focus failed", it) }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 4.dp)
            .then(downFocus?.let { target -> Modifier.focusProperties { down = target } } ?: Modifier)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Server also carries the TV focus anchor for leaving fullscreen.
        CompactClickablePill(
            label = ProviderCatalog.label(data.provider) +
                if (data.provider == data.preferredProvider) " ★" else "",
            downloadable = ProviderCatalog.supportsOfflineDownload(data.provider),
            enabled = servers.isNotEmpty(),
            focusRequester = focusRequester,
            onClick = {
                onRequestAllSources()
                showServerDialog = true
            }
        )
        val alternateAudio = audioForServer.firstOrNull { it != data.category }
        CompactClickablePill(
            label = if (data.category == com.anilili.data.model.Category.DUB) "Dub" else "Sub",
            enabled = alternateAudio != null,
            active = data.category == com.anilili.data.model.Category.DUB,
            showArrow = false,
            onClick = { alternateAudio?.let { onChangeCategory(it.api) } },
        )
        if (device.isTv && onToggleFullscreen != null) {
            CompactClickablePill(
                label = "Fullscreen",
                enabled = true,
                showArrow = false,
                onClick = onToggleFullscreen,
            )
        }
        if (data.isLoadingMoreSources || data.isRetryingMiruro) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (data.isRetryingMiruro) "Reconnecting Miruro…" else "More servers…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showServerDialog && !device.isTv) {
        ModalBottomSheet(
            onDismissRequest = { showServerDialog = false },
            sheetState = mobileSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            MobileServerPickerContent(
                data = data,
                servers = servers,
                audio = audioFilter,
                onAudioChange = { audioFilter = it },
                language = languageFilter,
                onLanguageChange = { languageFilter = it },
                onSelect = { server ->
                    showServerDialog = false
                    if (server != data.provider || server != data.preferredProvider) {
                        val options = data.sourceOptions.filter { it.provider == server }
                        val nextCategory = options.firstOrNull { it.category == data.category }?.category
                            ?: options.first().category
                        onChangeSource(server, nextCategory.api)
                    }
                },
                onRetryMiruroSources = onRetryMiruroSources,
                onClose = { showServerDialog = false },
            )
        }
    }

    if (showServerDialog && device.isTv) {
        Dialog(
            onDismissRequest = {
                showServerDialog = false
                if (device.isTv) runCatching { focusRequester.requestFocus() }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = device.isTv,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (device.isTv) 0.dp else 84.dp),
                contentAlignment = if (device.isTv) Alignment.Center else Alignment.BottomCenter,
            ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .clip(
                        if (device.isTv) RoundedCornerShape(16.dp)
                        else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        if (device.isTv) RoundedCornerShape(16.dp)
                        else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
                    .padding(20.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Server for this episode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (data.isLoadingMoreSources || data.isRetryingMiruro) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = if (data.isRetryingMiruro) {
                                    "Reconnecting Miruro… ${servers.size} available"
                                } else if (data.isLoadingMoreSources) {
                                    "Finding servers… ${servers.size} so far"
                                } else if (data.preferredProvider == "auto") {
                                    "Applies to this episode only · ${servers.size} available"
                                } else {
                                    "This episode only · Settings pins ${ProviderCatalog.label(data.preferredProvider)} · ${servers.size} available"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = FastServerColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Fast servers — these usually start quickest",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    SourceFilterRow(
                        audio = audioFilter,
                        onAudioChange = { audioFilter = it },
                        languages = data.knownLanguages,
                        language = languageFilter,
                        onLanguageChange = { languageFilter = it },
                        stillChecking = !languageFilterIsComplete(data.sourceOptions, data.capabilities),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val columns = if (device.isTv) 4 else 3
                        val rows = servers.chunked(columns)
                        rows.forEachIndexed { rowIndex, rowCells ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowCells.forEachIndexed { columnIndex, server ->
                                    val selected = server == data.provider
                                    val preferred = server == data.preferredProvider
                                    val downloadable = ProviderCatalog.supportsOfflineDownload(server)
                                    val bg = when {
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                    val textColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    val selectServer = {
                                        showServerDialog = false
                                        if (device.isTv) runCatching { focusRequester.requestFocus() }
                                        if (!selected || !preferred) {
                                            val options = data.sourceOptions.filter { it.provider == server }
                                            val category = options.firstOrNull { it.category == data.category }?.category
                                                ?: options.first().category
                                            onChangeSource(server, category.api)
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .then(
                                                if (rowIndex == 0 && columnIndex == 0) {
                                                    Modifier.focusRequester(tvDialogFocus)
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .height(58.dp)
                                            .onPreviewKeyEvent { event ->
                                                val keyCode = event.nativeKeyEvent.keyCode
                                                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                                                if (!activate) {
                                                    false
                                                } else {
                                                    if (event.type == KeyEventType.KeyUp) selectServer()
                                                    true
                                                }
                                            }
                                            .focusHighlight(RoundedCornerShape(8.dp))
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bg)
                                            .border(
                                                1.dp,
                                                if (selected || preferred) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable(onClick = selectServer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (ProviderCatalog.isFast(server)) {
                                                    Icon(
                                                        Icons.Default.Bolt,
                                                        contentDescription = "Fast server",
                                                        tint = if (selected) textColor else FastServerColor,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                                if (downloadable) {
                                                    Icon(
                                                        Icons.Default.Download,
                                                        contentDescription = "Can be saved offline",
                                                        tint = if (selected) textColor else MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                                Text(
                                                    text = ProviderCatalog.label(server) + if (preferred) " ★" else "",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (selected || preferred) FontWeight.Bold else FontWeight.Normal,
                                                    color = textColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                categoriesByServer[server].orEmpty().forEach { category ->
                                                    SourceCategoryBadge(category = category, selected = selected)
                                                }
                                            }
                                        }
                                    }
                                }
                                repeat(columns - rowCells.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!data.hasMiruroSources) {
                            TextButton(
                                onClick = onRetryMiruroSources,
                                enabled = !data.isRetryingMiruro,
                                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                            ) {
                                Text(
                                    if (data.isRetryingMiruro) "Retrying Miruro…"
                                    else "Retry Miruro servers",
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                showServerDialog = false
                                if (device.isTv) runCatching { focusRequester.requestFocus() }
                            },
                            modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun MobileServerPickerContent(
    data: WatchData,
    servers: List<String>,
    audio: AudioFilter,
    onAudioChange: (AudioFilter) -> Unit,
    language: String?,
    onLanguageChange: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onRetryMiruroSources: () -> Unit,
    onClose: () -> Unit,
) {
    val categoriesByServer = remember(data.sourceOptions) {
        data.sourceOptions
            .groupBy { it.provider }
            .mapValues { (_, options) -> options.map { it.category }.distinct() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Server for this episode",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (data.isLoadingMoreSources || data.isRetryingMiruro) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = if (data.isRetryingMiruro) {
                        "Reconnecting Miruro… ${servers.size} available"
                    } else if (data.isLoadingMoreSources) {
                        "Finding servers… ${servers.size} so far"
                    } else if (data.preferredProvider == "auto") {
                        "Applies to this episode only · ${servers.size} available"
                    } else {
                        "This episode only · Settings pins ${ProviderCatalog.label(data.preferredProvider)} · ${servers.size} available"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = FastServerColor,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "Fast servers usually start quickest",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SourceFilterRow(
            audio = audio,
            onAudioChange = onAudioChange,
            languages = data.knownLanguages,
            language = language,
            onLanguageChange = onLanguageChange,
            stillChecking = !languageFilterIsComplete(data.sourceOptions, data.capabilities),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val columns = 2
            servers.chunked(columns).forEach { rowServers ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowServers.forEach { server ->
                        val selected = server == data.provider
                        val preferred = server == data.preferredProvider
                        val downloadable = ProviderCatalog.supportsOfflineDownload(server)
                        val textColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected || preferred) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(11.dp),
                                )
                                .clickable { onSelect(server) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (ProviderCatalog.isFast(server)) {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = "Fast server",
                                        tint = if (selected) textColor else FastServerColor,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Text(
                                    text = ProviderCatalog.label(server) + if (preferred) " ★" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (selected || preferred) FontWeight.Bold else FontWeight.Normal,
                                    color = textColor,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (downloadable) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = "Can be saved offline",
                                        tint = if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                categoriesByServer[server].orEmpty().forEach { category ->
                                    SourceCategoryBadge(category = category, selected = selected)
                                }
                            }
                        }
                    }
                    repeat(columns - rowServers.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!data.hasMiruroSources) {
                TextButton(
                    onClick = onRetryMiruroSources,
                    enabled = !data.isRetryingMiruro,
                ) {
                    Text(
                        if (data.isRetryingMiruro) "Retrying Miruro…"
                        else "Retry Miruro servers",
                    )
                }
            }
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun SourceCategoryBadge(category: Category, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}

/** Compact bordered pill that triggers an onClick callback. */
@Composable
private fun CompactClickablePill(
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    active: Boolean = false,
    showArrow: Boolean = true,
    downloadable: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val activate = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (!enabled || !activate) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) onClick()
                    true
                }
            }
            .focusHighlight(RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (downloadable) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Can be saved offline",
                tint = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(15.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
        if (showArrow) {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Compact bordered pill that opens a dropdown menu. */
@Composable
private fun CompactDropdown(
    label: String,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusHighlight(RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { expanded = true }
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

@Composable
private fun NoSource(onWebFallback: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No playable source on this server.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            TextButton(
                onClick = onWebFallback,
                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
            ) { Text("Open in web player") }
        }
    }
}

@Composable
private fun EmbedEpisodeNavigationEffect(
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val currentHasPrevious by rememberUpdatedState(hasPrevious)
    val currentHasNext by rememberUpdatedState(hasNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)

    DisposableEffect(Unit) {
        val navigator: (Int) -> Unit = { direction ->
            DiagnosticsLog.event("Embed player episode navigator direction=$direction")
            when {
                direction > 0 && currentHasNext -> currentOnNext()
                direction < 0 && currentHasPrevious -> currentOnPrevious()
            }
        }
        PlaybackService.episodeNavigator = navigator
        DiagnosticsLog.event("Embed player episode navigator registered hasPrev=$hasPrevious hasNext=$hasNext")
        onDispose {
            if (PlaybackService.episodeNavigator === navigator) {
                PlaybackService.episodeNavigator = null
            }
            DiagnosticsLog.event("Embed player episode navigator cleared")
        }
    }
}

/** Amber bolt tint for the fast-server badge; readable on both selected and idle cells. */
private val FastServerColor = Color(0xFFFFB300)

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tvFocusPolicy = if (LocalAppDeviceProfile.current.isTv) {
        // A TV remote already has a dedicated Back key. Keeping this overlay out of the D-pad
        // graph prevents Up/Center from unexpectedly abandoning the episode controls.
        Modifier.focusProperties { canFocus = false }
    } else {
        Modifier
    }
    IconButton(
        onClick = onBack,
        // The app draws edge-to-edge, so keep the button below the clock/battery area whenever
        // the status bar is visible (the inset is zero in fullscreen, where the bars are hidden).
        modifier = modifier
            .then(tvFocusPolicy)
            .statusBarsPadding()
            .padding(4.dp)
            .focusHighlight(RoundedCornerShape(24.dp)),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
        )
    }
}


/**
 * Status line for a running batch download.
 *
 * Batches take minutes and queue their episodes one at a time, so without this the only feedback
 * is downloads quietly appearing in the Library — easily read as nothing happening.
 */
@Composable
private fun BulkDownloadStatus(modifier: Modifier = Modifier) {
    val progress by BulkEpisodeDownloads.progress.collectAsState()
    val state = progress ?: return

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = when (state.outcome) {
                BulkDownloadOutcome.RUNNING -> buildString {
                    append("Downloading ${state.done + 1} of ${state.total}")
                    state.current?.let { append(" · episode $it") }
                }
                BulkDownloadOutcome.FINISHED ->
                    "Queued ${state.queued} of ${state.total}" +
                        if (state.failed > 0) " · ${state.failed} had no downloadable source" else ""
                BulkDownloadOutcome.CANCELLED -> "Stopped after ${state.queued} of ${state.total}"
                BulkDownloadOutcome.OUT_OF_SPACE ->
                    "Out of space after ${state.queued} of ${state.total}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state.outcome == BulkDownloadOutcome.OUT_OF_SPACE) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { if (state.isRunning) BulkEpisodeDownloads.cancel() else BulkEpisodeDownloads.dismiss() },
            modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
        ) {
            Text(if (state.isRunning) "Stop" else "Dismiss")
        }
    }
}

// ---- Redesign UI Components (anilili.png) ----

@Composable
private fun WatchActionBar(
    anilistId: Int,
    seriesTitle: String,
    episodeNumber: String,
    canDownload: Boolean,
    downloadPreparing: Boolean,
    episodeDownload: EpisodeDownload?,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val likes by LibraryStore.likes.collectAsState()
    val dislikes by LibraryStore.dislikes.collectAsState()
    val isLiked = anilistId in likes
    val isDisliked = anilistId in dislikes

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Combined Like / Dislike YouTube Pill
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Like Segment
            Row(
                modifier = Modifier
                    .clickable { LibraryStore.toggleLike(anilistId) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ThumbUp,
                    contentDescription = "Like",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (isLiked) "1" else "Like",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(Color.White.copy(alpha = 0.2f))
            )

            // Dislike Segment
            Box(
                modifier = Modifier
                    .clickable { LibraryStore.toggleDislike(anilistId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ThumbDown,
                    contentDescription = "Dislike",
                    tint = if (isDisliked) MaterialTheme.colorScheme.error else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Share Pill
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, seriesTitle)
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Watching $seriesTitle - Episode $episodeNumber on Anilili!\nhttps://anilist.co/anime/$anilistId",
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Anime"))
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Share",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }

        // Download Pill
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(enabled = canDownload && !downloadPreparing, onClick = onDownload)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                downloadPreparing || episodeDownload?.state == EpisodeDownloadState.DOWNLOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                }
                episodeDownload?.state == EpisodeDownloadState.COMPLETED -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                else -> {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download",
                        tint = if (canDownload) Color.White else Color.Gray,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = when (episodeDownload?.state) {
                    EpisodeDownloadState.COMPLETED -> "Downloaded"
                    EpisodeDownloadState.DOWNLOADING -> "Downloading..."
                    else -> "Download"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (canDownload) Color.White else Color.Gray,
            )
        }
    }
}

@Composable
private fun RecommendationsList(
    media: com.anilili.data.model.Media?,
    onOpenAnime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recommendations = remember(media) {
        media?.relations?.edges?.mapNotNull { it.node } ?: emptyList()
    }

    if (recommendations.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No recommendations available for this series.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        recommendations.forEach { rec ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { onOpenAnime() }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = rec.coverImage.best,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp, 88.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = rec.title.preferred,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${rec.format ?: "TV"} • Ep ${rec.episodes ?: "?"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        rec.averageScore?.let { score ->
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = " ${score / 10f} ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        rec.popularity?.let { pop ->
                            Text(
                                text = "  🔥 ${compactPopularity(pop)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TagChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DescriptionBottomSheet(
    data: WatchData,
    canDownload: Boolean,
    downloadPreparing: Boolean,
    episodeDownload: EpisodeDownload?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = data.seriesTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Episode ${data.current.displayNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            WatchActionBar(
                anilistId = data.anilistId,
                seriesTitle = data.seriesTitle,
                episodeNumber = data.current.displayNumber,
                canDownload = canDownload,
                downloadPreparing = downloadPreparing,
                episodeDownload = episodeDownload,
                onDownload = onDownload,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                data.fullMedia?.seasonYear?.let { year -> TagChip(text = "$year") }
                data.fullMedia?.genres.orEmpty().take(4).forEach { genre -> TagChip(text = genre) }
            }

            Text(
                "Description",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Text(
                text = data.description?.cleanAniListDescription() ?: "No description available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                data.fullMedia?.status?.let { status ->
                    MetadataRow("Status", status.replace('_', ' ').lowercase())
                }
                data.fullMedia?.episodes?.let { eps ->
                    MetadataRow("Total Episodes", "$eps")
                }
                data.fullMedia?.duration?.let { dur ->
                    MetadataRow("Duration", "$dur min/ep")
                }
                data.fullMedia?.studios?.nodes?.firstOrNull()?.name?.let { studio ->
                    MetadataRow("Studio", studio)
                }
                data.averageScore?.let { score ->
                    MetadataRow("Rating", "$score% (${compactPopularity(data.popularity ?: 0)} votes)")
                }
                data.popularity?.let { pop ->
                    MetadataRow("Popularity", "🔥 ${compactPopularity(pop)}")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

