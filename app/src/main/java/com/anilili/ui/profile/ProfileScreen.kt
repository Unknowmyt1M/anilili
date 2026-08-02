package com.anilili.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.model.MediaListEntry
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvNativeTextField
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.components.ContinueWatchingActionsDialog
import com.anilili.ui.components.FastScrollbar
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.PullRefreshContainer
import com.anilili.ui.components.RatingBadge
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.playback.EpisodeDownload
import com.anilili.playback.EpisodeDownloadState
import com.anilili.playback.EpisodeDownloads
import com.anilili.playback.EpisodeExport
import com.anilili.playback.EpisodeExportState
import com.anilili.playback.EpisodeExportStatus
import com.anilili.playback.OfflineEpisode
import com.anilili.playback.OfflineSeries
import com.anilili.playback.episodeDownloadBadges
import com.anilili.ui.components.DownloadCoverBadge
import com.anilili.playback.offlineEpisodeBlocks
import com.anilili.playback.offlineEpisodes
import com.anilili.playback.offlineSeries
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class LibraryView(val label: String) {
    WATCHLIST("My watchlist"),
    WATCHING("Watching"),
    REWATCHING("Re-watching"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    DROPPED("Dropped"),
}

private enum class LibrarySort(val label: String) {
    LAST_MODIFIED("Last modified"),
    TITLE("Title (A–Z)"),
    SCORE("Highest score"),
    PROGRESS("Most progress"),
    DEFAULT("Default"),
}

private data class SelectOption(val value: String?, val label: String)

private data class SavedAnimeCardData(
    val id: Int,
    val title: String,
    val cover: String?,
    val format: String?,
    val airingStatus: String?,
    val status: String?,
    val userScore: Double?,
    val averageScore: Int?,
    val progress: Int?,
    val totalEpisodes: Int?,
    val updatedAt: Long = 0L,
)

private val formatOptions = listOf(
    SelectOption(null, "Any format"),
    SelectOption("TV", "TV"),
    SelectOption("MOVIE", "Movie"),
    SelectOption("ONA", "ONA"),
    SelectOption("OVA", "OVA"),
    SelectOption("SPECIAL", "Special"),
)

private val airingOptions = listOf(
    SelectOption(null, "Any airing status"),
    SelectOption("RELEASING", "Ongoing"),
    SelectOption("FINISHED", "Completed"),
    SelectOption("NOT_YET_RELEASED", "Upcoming"),
    SelectOption("HIATUS", "On hiatus"),
    SelectOption("CANCELLED", "Cancelled"),
)

private val sortOptions = LibrarySort.entries.map { SelectOption(it.name, "Sort: ${it.label}") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onAnimeClick: (Int) -> Unit,
    onResume: (HistoryEntry) -> Unit,
    onPlayDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val token by AuthManager.token.collectAsState()
    val malLoggedIn by MalAuthManager.loggedIn.collectAsState()
    val profileState by vm.profile.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val downloadIndex by EpisodeDownloads.downloads(context).collectAsState()
    val exportedEpisodes by EpisodeExport.exported(context).collectAsState()
    val exportStatuses by EpisodeExport.statuses(context).collectAsState()
    // One card per episode whether it is held as cached segments, as an MP4 in Downloads, or both.
    val episodeDownloads = remember(downloadIndex, exportedEpisodes) {
        offlineEpisodes(downloadIndex, exportedEpisodes)
    }
    // The flat list is unmanageable for long-runners; the section below groups by anime instead.
    val downloadSeries = remember(episodeDownloads) { offlineSeries(episodeDownloads) }
    val isRefreshing by vm.isRefreshing.collectAsState()
    var loginService by remember { mutableStateOf<AccountService?>(null) }
    var selectedViewName by rememberSaveable { mutableStateOf(LibraryView.WATCHLIST.name) }
    var selectedFormat by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAiring by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSortName by rememberSaveable { mutableStateOf(LibrarySort.LAST_MODIFIED.name) }
    var titleFilter by rememberSaveable { mutableStateOf("") }
    val loggedIn = token != null || malLoggedIn

    LaunchedEffect(loggedIn) {
        if (!loggedIn) selectedViewName = LibraryView.WATCHLIST.name
        vm.loadIfLoggedIn()
    }

    when (loginService) {
        AccountService.ANILIST -> {
            LoginWebView(
                authorizeUrl = remember { AuthManager.authorizeUrl() },
                isRedirect = AuthManager::isRedirect,
                extractResult = AuthManager::extractToken,
                onResult = {
                    loginService = null
                    vm.onLoggedIn(it)
                    confirmSignedIn(context, "AniList")
                },
                onCancel = { loginService = null },
            )
            return
        }
        AccountService.MAL -> {
            LoginWebView(
                authorizeUrl = remember { MalAuthManager.authorizeUrl() },
                isRedirect = MalAuthManager::isRedirect,
                extractResult = MalAuthManager::extractCode,
                onResult = { loginService = null; vm.onMalCode(it) },
                onCancel = { loginService = null },
            )
            return
        }
        null -> Unit
    }

    val profile = (profileState as? UiState.Success)?.data
    val combinedWatchlist = remember(profile, watchlist, history) {
        buildCombinedWatchlist(profile, watchlist, history)
    }
    val selectedView = LibraryView.valueOf(selectedViewName)
    val selectedSort = LibrarySort.entries.firstOrNull { it.name == selectedSortName } ?: LibrarySort.LAST_MODIFIED
    val selectedCards = remember(profile, combinedWatchlist, selectedView, selectedFormat, selectedAiring, selectedSort, titleFilter) {
        val source = when (selectedView) {
            LibraryView.WATCHLIST -> combinedWatchlist
            LibraryView.WATCHING -> aniListCards(profile?.watching.orEmpty())
            LibraryView.REWATCHING -> aniListCards(profile?.rewatching.orEmpty())
            LibraryView.PAUSED -> aniListCards(profile?.paused.orEmpty())
            LibraryView.COMPLETED -> aniListCards(profile?.completed.orEmpty())
            LibraryView.DROPPED -> aniListCards(profile?.dropped.orEmpty())
        }
        val filtered = source.filter { entry ->
            (selectedFormat == null || entry.format == selectedFormat) &&
                (selectedAiring == null || entry.airingStatus == selectedAiring) &&
                (titleFilter.isBlank() || entry.title.contains(titleFilter, ignoreCase = true))
        }
        when (selectedSort) {
            LibrarySort.LAST_MODIFIED -> filtered.sortedByDescending { it.updatedAt }
            LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.SCORE -> filtered.sortedByDescending { it.userScore ?: 0.0 }
            LibrarySort.PROGRESS -> filtered.sortedByDescending { it.progress ?: 0 }
            LibrarySort.DEFAULT -> filtered
        }
    }
    var isLibraryExpanded by rememberSaveable { mutableStateOf(false) }
    var isHistoryExpanded by rememberSaveable { mutableStateOf(false) }
    var isDownloadsExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedDownloadSeriesId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showDownloadsRemoveAll by remember { mutableStateOf(false) }
    val selectedDownloadSeries = downloadSeries.firstOrNull { it.anilistId == selectedDownloadSeriesId }
    var selectedDownloadCategory by remember(selectedDownloadSeriesId) { mutableStateOf<String?>(null) }
    var selectedDownloadBlock by remember(selectedDownloadSeriesId, selectedDownloadCategory) { mutableStateOf(0) }
    // The series the viewer is browsing can vanish under them (remove-all, or the last episode
    // deleted from the watch page); drop back to the series list instead of showing a ghost.
    LaunchedEffect(selectedDownloadSeriesId, selectedDownloadSeries) {
        if (selectedDownloadSeriesId != null && selectedDownloadSeries == null) {
            selectedDownloadSeriesId = null
        }
    }
    val profileListState = rememberLazyListState()

    if (showDownloadsRemoveAll && selectedDownloadSeries != null) {
        val series = selectedDownloadSeries
        AlertDialog(
            onDismissRequest = { showDownloadsRemoveAll = false },
            title = { Text("Remove all downloads?") },
            text = {
                Text(
                    "This removes every saved episode of ${series.title} from this device, " +
                        "including any MP4 copies exported to the Downloads folder.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    series.episodes.forEach { removeOfflineEpisode(context, it) }
                    showDownloadsRemoveAll = false
                    selectedDownloadSeriesId = null
                }) { Text("Remove all") }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadsRemoveAll = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ScrollAwareTopBar { TopAppBar(
                title = { Text("Library", fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            ) }
        },
    ) { padding ->
        PullRefreshContainer(
            isRefreshing = isRefreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = profileListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + LocalAppChromeBottomInset.current + 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (device.isTv && !loggedIn) {
                        // Give the two provider buttons the whole first focus row. In the old
                        // split layout, D-pad focus entered the filter panel on the right and made
                        // the AniList button appear unresponsive or unreachable on some TVs.
                        item {
                            ProfileHero(
                                loggedIn = false,
                                state = profileState,
                                onLogin = { loginService = it },
                                onSync = { vm.loadIfLoggedIn() },
                                onLogout = vm::logout,
                                modifier = Modifier.padding(horizontal = device.pagePadding),
                            )
                        }
                        item {
                            LibraryFilters(
                                selectedView = selectedView,
                                onViewChange = { selectedViewName = it.name },
                                selectedFormat = selectedFormat,
                                onFormatChange = { selectedFormat = it },
                                selectedAiring = selectedAiring,
                                onAiringChange = { selectedAiring = it },
                                selectedSort = selectedSort,
                                onSortChange = { selectedSortName = it.name },
                                titleFilter = titleFilter,
                                onTitleFilterChange = { titleFilter = it },
                                resultCount = selectedCards.size,
                                showAniListLists = false,
                                modifier = Modifier.padding(horizontal = device.pagePadding),
                            )
                        }
                    } else if (device.isTv) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = device.pagePadding)
                                    .focusGroup(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                ProfileHero(
                                    loggedIn = loggedIn,
                                    state = profileState,
                                    onLogin = { loginService = it },
                                    onSync = { vm.loadIfLoggedIn() },
                                    onLogout = vm::logout,
                                    modifier = Modifier.weight(1f),
                                )
                                LibraryFilters(
                                    selectedView = selectedView,
                                    onViewChange = { selectedViewName = it.name },
                                    selectedFormat = selectedFormat,
                                    onFormatChange = { selectedFormat = it },
                                    selectedAiring = selectedAiring,
                                    onAiringChange = { selectedAiring = it },
                                    selectedSort = selectedSort,
                                    onSortChange = { selectedSortName = it.name },
                                    titleFilter = titleFilter,
                                    onTitleFilterChange = { titleFilter = it },
                                    resultCount = selectedCards.size,
                                    showAniListLists = profile != null,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        item {
                            ProfileHero(
                                loggedIn = loggedIn,
                                state = profileState,
                                onLogin = { loginService = it },
                                onSync = { vm.loadIfLoggedIn() },
                                onLogout = vm::logout,
                                modifier = Modifier.padding(horizontal = device.pagePadding),
                            )
                        }
                        item {
                            LibraryFilters(
                                selectedView = selectedView,
                                onViewChange = { selectedViewName = it.name },
                                selectedFormat = selectedFormat,
                                onFormatChange = { selectedFormat = it },
                                selectedAiring = selectedAiring,
                                onAiringChange = { selectedAiring = it },
                                selectedSort = selectedSort,
                                onSortChange = { selectedSortName = it.name },
                                titleFilter = titleFilter,
                                onTitleFilterChange = { titleFilter = it },
                                resultCount = selectedCards.size,
                                showAniListLists = profile != null,
                                modifier = Modifier.padding(horizontal = device.pagePadding),
                            )
                        }
                    }

                    item {
                        val serviceLabel = profile?.service?.label ?: "AniList"
                        ProfileSectionTitle(
                            title = selectedView.label,
                            subtitle = if (selectedView == LibraryView.WATCHLIST) "Saved here and in $serviceLabel Planning" else "Synced from $serviceLabel",
                            isExpanded = isLibraryExpanded,
                            onToggleExpand = { isLibraryExpanded = !isLibraryExpanded },
                        )
                    }
                    if (selectedCards.isEmpty()) {
                        item {
                            EmptyPanel(
                                if (selectedView == LibraryView.WATCHLIST && selectedFormat == null && selectedAiring == null && titleFilter.isBlank()) {
                                    "Tap the heart on any anime to save it"
                                } else {
                                    "No anime match these filters"
                                },
                            )
                        }
                    } else if (isLibraryExpanded) {
                        val columns = device.posterColumns
                        val cardRows = selectedCards.chunked(columns)
                        itemsIndexed(cardRows, key = { rowIndex, _ -> "lib_row_$rowIndex" }) { _, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = device.pagePadding, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (entry in row) {
                                    SavedAnimeCard(
                                        entry = entry,
                                        onAnimeClick = onAnimeClick,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(columns - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        item {
                            LazyRow(
                                modifier = Modifier.focusGroup(),
                                contentPadding = PaddingValues(horizontal = device.pagePadding),
                                horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 12.dp),
                            ) {
                                items(selectedCards, key = { it.id }) { entry ->
                                    SavedAnimeCard(entry, onAnimeClick)
                                }
                            }
                        }
                    }

                    item {
                        ProfileSectionTitle(
                            title = "Continue Watching",
                            subtitle = "Long-press a title to remove it or move it on your anime list",
                            isExpanded = isHistoryExpanded,
                            onToggleExpand = { isHistoryExpanded = !isHistoryExpanded },
                        )
                    }
                    if (history.isEmpty()) {
                        item { EmptyPanel("Nothing watched yet") }
                    } else if (isHistoryExpanded) {
                        val columns = device.posterColumns
                        val historyRows = history.chunked(columns)
                        itemsIndexed(historyRows, key = { rowIndex, _ -> "hist_row_$rowIndex" }) { _, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = device.pagePadding, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (entry in row) {
                                    HistoryCard(
                                        entry = entry,
                                        onResume = onResume,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(columns - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        item {
                            LazyRow(
                                modifier = Modifier.focusGroup(),
                                contentPadding = PaddingValues(horizontal = device.pagePadding),
                                horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 12.dp),
                            ) {
                                items(history, key = { it.anilistId }) { entry -> HistoryCard(entry, onResume) }
                            }
                        }
                    }

                    item {
                        ProfileSectionTitle(
                            title = "Downloads",
                            subtitle = "Episodes saved on this device, grouped by anime",
                            isExpanded = isDownloadsExpanded.takeIf { selectedDownloadSeries == null },
                            onToggleExpand = if (selectedDownloadSeries == null) {
                                { isDownloadsExpanded = !isDownloadsExpanded }
                            } else {
                                null
                            },
                        )
                    }
                    if (downloadSeries.isEmpty()) {
                        item { EmptyPanel("Download a native-stream episode from its watch page") }
                    } else if (selectedDownloadSeries != null) {
                        val series = selectedDownloadSeries
                        val categories = series.episodes.map { it.metadata.category }.distinct()
                        val categoryEpisodes = series.episodes.filter {
                            selectedDownloadCategory == null || it.metadata.category == selectedDownloadCategory
                        }
                        // Long-runners get the same range chunking as the streaming episode browser.
                        val blocks = offlineEpisodeBlocks(categoryEpisodes)
                        val blockIndex = selectedDownloadBlock.coerceAtMost(blocks.lastIndex.coerceAtLeast(0))
                        val visibleEpisodes = blocks.getOrNull(blockIndex)?.episodes.orEmpty()
                        item(key = "dl_series_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = device.pagePadding, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { selectedDownloadSeriesId = null },
                                    modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to all downloads",
                                    )
                                }
                                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                    Text(
                                        series.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Black,
                                    )
                                    Text(
                                        listOfNotNull(
                                            "${series.playableCount} of ${series.episodes.size} offline",
                                            formatDownloadBytes(series.totalBytes).takeIf { series.totalBytes > 0 },
                                            "${series.activeCount} downloading".takeIf { series.activeCount > 0 },
                                        ).joinToString(" \u00b7 "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { showDownloadsRemoveAll = true },
                                    modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Remove all")
                                }
                            }
                        }
                        if (categories.size > 1) {
                            item(key = "dl_series_categories") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = device.pagePadding),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilterChip(
                                        selected = selectedDownloadCategory == null,
                                        onClick = { selectedDownloadCategory = null },
                                        label = { Text("All") },
                                        modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                                    )
                                    categories.forEach { category ->
                                        FilterChip(
                                            selected = selectedDownloadCategory == category,
                                            onClick = { selectedDownloadCategory = category },
                                            label = { Text(category.uppercase()) },
                                            modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                                        )
                                    }
                                }
                            }
                        }
                        if (blocks.size > 1) {
                            item(key = "dl_series_blocks") {
                                LazyRow(
                                    modifier = Modifier.focusGroup(),
                                    contentPadding = PaddingValues(horizontal = device.pagePadding),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(blocks.size) { index ->
                                        FilterChip(
                                            selected = blockIndex == index,
                                            onClick = { selectedDownloadBlock = index },
                                            label = { Text(blocks[index].label) },
                                            modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                                        )
                                    }
                                }
                            }
                        }
                        val downloadColumns = if (device.isTv) 3 else 2
                        itemsIndexed(
                            visibleEpisodes.chunked(downloadColumns),
                            key = { rowIndex, _ -> "dl_series_row_$rowIndex" },
                        ) { _, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = device.pagePadding, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (episode in row) {
                                    EpisodeDownloadCard(
                                        episode = episode,
                                        exportStatus = exportStatuses[episode.id],
                                        onPlay = { onPlayDownload(episode.id) },
                                        onPauseToggle = {
                                            episode.download?.let { download ->
                                                if (download.isPaused) EpisodeDownloads.resume(context, episode.id)
                                                else EpisodeDownloads.pause(context, episode.id)
                                            }
                                        },
                                        onRemove = { removeOfflineEpisode(context, episode) },
                                        onExport = { EpisodeExport.request(context, episode.id) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(downloadColumns - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else if (isDownloadsExpanded) {
                        val downloadColumns = if (device.isTv) 3 else 2
                        val downloadRows = downloadSeries.chunked(downloadColumns)
                        itemsIndexed(downloadRows, key = { rowIndex, _ -> "dl_row_$rowIndex" }) { _, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = device.pagePadding, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (series in row) {
                                    SeriesDownloadCard(
                                        series = series,
                                        onClick = { selectedDownloadSeriesId = series.anilistId },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(downloadColumns - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        item {
                            LazyRow(
                                modifier = Modifier.focusGroup(),
                                contentPadding = PaddingValues(horizontal = device.pagePadding),
                                horizontalArrangement = Arrangement.spacedBy(if (device.isTv) 18.dp else 12.dp),
                            ) {
                                items(downloadSeries, key = OfflineSeries::anilistId) { series ->
                                    SeriesDownloadCard(
                                        series = series,
                                        onClick = { selectedDownloadSeriesId = series.anilistId },
                                    )
                                }
                            }
                        }
                    }
                }

                FastScrollbar(
                    state = profileListState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + LocalAppChromeBottomInset.current + 8.dp,
                            end = 2.dp,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ProfileHero(
    loggedIn: Boolean,
    state: UiState<AniListProfile>?,
    onLogin: (AccountService) -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        if (!loggedIn) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Your anime, in one place", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text(
                    "Connect AniList or MyAnimeList to browse every list, score, and episode progress from Anilili.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onLogin(AccountService.ANILIST) },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(9.dp)),
                    ) {
                        Text("Login with AniList", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onLogin(AccountService.MAL) },
                        modifier = Modifier.focusHighlight(RoundedCornerShape(9.dp)),
                    ) {
                        Text("Login with MAL", fontWeight = FontWeight.Bold)
                    }
                }
            }
            return
        }

        when (state) {
            is UiState.Success -> {
                val viewer = state.data.viewer
                // The tall banner exists to show the account's cover art. Without one it is just
                // an empty box above the avatar, so collapse it to what the avatar and totals
                // actually occupy instead of reserving room for a picture that never arrives.
                val hasBanner = !viewer.bannerImage.isNullOrBlank()
                val bannerHeight = when {
                    device.isTv -> if (hasBanner) 230.dp else 148.dp
                    hasBanner -> 180.dp
                    else -> 124.dp
                }
                Box(Modifier.fillMaxWidth().height(bannerHeight)) {
                    AsyncImage(
                        model = viewer.bannerImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surface,
                            ),
                        ),
                    )
                    AsyncImage(
                        model = viewer.avatar?.large,
                        contentDescription = viewer.name,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                            .size(if (device.isTv) 104.dp else 88.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    // Level with the avatar, in the banner space that was already empty. The
                    // gradient above has faded to the surface colour by this point, so the figures
                    // read as cleanly here as they did in their own card.
                    ProfileStatsInline(
                        state.data,
                        Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            viewer.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            // A long account name gives way to the sync/logout buttons rather
                            // than wrapping and doubling the row's height.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull("Signed in via ${state.data.service.label}", joinedLabel(viewer.createdAt)).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onSync, modifier = Modifier.focusHighlight(RoundedCornerShape(9.dp))) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync AniList")
                    }
                    IconButton(onClick = onLogout, modifier = Modifier.focusHighlight(RoundedCornerShape(9.dp))) {
                        Icon(Icons.Default.Close, contentDescription = "Logout")
                    }
                }
            }
            is UiState.Error -> Column(Modifier.padding(18.dp)) {
                Text("Your lists could not be loaded", fontWeight = FontWeight.Bold)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = onSync) { Text("Try again") }
                    TextButton(onClick = onLogout) { Text("Logout") }
                }
            }
            else -> Text("Syncing your profile…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
    }
}

/** The three totals, sized to sit alongside the avatar on the banner. */
@Composable
private fun ProfileStatsInline(profile: AniListProfile, modifier: Modifier = Modifier) {
    val stats = profile.viewer.statistics?.anime
    val days = (stats?.minutesWatched ?: 0L) / 1440.0
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        ProfileStat((stats?.count ?: 0).toString(), "Anime")
        ProfileStat(String.format(Locale.US, "%.1f", days), "Days")
        ProfileStat(String.format(Locale.US, "%.1f", stats?.meanScore ?: 0.0), "Score")
    }
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryFilters(
    selectedView: LibraryView,
    onViewChange: (LibraryView) -> Unit,
    selectedFormat: String?,
    onFormatChange: (String?) -> Unit,
    selectedAiring: String?,
    onAiringChange: (String?) -> Unit,
    selectedSort: LibrarySort,
    onSortChange: (LibrarySort) -> Unit,
    titleFilter: String,
    onTitleFilterChange: (String) -> Unit,
    resultCount: Int,
    showAniListLists: Boolean,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val viewOptions = (if (showAniListLists) LibraryView.entries else listOf(LibraryView.WATCHLIST))
        .map { SelectOption(it.name, it.label) }
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectorField(
                value = selectedView.label,
                options = viewOptions,
                onSelect = { value -> LibraryView.entries.firstOrNull { it.name == value }?.let(onViewChange) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectorField(
                    value = formatOptions.first { it.value == selectedFormat }.label,
                    options = formatOptions,
                    onSelect = onFormatChange,
                    modifier = Modifier.weight(1f),
                )
                SelectorField(
                    value = airingOptions.first { it.value == selectedAiring }.label,
                    options = airingOptions,
                    onSelect = onAiringChange,
                    modifier = Modifier.weight(1f),
                )
            }
            SelectorField(
                value = sortOptions.first { it.value == selectedSort.name }.label,
                options = sortOptions,
                onSelect = { value -> LibrarySort.entries.firstOrNull { it.name == value }?.let(onSortChange) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (device.isTv) {
                TvNativeTextField(
                    value = titleFilter,
                    onValueChange = onTitleFilterChange,
                    hint = "Filter by title",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = titleFilter,
                    onValueChange = onTitleFilterChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter by title") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (titleFilter.isNotEmpty()) {
                        { IconButton(onClick = { onTitleFilterChange("") }) { Icon(Icons.Default.Close, contentDescription = "Clear title filter") } }
                    } else null,
                    shape = RoundedCornerShape(9.dp),
                    singleLine = true,
                )
            }
            Text(
                "$resultCount anime",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectorField(
    value: String,
    options: List<SelectOption>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(9.dp)).clickable { expanded = true },
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { expanded = false; onSelect(option.value) },
                )
            }
        }
    }
}

@Composable
private fun ProfileSectionTitle(
    title: String,
    subtitle: String,
    isExpanded: Boolean? = null,
    onToggleExpand: (() -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = device.pagePadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onToggleExpand != null && isExpanded != null) {
            TextButton(
                onClick = onToggleExpand,
                modifier = Modifier.focusHighlight(RoundedCornerShape(8.dp)),
            ) {
                Icon(
                    if (isExpanded) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isExpanded) "Carousel" else "Expand")
            }
        }
    }
}

@Composable
private fun EpisodeDownloadCard(
    episode: OfflineEpisode,
    exportStatus: EpisodeExportStatus?,
    onPlay: () -> Unit,
    onPauseToggle: () -> Unit,
    onRemove: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val download = episode.download
    val metadata = episode.metadata
    // The corner tick already says "saved"; the overlay is only for work still in flight.
    val coverState = remember(download, exportStatus, episode.exported) {
        episodeDownloadBadges(
            downloads = listOfNotNull(download),
            exportStatuses = listOfNotNull(exportStatus).associateBy { it.downloadId },
            exported = listOfNotNull(episode.exported),
        )[episode.id]?.takeIf { it.isBusy }
    }
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(12.dp)
    val cardModifier = if (modifier != Modifier) {
        modifier.focusHighlight(shape)
    } else {
        Modifier.width(if (device.isTv) 320.dp else 270.dp).focusHighlight(shape)
    }
    Surface(
        modifier = cardModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = metadata.artworkUrl,
                    contentDescription = metadata.seriesTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.4f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.82f),
                            ),
                        ),
                )
                Text(
                    "EP ${metadata.episodeNumber} · ${metadata.category.uppercase()}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
                if (episode.isPlayable) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    )
                }
                // Same cue as the watch and detail lists, so an in-flight episode looks identical
                // wherever the viewer happens to be looking at it.
                DownloadCoverBadge(
                    state = coverState,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    metadata.seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metadata.episodeTitle?.takeIf(String::isNotBlank)
                        ?: "Episode ${metadata.episodeNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.isDownloading) {
                    LinearProgressIndicator(
                        progress = { (download?.percent ?: 0f) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    listOfNotNull(
                        // An episode that only exists as an MP4 has no Media3 download state left
                        // to report, so say where it lives instead.
                        download?.state?.displayLabel(download.percent) ?: "In Downloads folder",
                        episode.sizeBytes.takeIf { it > 0 }?.let(::formatDownloadBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (download?.state == EpisodeDownloadState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // A completed export is already spelled out by the status line above once the
                // library copy is gone; only say it twice while both copies exist.
                if (exportStatus != null && !(exportStatus.state == EpisodeExportState.COMPLETED && download == null)) {
                    if (exportStatus.state == EpisodeExportState.RUNNING) {
                        LinearProgressIndicator(
                            progress = { (exportStatus.percent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        when (exportStatus.state) {
                            EpisodeExportState.PENDING -> "Queued for Downloads"
                            EpisodeExportState.RUNNING -> exportStatus.percent
                                ?.let { "Building MP4 · $it%" }
                                ?: "Building MP4…"
                            EpisodeExportState.COMPLETED -> "Saved to Downloads"
                            EpisodeExportState.FAILED ->
                                exportStatus.error ?: "Could not save to Downloads"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (exportStatus.state == EpisodeExportState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    download?.takeIf { it.canPause || it.canResume }?.let { controlledDownload ->
                        IconButton(onClick = onPauseToggle) {
                            Icon(
                                if (controlledDownload.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (controlledDownload.isPaused) {
                                    "Resume download"
                                } else {
                                    "Pause download"
                                },
                            )
                        }
                    }
                    if (episode.isPlayable) {
                        // Offered per episode rather than only at download time, so one already
                        // sitting in the library can be lifted out to Downloads later.
                        if (
                            EpisodeExport.isSupported &&
                            download?.isComplete == true &&
                            !episode.isInDownloadsFolder
                        ) {
                            TextButton(
                                onClick = onExport,
                                enabled = exportStatus == null ||
                                    exportStatus.state == EpisodeExportState.FAILED,
                            ) {
                                Icon(Icons.Default.SaveAlt, contentDescription = null)
                                Text("MP4", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                        TextButton(onClick = onPlay) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("Play", modifier = Modifier.padding(start = 4.dp))
                        }
                    } else {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove download")
                    }
                }
            }
        }
    }
}

/**
 * One anime's offline episodes as a single card. The flat per-episode list this replaced was
 * unmanageable for long-runners (180+ episodes of one show); the card opens the series view,
 * which is where individual episodes are played, paused, exported, or removed.
 */
@Composable
private fun SeriesDownloadCard(
    series: OfflineSeries,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val shape = RoundedCornerShape(12.dp)
    val cardModifier = (if (modifier != Modifier) {
        modifier
    } else {
        Modifier.width(if (device.isTv) 320.dp else 270.dp)
    })
        .focusHighlight(shape)
        .clickable(onClickLabel = "Open ${series.title} downloads", onClick = onClick)
    Surface(
        modifier = cardModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = series.artworkUrl,
                    contentDescription = series.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.4f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.82f),
                            ),
                        ),
                )
                Text(
                    "${series.episodes.size} EP",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
                if (series.activeCount > 0) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Download in progress",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    )
                }
            }
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    series.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        "${series.playableCount} of ${series.episodes.size} offline",
                        formatDownloadBytes(series.totalBytes).takeIf { series.totalBytes > 0 },
                    ).joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Removes an episode from the offline library completely, whichever copies of it exist — the
 * cached segments, the MP4 in Downloads, or both. Deleting only half of it would leave a card
 * behind that still looks downloaded.
 */
private fun removeOfflineEpisode(context: android.content.Context, episode: OfflineEpisode) {
    if (episode.download != null) EpisodeDownloads.remove(context, episode.id)
    if (episode.exported != null) EpisodeExport.deleteExported(context, episode.id)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    onResume: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    var actionsVisible by remember { mutableStateOf(false) }
    if (actionsVisible) {
        ContinueWatchingActionsDialog(entry = entry, onDismiss = { actionsVisible = false })
    }
    val cardModifier = if (modifier != Modifier) {
        modifier
            .focusHighlight()
            .combinedClickable(
                onClickLabel = "Resume ${entry.title}",
                onLongClickLabel = "Manage Continue Watching",
                onClick = { onResume(entry) },
                onLongClick = { actionsVisible = true },
            )
    } else {
        Modifier
            .width(device.posterWidth)
            .focusHighlight()
            .combinedClickable(
                onClickLabel = "Resume ${entry.title}",
                onLongClickLabel = "Manage Continue Watching",
                onClick = { onResume(entry) },
                onLongClick = { actionsVisible = true },
            )
    }
    Column(cardModifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = .4f))) {
                Box(Modifier.fillMaxWidth(entry.progressFraction.coerceAtLeast(.02f)).height(4.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        Text(entry.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        Text("EP ${entry.episodeLabel}  ·  ${entry.provider}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun EpisodeDownloadState.displayLabel(percent: Float?): String = when (this) {
    EpisodeDownloadState.QUEUED -> "Queued"
    EpisodeDownloadState.DOWNLOADING -> percent?.let { "Downloading ${it.toInt()}%" } ?: "Downloading"
    EpisodeDownloadState.COMPLETED -> "Available offline"
    EpisodeDownloadState.FAILED -> "Download failed"
    EpisodeDownloadState.REMOVING -> "Removing"
    EpisodeDownloadState.RESTARTING -> "Restarting"
    EpisodeDownloadState.STOPPED -> percent?.let { "Paused at ${it.toInt()}%" } ?: "Paused"
}

private fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
}

@Composable
private fun SavedAnimeCard(
    entry: SavedAnimeCardData,
    onAnimeClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val cardModifier = if (modifier != Modifier) {
        modifier.focusHighlight().clickable { onAnimeClick(entry.id) }
    } else {
        Modifier.width(device.posterWidth).focusHighlight().clickable { onAnimeClick(entry.id) }
    }
    Column(cardModifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(entry.cover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            when {
                entry.userScore != null -> CornerBadge(
                    text = String.format(Locale.US, "★ %.1f", entry.userScore),
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
                )
                entry.averageScore != null -> RatingBadge(entry.averageScore, Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            entry.progress?.let { progress ->
                CornerBadge(
                    text = "$progress/${entry.totalEpisodes ?: "?"}",
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
                )
            }
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            listOfNotNull(entry.status?.toDisplayLabel(), entry.format?.replace('_', ' ')).joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CornerBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(5.dp),
        color = Color.Black.copy(alpha = .82f),
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyPanel(text: String) {
    val device = LocalAppDeviceProfile.current
    Panel(Modifier.padding(horizontal = device.pagePadding)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content,
    )
}

private fun joinedLabel(createdAt: Long?): String? {
    if (createdAt == null || createdAt <= 0) return null
    return runCatching {
        "Joined " + Instant.ofEpochSecond(createdAt)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM yyyy"))
    }.getOrNull()
}

private fun buildCombinedWatchlist(
    profile: AniListProfile?,
    local: List<WatchlistEntry>,
    history: List<HistoryEntry>,
): List<SavedAnimeCardData> {
    val aniListPlanning = profile?.planning.orEmpty().distinctBy { it.media?.id }
    val byMediaId = aniListPlanning.mapNotNull { entry -> entry.media?.id?.let { it to entry } }.toMap()
    val historyById = history.associateBy { it.anilistId }

    return buildList {
        local.forEach { saved ->
            val aniListEntry = byMediaId[saved.anilistId]
            val media = aniListEntry?.media
            add(
                SavedAnimeCardData(
                    id = saved.anilistId,
                    title = media?.title?.preferred ?: saved.title,
                    cover = media?.coverImage?.best ?: saved.cover,
                    format = media?.format ?: saved.format,
                    airingStatus = media?.status,
                    status = aniListEntry?.status,
                    userScore = aniListEntry?.score?.takeIf { it > 0 },
                    averageScore = media?.averageScore ?: saved.averageScore,
                    progress = aniListEntry?.progress ?: historyById[saved.anilistId]?.episodeNumber?.toInt(),
                    totalEpisodes = media?.episodes,
                    updatedAt = aniListEntry?.updatedAt?.takeIf { it > 0 } ?: (saved.addedAt / 1000L),
                ),
            )
        }
        aniListPlanning.forEach { aniListEntry ->
            val media = aniListEntry.media ?: return@forEach
            if (local.any { it.anilistId == media.id }) return@forEach
            add(
                SavedAnimeCardData(
                    id = media.id,
                    title = media.title.preferred,
                    cover = media.coverImage.best,
                    format = media.format,
                    airingStatus = media.status,
                    status = aniListEntry.status,
                    userScore = aniListEntry.score.takeIf { it > 0 },
                    averageScore = media.averageScore,
                    progress = aniListEntry.progress,
                    totalEpisodes = media.episodes,
                    updatedAt = aniListEntry.updatedAt,
                ),
            )
        }
    }
}

private fun aniListCards(entries: List<MediaListEntry>): List<SavedAnimeCardData> =
    entries.mapNotNull { entry ->
        val media = entry.media ?: return@mapNotNull null
        SavedAnimeCardData(
            id = media.id,
            title = media.title.preferred,
            cover = media.coverImage.best,
            format = media.format,
            airingStatus = media.status,
            status = entry.status,
            userScore = entry.score.takeIf { it > 0 },
            averageScore = media.averageScore,
            progress = entry.progress,
            totalEpisodes = media.episodes,
            updatedAt = entry.updatedAt,
        )
    }.distinctBy { it.id }

private fun String.toDisplayLabel(): String = when (this) {
    "CURRENT" -> "Watching"
    "REPEATING" -> "Re-watching"
    "PLANNING" -> "Planning"
    "PAUSED" -> "Paused"
    else -> lowercase().replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
