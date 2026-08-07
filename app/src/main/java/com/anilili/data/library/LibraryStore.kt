package com.anilili.data.library

import android.content.Context
import android.content.SharedPreferences
import com.anilili.data.reminder.ReleaseSyncScheduler
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.model.MediaListEntry
import com.anilili.data.remote.MalProgressSyncWorker
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * On-device library: watch history (continue-watching + resume position) and watchlist.
 * Persisted as JSON in SharedPreferences; exposed as StateFlows so the UI reacts. No login.
 */
object LibraryStore {
    private const val KEY_HISTORY = "history"
    private const val KEY_WATCHLIST = "watchlist"
    private const val KEY_REMOTE_STATUSES = "remote_statuses"
    private const val KEY_DISMISSED_REMOTE_HISTORY = "dismissed_remote_history"
    private const val KEY_LIKES = "likes_media"
    private const val KEY_DISLIKES = "dislikes_media"
    private const val MAX_HISTORY = 100
    private const val REMOTE_REFRESH_INTERVAL_MS = 30_000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aniListSyncMutex = Mutex()
    private var remoteRefreshJob: Job? = null
    @Volatile private var lastRemoteRefreshAt = 0L

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history = _history.asStateFlow()

    private val _watchlist = MutableStateFlow<List<WatchlistEntry>>(emptyList())
    val watchlist = _watchlist.asStateFlow()

    private val _remoteStatuses = MutableStateFlow<Map<Int, String>>(emptyMap())
    val remoteStatuses = _remoteStatuses.asStateFlow()
    private var dismissedRemoteHistory = emptySet<Int>()

    private val _likes = MutableStateFlow<Set<Int>>(emptySet())
    val likes = _likes.asStateFlow()

    private val _dislikes = MutableStateFlow<Set<Int>>(emptySet())
    val dislikes = _dislikes.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("miruro_library", Context.MODE_PRIVATE)
        val storedHistory = decodeList(prefs.getString(KEY_HISTORY, null), HistoryEntry.serializer())
            .map(::seedLegacyWatchedEpisodes)
        val orderedHistory = sortHistoryLatestFirst(storedHistory).take(MAX_HISTORY)
        _history.value = orderedHistory
        if (orderedHistory != storedHistory) {
            persist(KEY_HISTORY, orderedHistory, HistoryEntry.serializer())
        }
        _watchlist.value = decodeList(prefs.getString(KEY_WATCHLIST, null), WatchlistEntry.serializer())
        _remoteStatuses.value = decodeList(
            prefs.getString(KEY_REMOTE_STATUSES, null),
            RemoteListStatus.serializer(),
        ).associate { it.anilistId to it.status }
        dismissedRemoteHistory = prefs.getStringSet(KEY_DISMISSED_REMOTE_HISTORY, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()

        _likes.value = prefs.getStringSet(KEY_LIKES, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()
        _dislikes.value = prefs.getStringSet(KEY_DISLIKES, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()
    }

    // ---- Likes & Dislikes ----

    fun isLiked(anilistId: Int): Boolean = _likes.value.contains(anilistId)
    fun isDisliked(anilistId: Int): Boolean = _dislikes.value.contains(anilistId)

    fun toggleLike(anilistId: Int) {
        val currentLikes = _likes.value
        val currentDislikes = _dislikes.value
        val newLikes: Set<Int>
        val newDislikes: Set<Int>
        if (currentLikes.contains(anilistId)) {
            newLikes = currentLikes - anilistId
            newDislikes = currentDislikes
        } else {
            newLikes = currentLikes + anilistId
            newDislikes = currentDislikes - anilistId
        }
        _likes.value = newLikes
        _dislikes.value = newDislikes
        prefs.edit()
            .putStringSet(KEY_LIKES, newLikes.map(Int::toString).toSet())
            .putStringSet(KEY_DISLIKES, newDislikes.map(Int::toString).toSet())
            .apply()
    }

    fun toggleDislike(anilistId: Int) {
        val currentLikes = _likes.value
        val currentDislikes = _dislikes.value
        val newLikes: Set<Int>
        val newDislikes: Set<Int>
        if (currentDislikes.contains(anilistId)) {
            newDislikes = currentDislikes - anilistId
            newLikes = currentLikes
        } else {
            newDislikes = currentDislikes + anilistId
            newLikes = currentLikes - anilistId
        }
        _likes.value = newLikes
        _dislikes.value = newDislikes
        prefs.edit()
            .putStringSet(KEY_LIKES, newLikes.map(Int::toString).toSet())
            .putStringSet(KEY_DISLIKES, newDislikes.map(Int::toString).toSet())
            .apply()
    }

    // ---- history ----

    /** Insert/replace the anime's record (keeps one per anime, most-recent first). */
    fun upsertHistory(entry: HistoryEntry) {
        if (entry.anilistId in dismissedRemoteHistory) {
            dismissedRemoteHistory = dismissedRemoteHistory - entry.anilistId
            persistDismissedRemoteHistory()
        }
        val stamped = entry.copy(updatedAt = System.currentTimeMillis())
        val updated = sortHistoryLatestFirst(
            buildList {
                add(stamped)
                addAll(_history.value.filter { it.anilistId != entry.anilistId })
            },
        ).take(MAX_HISTORY)
        _history.value = updated
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
        // TV launchers surface in-progress titles in their Continue Watching row; publishing is
        // throttled inside the manager and a no-op off Android TV.
        scope.launch { WatchNextManager.publish(appContext, stamped) }
    }

    fun updateProgress(anilistId: Int, episodeNumber: Double, positionMs: Long, durationMs: Long) {
        val existing = _history.value.firstOrNull { it.anilistId == anilistId } ?: return
        if (existing.episodeNumber != episodeNumber) return
        upsertHistory(existing.copy(positionMs = positionMs, durationMs = durationMs))
    }

    /**
     * Advance the local Continue Watching row as soon as playback counts an episode as watched.
     * This is intentionally independent from account sync: local history remains correct even
     * while offline, logged out, or when a remote service rejects an update.
     */
    fun markEpisodeWatched(
        anilistId: Int,
        watchedEpisode: Double,
        nextEpisode: Double?,
        nextEpisodeTitle: String?,
        seriesCompleted: Boolean,
    ) {
        val existing = historyFor(anilistId) ?: return
        val updated = historyAfterEpisodeWatched(
            existing = existing,
            watchedEpisode = watchedEpisode,
            nextEpisode = nextEpisode,
            nextEpisodeTitle = nextEpisodeTitle,
            seriesCompleted = seriesCompleted,
        )
        if (updated == existing) return
        if (updated == null) {
            DiagnosticsLog.event(
                "Continue Watching completed id=$anilistId episode=${existing.episodeLabel}",
            )
            // Prevent a still-stale remote CURRENT record from immediately recreating the row.
            removeHistory(anilistId, dismissRemoteSeed = AccountService.active != null)
        } else {
            DiagnosticsLog.event(
                "Continue Watching advanced id=$anilistId from=${existing.episodeLabel} " +
                    "to=${updated.episodeLabel}",
            )
            upsertHistory(updated)
        }
    }

    /** Persist MAL progress delivery across navigation, process death, and lost connectivity. */
    fun enqueueMalProgressSync(anilistId: Int, progress: Int, totalEpisodes: Int?) {
        MalProgressSyncWorker.enqueue(appContext, anilistId, progress, totalEpisodes)
    }

    fun historyFor(anilistId: Int): HistoryEntry? = _history.value.firstOrNull { it.anilistId == anilistId }

    /**
     * Remove one Continue Watching row. With [dismissRemoteSeed], account sync cannot immediately
     * recreate it; actually playing the title again clears that dismissal.
     */
    fun removeHistory(anilistId: Int, dismissRemoteSeed: Boolean = true) {
        val updated = _history.value.filter { it.anilistId != anilistId }
        if (updated == _history.value) return
        _history.value = updated
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
        if (dismissRemoteSeed && AccountService.active != null) {
            dismissedRemoteHistory = dismissedRemoteHistory + anilistId
            persistDismissedRemoteHistory()
        }
        scope.launch { WatchNextManager.remove(appContext, anilistId) }
    }

    fun clearHistory() {
        if (AccountService.active != null) {
            dismissedRemoteHistory = dismissedRemoteHistory + _history.value.map(HistoryEntry::anilistId)
            persistDismissedRemoteHistory()
        }
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
        scope.launch { WatchNextManager.removeAll(appContext) }
    }

    // ---- watchlist ----

    fun isInWatchlist(anilistId: Int): Boolean = _watchlist.value.any { it.anilistId == anilistId }

    fun toggleWatchlist(entry: WatchlistEntry) {
        val updated = if (isInWatchlist(entry.anilistId)) {
            _watchlist.value.filter { it.anilistId != entry.anilistId }
        } else {
            listOf(entry.copy(addedAt = System.currentTimeMillis())) + _watchlist.value
        }
        _watchlist.value = updated
        persist(KEY_WATCHLIST, updated, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
        val service = AccountService.active
        // Catalogue-native hanime entries carry a negative, hanime-owned id. Posting one to
        // AniList or MAL would be a request about somebody else's anime, so they never sync.
        val syncable = !com.anilili.data.remote.isHanimeMediaId(entry.anilistId)
        if (service != null && syncable && SettingsStore.syncSavedToAniList.value) {
            val saved = updated.any { it.anilistId == entry.anilistId }
            scope.launch {
                aniListSyncMutex.withLock {
                    runCatching {
                        when (service) {
                            AccountService.ANILIST -> AppGraph.repository.syncSavedAnime(entry.anilistId, saved)
                            AccountService.MAL -> AppGraph.repository.malSyncSavedAnime(entry.anilistId, saved)
                        }
                    }.onSuccess {
                        refreshRemoteLibrary(force = true)
                    }.onFailure {
                        com.anilili.diagnostics.DiagnosticsLog.throwable(
                            "${service.label} saved sync failed id=${entry.anilistId} saved=$saved",
                            it,
                        )
                    }
                }
            }
        }
    }

    /** Push the whole device watchlist to whichever list service is signed in. */
    fun syncSavedToRemote() {
        val service = AccountService.active ?: return
        if (!SettingsStore.syncSavedToAniList.value) return
        val savedIds = _watchlist.value.map { it.anilistId }
        scope.launch {
            aniListSyncMutex.withLock {
                runCatching {
                    when (service) {
                        AccountService.ANILIST -> AppGraph.repository.syncSavedAnime(savedIds)
                        AccountService.MAL -> AppGraph.repository.malSyncSavedAnime(savedIds)
                    }
                }.onSuccess {
                    refreshRemoteLibrary(force = true)
                }.onFailure {
                    com.anilili.diagnostics.DiagnosticsLog.throwable(
                        "${service.label} watchlist sync failed (${savedIds.size} titles)",
                        it,
                    )
                }
            }
        }
    }

    /**
     * Bulk add from a MAL XML import. Existing saves keep their position and addedAt; the
     * whole batch syncs to the signed-in list service in one push rather than per title.
     * Returns how many entries were actually new.
     */
    fun importWatchlist(entries: List<WatchlistEntry>): Int {
        val current = _watchlist.value
        val existing = current.mapTo(mutableSetOf()) { it.anilistId }
        val now = System.currentTimeMillis()
        val newEntries = entries
            .distinctBy { it.anilistId }
            .filter { it.anilistId !in existing }
            .map { it.copy(addedAt = now) }
        if (newEntries.isEmpty()) return 0
        val updated = newEntries + current
        _watchlist.value = updated
        persist(KEY_WATCHLIST, updated, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
        syncSavedToRemote()
        return newEntries.size
    }

    /** Merge AniList Planning into this device without deleting device-only saves. */
    fun hydrateWatchlistFromAniList(entries: List<WatchlistEntry>) {
        val merged = mergeWatchlistEntries(_watchlist.value, entries)
        if (merged == _watchlist.value) return
        _watchlist.value = merged
        persist(KEY_WATCHLIST, merged, WatchlistEntry.serializer())
        ReleaseSyncScheduler.runNow(appContext)
    }

    /**
     * Publish a freshly fetched remote collection to every screen immediately. Unlike the local
     * watchlist, this includes every list state so Detail can distinguish Watching, Completed,
     * Paused, and Dropped from a title that truly is not tracked.
     */
    fun hydrateRemoteLibrary(entries: List<MediaListEntry>) {
        val statuses = remoteListStatuses(entries)
        _remoteStatuses.value = statuses
        persist(
            KEY_REMOTE_STATUSES,
            statuses.map { (id, status) -> RemoteListStatus(id, status) },
            RemoteListStatus.serializer(),
        )
        lastRemoteRefreshAt = System.currentTimeMillis()
        seedHistoryFromRemote(entries)

        if (SettingsStore.syncSavedToAniList.value) {
            hydrateWatchlistFromAniList(
                entries.mapNotNull { entry ->
                    if (entry.status != "PLANNING") return@mapNotNull null
                    val media = entry.media ?: return@mapNotNull null
                    WatchlistEntry(
                        anilistId = media.id,
                        title = media.title.preferred,
                        cover = media.coverImage.best,
                        format = media.format,
                        averageScore = media.averageScore,
                    )
                },
            )
        }
    }

    /**
     * Continue Watching from the signed-in service: every title it says the user is watching
     * becomes a resumable row pointing at the next unwatched episode, so a fresh install isn't
     * blank. Real playback records always win — seeded rows sit behind them, are replaced
     * wholesale on every refresh (remote progress moves them forward), get superseded by a real
     * record the moment the title is played here, and vanish on logout. The "auto" provider is
     * the same sentinel Watch Now uses when nothing local exists: the watch screen resolves a
     * source itself.
     */
    private fun seedHistoryFromRemote(entries: List<MediaListEntry>) {
        val preferDub = SettingsStore.preferDub.value
        val local = _history.value.filterNot { it.fromRemote }
        val localIds = local.mapTo(mutableSetOf()) { it.anilistId }
        val seeded = entries.mapNotNull { entry ->
            if (entry.status != "CURRENT" && entry.status != "REPEATING") return@mapNotNull null
            val media = entry.media ?: return@mapNotNull null
            if (media.id in dismissedRemoteHistory) return@mapNotNull null
            if (media.id in localIds) return@mapNotNull null
            val nextEpisode = entry.progress + 1
            val total = media.episodes
            if (total != null && total > 0 && nextEpisode > total) return@mapNotNull null
            HistoryEntry(
                anilistId = media.id,
                title = media.title.preferred,
                cover = media.coverImage.best,
                episodeNumber = nextEpisode.toDouble(),
                provider = "auto",
                category = if (preferDub) "dub" else "sub",
                // Continue Watching is ordered by updatedAt, so a seeded row left at 0 sank below
                // every locally played title and, past MAX_HISTORY, fell off the list entirely —
                // a fresh install signed into an existing account looked like it had synced
                // nothing. The list service's own timestamp is exactly the "last watched" this
                // ordering wants; it is in seconds.
                updatedAt = entry.updatedAt * 1_000L,
                fromRemote = true,
                // AniList and MAL only store a count, not which episodes were played, so the
                // count is the best statement available: everything up to it counts as watched.
                watchedEpisodes = generateSequence(1.0) { it + 1.0 }
                    .takeWhile { it <= entry.progress }
                    .toSet(),
            )
        }
        val updated = sortHistoryLatestFirst(local + seeded).take(MAX_HISTORY)
        if (updated == _history.value) return
        _history.value = updated
        // No WatchNextManager publish for seeded rows: the launcher's Continue Watching channel
        // is reserved for titles actually played on this device.
        persist(KEY_HISTORY, updated, HistoryEntry.serializer())
    }

    /** Update the snapshot synchronously after playback changes an AniList/MAL list state. */
    fun updateRemoteStatus(anilistId: Int, status: String?) {
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val updated = _remoteStatuses.value.toMutableMap().apply {
            if (normalized == null) remove(anilistId) else put(anilistId, normalized)
        }
        _remoteStatuses.value = updated
        persist(
            KEY_REMOTE_STATUSES,
            updated.map { (id, value) -> RemoteListStatus(id, value) },
            RemoteListStatus.serializer(),
        )
    }

    /** Refresh on cold start/foreground, while bounding AniList's shared API rate limit. */
    @Synchronized
    fun refreshRemoteLibrary(force: Boolean = false) {
        val service = AccountService.active
        if (service == null) {
            if (_remoteStatuses.value.isNotEmpty()) clearRemoteLibrary()
            return
        }
        val now = System.currentTimeMillis()
        if (remoteRefreshJob?.isActive == true) return
        if (!force && now - lastRemoteRefreshAt < REMOTE_REFRESH_INTERVAL_MS) return
        lastRemoteRefreshAt = now
        remoteRefreshJob = scope.launch {
            runCatching {
                aniListSyncMutex.withLock {
                    val entries = when (service) {
                        AccountService.ANILIST -> {
                            val viewerId = AuthManager.viewerId() ?: AppGraph.repository.viewer()?.id
                                ?: error("Couldn't load your AniList account")
                            val collection = AppGraph.repository.userAnimeList(viewerId)
                                ?: error("Couldn't load your AniList library")
                            collection.lists
                                .flatMap { it.entries }
                                .distinctBy { it.id }
                        }
                        AccountService.MAL -> AppGraph.repository.malAnimeList()
                    }
                    hydrateRemoteLibrary(entries)
                    com.anilili.diagnostics.DiagnosticsLog.event(
                        "${service.label} library refreshed statuses=${_remoteStatuses.value.size}",
                    )
                }
            }.onFailure {
                lastRemoteRefreshAt = 0L
                com.anilili.diagnostics.DiagnosticsLog.throwable(
                    "${service.label} library refresh failed",
                    it,
                )
            }
        }
    }

    fun clearRemoteLibrary() {
        remoteRefreshJob?.cancel()
        remoteRefreshJob = null
        lastRemoteRefreshAt = 0L
        _remoteStatuses.value = emptyMap()
        dismissedRemoteHistory = emptySet()
        prefs.edit()
            .remove(KEY_REMOTE_STATUSES)
            .remove(KEY_DISMISSED_REMOTE_HISTORY)
            .apply()
        // Seeded Continue Watching rows belong to the account that just signed out.
        val localOnly = sortHistoryLatestFirst(_history.value.filterNot { it.fromRemote })
        if (localOnly != _history.value) {
            _history.value = localOnly
            persist(KEY_HISTORY, localOnly, HistoryEntry.serializer())
        }
    }

    // ---- persistence ----

    private fun <T> persist(key: String, list: List<T>, serializer: kotlinx.serialization.KSerializer<T>) {
        val encoded = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer), list)
        prefs.edit().putString(key, encoded).apply()
    }

    private fun persistDismissedRemoteHistory() {
        prefs.edit()
            .putStringSet(KEY_DISMISSED_REMOTE_HISTORY, dismissedRemoteHistory.map(Int::toString).toSet())
            .apply()
    }

    /**
     * Gives rows written before [HistoryEntry.watchedEpisodes] existed the history the old
     * inference implied — every whole episode below the one in progress.
     *
     * Without this, a viewer 90 episodes into a series would open the app after updating and
     * find nothing marked watched. It preserves the previous (over-generous) picture for old
     * rows and lets accurate recording take over from the next episode finished. A row already
     * carrying a set, or sitting on episode 1, has nothing to seed.
     */
    private fun seedLegacyWatchedEpisodes(entry: HistoryEntry): HistoryEntry {
        if (entry.watchedEpisodes.isNotEmpty()) return entry
        val current = entry.episodeNumber
        if (current <= 1.0) return entry
        val seeded = generateSequence(1.0) { it + 1.0 }
            .takeWhile { it < current }
            .toSet()
        return entry.copy(watchedEpisodes = seeded)
    }

    private fun <T> decodeList(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer), raw)
        }.getOrDefault(emptyList())
    }
}
