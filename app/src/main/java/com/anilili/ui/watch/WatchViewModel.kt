package com.anilili.ui.watch

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.data.ProviderCatalog
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.auth.AccountService
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.DEFAULT_PREFERRED_PROVIDER
import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.hasUsableWindow
import com.anilili.data.remote.KonohaEpisode
import com.anilili.data.remote.PipeBridge
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.UiState
import com.anilili.ui.detail.mergeEpisodeMetadata
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Ignore short ad clips accidentally reported by an embed as its main video. */
private const val MIN_SKIP_LOOKUP_DURATION_MS = 60_000L

/**
 * How long the initial load waits on the Miruro pipe before proceeding with the fast Anivexa
 * catalog alone. A healthy warm pipe answers well under this; a Cloudflare-dead one takes 15 s+,
 * which used to be dead time on the loading screen. Miruro still merges in if it answers later.
 */
private const val MIRURO_CATALOG_WAIT_MS = 8_000L

data class WatchData(
    val episodes: List<EpisodeItem>,
    val currentIndex: Int,
    val provider: String,
    val category: Category,
    val sourceOptions: List<WatchSourceOption>,
    val anilistId: Int,
    val sources: SourcesResult,
    val chosenStream: StreamItem?,
    val skipTimingStatus: SkipTimingStatus = SkipTimingStatus.WAITING_FOR_DURATION,
    val seriesTitle: String,
    val artworkUrl: String?,
    val seriesFormat: String? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val description: String? = null,
    val startPositionMs: Long = 0,
    val playbackGeneration: Int = 0,
    val preferredProvider: String = DEFAULT_PREFERRED_PROVIDER,
    val isResolving: Boolean = false,
    /** The fast Miruro sources are shown; the slower Anivexa servers are still loading in. */
    val isLoadingMoreSources: Boolean = false,
    /** A bounded Miruro catalog recovery is queued or running after a failed cold start. */
    val isRetryingMiruro: Boolean = false,
    /** Track languages discovered per server/audio pair, filled in as sources are validated. */
    val capabilities: Map<Pair<String, Category>, SourceCapabilities> = emptyMap(),
    val notice: String? = null,
    val fullMedia: com.anilili.data.model.Media? = null,
) {
    val current: EpisodeItem get() = episodes[currentIndex]

    fun capabilitiesOf(option: WatchSourceOption): SourceCapabilities =
        capabilities[option.provider to option.category] ?: SourceCapabilities()

    /** Every language seen so far across all options, for the picker's filter row. */
    val knownLanguages: List<String>
        get() = capabilities.values.flatMap { it.languages }.distinct().sorted()
    val hasMiruroSources: Boolean
        get() = hasMiruroSourceOptions(sourceOptions)
    val hasNext: Boolean get() = currentIndex < episodes.lastIndex
    val hasPrev: Boolean get() = currentIndex > 0
}

data class WatchSourceOption(
    val provider: String,
    val category: Category,
    val hasCurrentEpisode: Boolean,
    val episodeCount: Int,
)

/**
 * What a server/audio pair actually turned out to offer, learned when its sources were resolved.
 *
 * The catalog only knows sub vs dub. Which spoken language a dub is in, and which subtitle tracks
 * ride along, are inside the resolved [com.anilili.data.model.SourcesResult] — so they are
 * only knowable after a resolve. The source-validation pass already performs exactly that resolve
 * for every candidate and used to keep nothing but a boolean; this is that discarded detail.
 *
 * [known] separates "we checked and it has no subtitle tracks" (common — plenty of servers ship
 * hardsubbed video) from "we have not checked yet", so the picker never claims a language is
 * missing while it is still looking.
 */
data class SourceCapabilities(
    val audioLanguages: Set<String> = emptySet(),
    val subtitleLanguages: Set<String> = emptySet(),
    val known: Boolean = false,
) {
    val languages: Set<String> get() = audioLanguages + subtitleLanguages
}

private data class EpisodeSourceKey(
    val episode: Double,
    val provider: String,
    val category: Category,
)

private data class SkipLookupKey(
    val episode: Double,
    val provider: String,
    val category: Category,
    val durationSeconds: Int,
)

class WatchViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<WatchData>>(UiState.Loading)
    val state = _state.asStateFlow()

    /** Live status line for the initial loading screen ("main source is down, trying others…"). */
    private val _loadingStatus = MutableStateFlow<String?>(null)
    val loadingStatus = _loadingStatus.asStateFlow()

    private var anilistId = 0
    private var category = Category.SUB
    private var globalPreferredProvider = DEFAULT_PREFERRED_PROVIDER
    private var preferred = ""
    private var spine: List<EpisodeItem> = emptyList()
    private var startedKey: String? = null
    private var seriesTitle = "Anime"
    private var artworkUrl: String? = null
    private var seriesFormat: String? = null
    private var averageScore: Int? = null
    private var popularity: Int? = null
    private var description: String? = null
    private var totalEpisodes: Int? = null
    private var fullMedia: com.anilili.data.model.Media? = null
    private val locallyWatchedEpisodes = mutableSetOf<Int>()
    private val scheduledRemoteProgressEpisodes = mutableSetOf<Int>()

    /** Episodes already speculatively resolved, so a preheat runs at most once each. */
    private val preheatedEpisodes = mutableSetOf<Double>()
    private var lastRequestedNumber = 1.0
    private var failedProviders = mutableSetOf<String>()
    private val unavailableSources = mutableSetOf<EpisodeSourceKey>()
    private val confirmedSources = mutableSetOf<EpisodeSourceKey>()

    /**
     * What each server/audio pair offers, keyed by provider+category and *not* by episode.
     *
     * Sampling one episode is enough: a provider's track lineup for a series does not change
     * between its episodes (checked live — AniBD returned exactly `[en]` for Dandadan episodes 1,
     * 2 and 5). Keying by episode would multiply the resolves by the episode count for data that
     * comes back identical every time.
     */
    private val sourceCapabilities = mutableMapOf<Pair<String, Category>, SourceCapabilities>()
    private val failedStreamUrls = mutableSetOf<String>()
    /** Konoha's episode titles and stills, kept so every spine rebuild carries them. */
    private var episodeMeta: List<KonohaEpisode> = emptyList()
    private var resolveJob: Job? = null
    private var episodeMetaJob: Job? = null
    private var anivexaMergeJob: Job? = null
    private var miruroLateMergeJob: Job? = null
    private var miruroRecoveryJob: Job? = null
    private var miruroAutoRetryAttempted = false
    private var miruroRecoveryPending = false
    private var sourceValidationJob: Job? = null
    private var sourceValidationIsExhaustive = false
    private var skipLookupJob: Job? = null
    private var skipLookupKey: SkipLookupKey? = null
    private var mergedIncludesAnivexa = false
    private var mergedEpisodes = EpisodesResult(emptyList())

    fun start(id: Int, providerName: String, categoryApi: String, episodeNumber: String) {
        val key = "$id/$providerName/$categoryApi/$episodeNumber"
        if (key == startedKey && _state.value is UiState.Success) {
            DiagnosticsLog.event("Watch start ignored duplicate key=$key")
            return
        }
        DiagnosticsLog.event("Watch start key=$key")
        startedKey = key
        anilistId = id
        category = Category.from(categoryApi)
        preferred = providerName
        totalEpisodes = null
        seriesTitle = "Anime"
        artworkUrl = null
        seriesFormat = null
        averageScore = null
        popularity = null
        description = null
        locallyWatchedEpisodes.clear()
        scheduledRemoteProgressEpisodes.clear()
        failedProviders.clear()
        unavailableSources.clear()
        confirmedSources.clear()
        mergedIncludesAnivexa = false
        episodeMeta = emptyList()

        resolveJob?.cancel()
        episodeMetaJob?.cancel()
        anivexaMergeJob?.cancel()
        miruroLateMergeJob?.cancel()
        miruroRecoveryJob?.cancel()
        miruroAutoRetryAttempted = false
        miruroRecoveryPending = false
        sourceValidationJob?.cancel()
        sourceValidationIsExhaustive = false
        // Runs beside source resolution: providers hand back bare numbered lists, so without this
        // the episode list here reads "Episode 5" where the Anime page shows the real title.
        episodeMetaJob = viewModelScope.launch { loadEpisodeMetadata(id) }
        resolveJob = viewModelScope.launch {
            _state.value = UiState.Loading
            _loadingStatus.value = null
            var pipeWarmSession: AutoCloseable? = null
            try {
                SettingsStore.awaitLoaded()
                // v0.1.49 was reliable because this browser was already resident. Hold one
                // bounded session instead: it starts when Watch really needs Miruro and survives
                // the catalog -> first-source hand-off, then disappears as playback begins.
                pipeWarmSession = PipeBridge.acquireWarmSession("initial-watch-$id")
                val storedPreferred = SettingsStore.preferredProvider.value
                globalPreferredProvider = storedPreferred
                preferred = preferredProviderForWatch(storedPreferred, providerName)
                DiagnosticsLog.event(
                    "Watch preferred server route=$providerName stored=$storedPreferred selected=$preferred",
                )
                DiagnosticsLog.event("Watch episodes load start id=$id")
                // Fast path: both catalogs race from the start. The fast Anivexa subset (plus the
                // preferred server) is already in flight if the Miruro pipe turns out to be slow
                // or down, and the Miruro wait is capped so a dead pipe (Cloudflare timeout)
                // can't hold the loading screen hostage — a late Miruro answer merges in behind.
                // The remaining slow Anivexa scrapers fold in via launchAnivexaMerge.
                val fastCatalog = async {
                    runCatching { repo.fastAnivexaEpisodes(id, setOf(preferred)) }
                        .onFailure { DiagnosticsLog.throwable("Watch fast anivexa episodes failed id=$id", it) }
                        .getOrDefault(EpisodesResult(emptyList()))
                }
                val miruroDeferred = async { runCatching { repo.miruroEpisodes(id) } }
                val miruroResult = withTimeoutOrNull(MIRURO_CATALOG_WAIT_MS) { miruroDeferred.await() }
                miruroResult?.exceptionOrNull()?.let {
                    DiagnosticsLog.throwable("Watch miruro episodes failed id=$id", it)
                }
                if (miruroResult == null) {
                    DiagnosticsLog.event(
                        "Watch miruro episodes still pending after ${MIRURO_CATALOG_WAIT_MS}ms id=$id",
                    )
                }
                val miruro = miruroResult?.getOrNull() ?: EpisodesResult(emptyList())
                if (miruro.isEmpty) {
                    _loadingStatus.value =
                        "The main source looks down right now. Trying other sources…"
                }
                val preferredIsAnivexa =
                    ProviderCatalog.sourceOf(preferred) == ProviderCatalog.Source.ANIVEXA
                val merged = if (!miruro.isEmpty && !preferredIsAnivexa) {
                    // The full Anivexa catalog loads via launchAnivexaMerge anyway; drop the
                    // now-redundant fast subset instead of hitting those providers twice.
                    fastCatalog.cancel()
                    miruro
                } else {
                    val fast = fastCatalog.await()
                    if (fast.isEmpty) {
                        repo.episodes(id).also { mergedIncludesAnivexa = true }
                    } else {
                        DiagnosticsLog.event(
                            "Watch fast catalog id=$id providers=" + fast.providerNames.joinToString(),
                        )
                        repo.mergeProviders(miruro, fast)
                    }
                }
                if (miruroResult == null) {
                    launchMiruroLateMerge(id, miruroDeferred)
                } else if (miruro.isEmpty) {
                    scheduleMiruroRecovery(id, "initial catalog failed")
                }
                DiagnosticsLog.event(
                    "Watch episodes load success id=$id providers=" +
                        merged.providers.joinToString { provider ->
                            "${provider.name}:sub=${provider.sub.size},dub=${provider.dub.size}"
                        },
                )
                mergedEpisodes = merged
                // Prefer dub: launches carrying category=sub (typically history saved before the
                // setting was enabled) upgrade to dub when the catalog actually has the start
                // episode dubbed. In-player category switches are unaffected — this only runs on
                // screen entry — and when no dub exists the launch stays sub instead of erroring.
                if (category == Category.SUB && SettingsStore.preferDub.value) {
                    val startNumber = episodeNumber.toDoubleOrNull()
                    val dubAvailable = merged.providers.any { provider ->
                        provider.dub.isNotEmpty() &&
                            (startNumber == null || provider.dub.any { it.number == startNumber })
                    }
                    if (dubAvailable) {
                        category = Category.DUB
                        DiagnosticsLog.event("Watch category upgraded to dub (prefer dub) id=$id")
                    }
                }
                // The mirror case: launches carrying category=dub (prefer-dub defaults from
                // Watch Now, seeded Continue Watching, or stale history) where the dub cannot
                // actually serve this episode. Without this the spine comes up empty and the
                // screen dies with "No episodes for this title" even though subs exist.
                //
                // Judged per episode, not per title. Dubs routinely lag the sub release, so a
                // long-running show has dubbed early episodes and undubbed recent ones: checking
                // only "does any dub exist" passed, then every dub server was tried in turn for an
                // episode none of them had, and the viewer waited out the whole fallback chain to
                // be told "no server found" for something available in sub all along.
                if (category == Category.DUB) {
                    val startNumber = episodeNumber.toDoubleOrNull()
                    val dubHasEpisode = merged.providers.any { provider ->
                        // A multi-audio server counts as dub-capable through its sub list: that
                        // one file carries the English track. Without this the app decides no dub
                        // exists, drops to sub, and the player then picks Japanese — the exact
                        // complaint, with an English option sitting unused in the audio menu.
                        val list = provider.episodes(
                            ProviderCatalog.dubCapableCategory(provider.name, Category.DUB),
                        )
                        list.isNotEmpty() &&
                            (startNumber == null || list.any { it.number == startNumber })
                    }
                    val subHasEpisode = merged.providers.any { provider ->
                        provider.sub.isNotEmpty() &&
                            (startNumber == null || provider.sub.any { it.number == startNumber })
                    }
                    if (!dubHasEpisode && subHasEpisode) {
                        category = Category.SUB
                        DiagnosticsLog.event(
                            "Watch category fell back to sub (no dub for episode) id=$id " +
                                "episode=$episodeNumber",
                        )
                    }
                }
                repo.animeInfo(id)?.let { info ->
                    fullMedia = info
                    seriesTitle = info.title.preferred
                    artworkUrl = info.coverImage.best
                    seriesFormat = info.format
                    averageScore = info.averageScore
                    popularity = info.popularity
                    description = info.description
                    totalEpisodes = info.episodes
                    DiagnosticsLog.event("Watch animeInfo success id=$id title=${seriesTitle.take(80)}")
                }
                spine = pickSpine(merged)
                DiagnosticsLog.event(
                    "Watch spine picked size=${spine.size} preferred=$preferred category=${category.api} " +
                        "first=${spine.firstOrNull()?.displayNumber ?: "none"} last=${spine.lastOrNull()?.displayNumber ?: "none"}",
                )
                if (spine.isEmpty()) error("No episodes for this title")
                val startNumber = episodeNumber.toDoubleOrNull() ?: spine.first().number
                // Launched before the first resolve so a miss on the partial catalog can await
                // the remaining servers instead of reporting "no source" prematurely.
                if (!mergedIncludesAnivexa) launchAnivexaMerge(id)
                resolveAndPlay(startNumber)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Watch start failed key=$key", e)
                _loadingStatus.value = null
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            } finally {
                pipeWarmSession?.close()
            }
        }
    }

    /**
     * When the Miruro pipe outlived its initial wait, keep listening: if it eventually answers,
     * fold its providers into the catalog so they become selectable, without disturbing playback
     * that already started on a fast source.
     */
    private fun launchMiruroLateMerge(id: Int, deferred: kotlinx.coroutines.Deferred<Result<EpisodesResult>>) {
        miruroLateMergeJob?.cancel()
        miruroLateMergeJob = viewModelScope.launch {
            val result = try {
                deferred.await()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Result.failure(e)
            }
            val late = result.getOrNull()
            if (late == null || late.isEmpty) {
                result.exceptionOrNull()?.let {
                    DiagnosticsLog.throwable("Watch miruro late catalog failed id=$id", it)
                }
                scheduleMiruroRecovery(id, "late catalog failed")
                return@launch
            }
            applyMiruroCatalog(id, late, "late")
        }
    }

    /** Queue at most one automatic retry so a transient cold-start miss repairs the live picker. */
    private fun scheduleMiruroRecovery(id: Int, reason: String) {
        if (id != anilistId || miruroAutoRetryAttempted || miruroRecoveryJob?.isActive == true) return
        miruroAutoRetryAttempted = true
        setMiruroRecoveryPending(true)
        DiagnosticsLog.event(
            "Watch miruro recovery scheduled id=$id delayMs=$MIRURO_RECOVERY_DELAY_MS reason=$reason",
        )
        miruroRecoveryJob = viewModelScope.launch {
            delay(MIRURO_RECOVERY_DELAY_MS)
            refreshMiruroCatalog(id, "automatic")
        }
    }

    /** User-visible retry from the source picker; unlike the automatic path it starts now. */
    fun retryMiruroServers() {
        val id = anilistId
        if (id <= 0) return
        DiagnosticsLog.event("Watch miruro retry requested id=$id")
        miruroRecoveryJob?.cancel()
        miruroAutoRetryAttempted = true
        setMiruroRecoveryPending(true)
        miruroRecoveryJob = viewModelScope.launch {
            refreshMiruroCatalog(id, "manual")
        }
    }

    private suspend fun refreshMiruroCatalog(id: Int, trigger: String) {
        PipeBridge.prepareForRetry()
        val warmSession = PipeBridge.acquireWarmSession("$trigger-miruro-retry-$id")
        try {
            val refreshed = try {
                repo.miruroEpisodes(id, force = true)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Watch miruro $trigger retry failed id=$id", e)
                null
            }
            if (refreshed != null && !refreshed.isEmpty) {
                applyMiruroCatalog(id, refreshed, trigger)
            } else {
                DiagnosticsLog.event("Watch miruro $trigger retry returned no providers id=$id")
            }
        } finally {
            warmSession.close()
            if (id == anilistId) setMiruroRecoveryPending(false)
        }
    }

    /** Merge recovered providers without replacing the stream that is already playing. */
    private fun applyMiruroCatalog(id: Int, catalog: EpisodesResult, trigger: String) {
        if (id != anilistId || catalog.isEmpty) return
        val recoveredProviders = catalog.providerNames.toSet()
        unavailableSources.removeAll { it.provider in recoveredProviders }
        mergedEpisodes = repo.mergeProviders(catalog, mergedEpisodes)
        spine = retainNonEmptyNavigationSpine(spine, pickSpine(mergedEpisodes))
        DiagnosticsLog.event(
            "Watch miruro $trigger merge applied id=$id providers=" +
                recoveredProviders.sorted().joinToString(),
        )
        val data = (_state.value as? UiState.Success)?.data ?: return
        val number = data.current.number
        val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
        _state.value = UiState.Success(
            data.copy(
                episodes = spine,
                currentIndex = index,
                sourceOptions = sourceOptions(number),
                capabilities = sourceCapabilities.toMap(),
                isRetryingMiruro = false,
            ),
        )
        launchSourceValidation(number)
    }

    private fun setMiruroRecoveryPending(pending: Boolean) {
        miruroRecoveryPending = pending
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isRetryingMiruro != pending) {
            _state.value = UiState.Success(data.copy(isRetryingMiruro = pending))
        }
    }

    /**
     * Fold the slower Anivexa providers in once they arrive, without disturbing playback that
     * already started on a Miruro source. Refreshes the navigation spine and the source list so
     * the extra servers become selectable; the active stream and resolving flag are left as-is.
     */
    private fun launchAnivexaMerge(id: Int) {
        anivexaMergeJob?.cancel()
        anivexaMergeJob = viewModelScope.launch {
            val anivexa = runCatching { repo.anivexaEpisodes(id) }
                .onFailure { DiagnosticsLog.throwable("Watch anivexa merge failed id=$id", it) }
                .getOrNull()
            if (anivexa != null && !anivexa.isEmpty) {
                mergedEpisodes = repo.mergeProviders(mergedEpisodes, anivexa)
                spine = retainNonEmptyNavigationSpine(spine, pickSpine(mergedEpisodes))
                DiagnosticsLog.event(
                    "Watch anivexa merge applied id=$id providers=" + mergedEpisodes.providerNames.joinToString(),
                )
            }
            mergedIncludesAnivexa = true
            // Reflect completion whether or not Anivexa added anything: the extra servers become
            // selectable and the "loading more servers" hint clears.
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            val number = data.current.number
            val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
            _state.value = UiState.Success(
                data.copy(
                    episodes = spine,
                    currentIndex = index,
                    sourceOptions = sourceOptions(number),
                        capabilities = sourceCapabilities.toMap(),
                    isLoadingMoreSources = false,
                ),
            )
            launchSourceValidation(number)
        }
    }

    /**
     * Navigation spine: the longest provider episode list, so Next never dead-ends just because
     * the launched provider's list lags behind the others. Ties keep the chosen provider; source
     * resolution still tries the preferred provider first and falls back per episode.
     */
    private fun pickSpine(merged: EpisodesResult): List<EpisodeItem> {
        val spine = pickNavigationSpine(merged, preferred, category)
        // Applied on every rebuild, not just the first paint: a provider failover swaps the spine
        // and would otherwise drop the titles back to bare numbers mid-episode.
        if (spine.isEmpty() || episodeMeta.isEmpty()) return spine
        return mergeEpisodeMetadata(spine, episodeMeta, anilistId)
    }

    /**
     * Konoha's episode titles and stills — the same overlay the Anime page applies. Cosmetic and
     * rate-limit-free, so any failure just leaves the provider's numbered list as it was.
     */
    private suspend fun loadEpisodeMetadata(id: Int) {
        val meta = runCatching { repo.konohaEpisodes(id) }
            .onFailure { DiagnosticsLog.throwable("Watch episode metadata failed id=$id", it) }
            .getOrDefault(emptyList())
        if (meta.isEmpty() || id != anilistId) return
        episodeMeta = meta
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.episodes.isEmpty()) return
        _state.value = UiState.Success(
            data.copy(episodes = mergeEpisodeMetadata(data.episodes, meta, id)),
        )
    }

    private suspend fun resolveAndPlay(number: Double) {
        failedStreamUrls.clear()
        skipLookupJob?.cancel()
        skipLookupJob = null
        skipLookupKey = null
        val requestedProvider = preferred
        DiagnosticsLog.event(
            "Watch resolve start id=$anilistId episode=${fmt(number)} preferred=$requestedProvider " +
                "category=${category.api} excluded=${failedProviders.joinToString()}",
        )
        lastRequestedNumber = number
        val previous = (_state.value as? UiState.Success)?.data
        _state.value = previous?.let { UiState.Success(it.copy(isResolving = true, notice = null)) }
            ?: UiState.Loading
        // Keep completion state paired with the exact catalog snapshot passed to the first
        // resolution. The background merge can finish while that resolution is suspended; using
        // the live flag afterward would falsely imply that the first attempt saw those providers.
        val initialCatalogIncludedFullAnivexa = mergedIncludesAnivexa
        val initialEpisodes = mergedEpisodes
        var resolution = repo.resolveSources(
            anilistId = anilistId,
            number = number,
            preferred = requestedProvider,
            category = category,
            episodes = initialEpisodes,
            excludedProviders = failedProviders,
        )
        val unavailableThisResolve = resolution.unavailableProviders.toMutableSet()
        var resolved = resolution.resolved
        val shouldRetryMergedCatalog = shouldRetryWithMergedCatalog(
            hasResolvedSource = resolved != null,
            initialCatalogIncludedFullAnivexa = initialCatalogIncludedFullAnivexa,
        )
        if (shouldRetryMergedCatalog) {
            // The quick partial catalog missed; the slower servers may still carry this episode,
            // so wait for the full merge before declaring no source.
            DiagnosticsLog.event(
                "Watch resolve retry pending full catalog id=$anilistId episode=${fmt(number)}",
            )
            _loadingStatus.value = "Still looking — checking the remaining servers…"
            anivexaMergeJob?.join()
            // Last chance before "no source": the attempt cap only exists to bound mid-playback
            // fallback latency, so here every remaining server gets a try.
            resolution = repo.resolveSources(
                anilistId = anilistId,
                number = number,
                preferred = requestedProvider,
                category = category,
                episodes = mergedEpisodes,
                excludedProviders = failedProviders,
                maxAttempts = Int.MAX_VALUE,
            )
            unavailableThisResolve += resolution.unavailableProviders
            resolved = resolution.resolved
        }
        unavailableThisResolve.forEach { provider ->
            unavailableSources += EpisodeSourceKey(number, provider, category)
        }
        if (resolved == null) {
            _loadingStatus.value = null
            val message = "No playable source for episode ${fmt(number)} on any server"
            DiagnosticsLog.event("Watch resolve no source id=$anilistId episode=${fmt(number)}")
            _state.value = previous?.let {
                UiState.Success(
                    it.copy(
                        sourceOptions = sourceOptions(number),
                        capabilities = sourceCapabilities.toMap(),
                        isResolving = false,
                        notice = message,
                    ),
                )
            } ?: UiState.Error(message)
            return
        }
        // A later successful retry makes this option valid again for the current session.
        unavailableSources -= EpisodeSourceKey(number, resolved.provider, category)
        confirmedSources += EpisodeSourceKey(number, resolved.provider, category)
        val fallbackNotice = if (resolved.provider != requestedProvider) {
            // The preferred provider can be absent from the fast catalog and arrive in a later
            // merge. The fallback already proves it was not usable for this playback request, so
            // keep that late row out of this episode's picker as well.
            if (requestedProvider != DEFAULT_PREFERRED_PROVIDER) {
                unavailableSources += EpisodeSourceKey(number, requestedProvider, category)
            }
            "${ProviderCatalog.label(requestedProvider)} is unavailable for this episode. " +
                "Playing ${ProviderCatalog.label(resolved.provider)} instead."
        } else {
            null
        }
        if (fallbackNotice != null) {
            DiagnosticsLog.event(
                "Watch provider fallback requested=$requestedProvider actual=${resolved.provider} " +
                    "episode=${fmt(number)}",
            )
        }
        // An empty provider `skipTimes: {}` is not capability. Normalise it to null so playback
        // can ask AniSkip after the player reports the exact duration of this encode.
        val sources = resolved.sources.let { providerSources ->
            if (providerSources.skip?.hasUsableWindow() == true) providerSources
            else providerSources.copy(skip = null)
        }
        val skipTimingStatus = when {
            sources.skip != null -> SkipTimingStatus.PROVIDER
            number % 1.0 != 0.0 -> SkipTimingStatus.UNAVAILABLE
            else -> SkipTimingStatus.WAITING_FOR_DURATION
        }
        val index = spine.indexOfFirst { it.number == number }.coerceAtLeast(0)
        val resume = LibraryStore.historyFor(anilistId)?.takeIf { it.episodeNumber == number }?.positionMs ?: 0L
        val chosen = pickProviderStream(resolved.provider, sources)
        DiagnosticsLog.event(
            "Watch resolve success provider=${resolved.provider} episode=${fmt(number)} index=$index " +
                "hls=${resolved.sources.hlsStreams.size} total=${resolved.sources.streams.size} " +
                "embed=${resolved.sources.embedStreams.size} subtitles=${resolved.sources.subtitles.size} " +
                "chosen=${chosen?.diagnosticLabel() ?: "none"} resumeMs=$resume " +
                "skip=${skipTimingStatus.name.lowercase()} ${sources.skip.diagnosticSummary()}",
        )
        DiagnosticsLog.event(
            "Watch source inventory provider=${resolved.provider} " +
                resolved.sources.streams.joinToString(separator = ",", limit = 16, truncated = "...") {
                    "${it.diagnosticLabel()}${if (it.isActive) "*" else ""}"
                },
        )
        _loadingStatus.value = null
        _state.value = UiState.Success(
            WatchData(
                episodes = spine,
                currentIndex = index,
                provider = resolved.provider,
                category = category,
                sourceOptions = sourceOptions(number),
                        capabilities = sourceCapabilities.toMap(),
                anilistId = anilistId,
                sources = sources,
                chosenStream = chosen,
                skipTimingStatus = skipTimingStatus,
                seriesTitle = seriesTitle,
                artworkUrl = artworkUrl,
                seriesFormat = seriesFormat,
                averageScore = averageScore,
                popularity = popularity,
                description = description,
                startPositionMs = resume,
                preferredProvider = globalPreferredProvider,
                isResolving = false,
                isLoadingMoreSources = !mergedIncludesAnivexa,
                isRetryingMiruro = miruroRecoveryPending,
                notice = fallbackNotice,
                fullMedia = fullMedia,
            ),
        )
        recordHistory(number, resolved.provider)
        launchSourceValidation(number)
    }

    /**
     * Switches the server for the episode on screen only.
     *
     * Picking a server here used to also pin it as the global preference, so reaching for a
     * working source on one awkward episode silently re-pointed every future title at it. The
     * lasting choice now lives in Settings ▸ Servers, where it can be ranked and seen.
     */
    /** The merged episode catalog, for callers that resolve episodes outside the current one. */
    fun episodeCatalog(): EpisodesResult = mergedEpisodes

    fun changeSource(providerName: String, categoryApi: String) {
        DiagnosticsLog.event("Watch changeSource requested provider=$providerName category=$categoryApi")
        switchSource(providerName, categoryApi, rememberProvider = false)
    }

    fun changeCategory(categoryApi: String) {
        val providerName = (_state.value as? UiState.Success)?.data?.provider ?: return
        DiagnosticsLog.event("Watch changeCategory requested provider=$providerName category=$categoryApi")
        switchSource(providerName, categoryApi, rememberProvider = false)
    }

    private fun switchSource(providerName: String, categoryApi: String, rememberProvider: Boolean) {
        val nextCategory = Category.from(categoryApi)
        val provider = mergedEpisodes.provider(providerName) ?: return
        val nextSpine = provider.episodes(nextCategory).takeIf { it.isNotEmpty() } ?: return
        val current = (_state.value as? UiState.Success)?.data
        val currentNumber = current?.current?.number ?: lastRequestedNumber
        // Source controls are episode-scoped. Never let a stale selection jump the viewer to the
        // first episode merely because that server/audio pair lacks the episode being watched.
        if (nextSpine.none { it.number == currentNumber }) return

        // Steer this resolve at the server the viewer just picked. Keeping the pick out of
        // `preferred` was what stopped it pinning the global preference, but `preferred` is also
        // the only thing `resolveAndPlay` passes to the resolver — so the pick was dropped
        // entirely and resolution fell back to whichever server answered first. Choosing a dub
        // server that way kept landing on a sub-only server's stream, i.e. Japanese audio.
        preferred = providerName
        if (rememberProvider) {
            globalPreferredProvider = providerName
            SettingsStore.setPreferredProvider(providerName)
        }
        if (current?.provider == providerName && current.category == nextCategory) {
            if (rememberProvider) {
                _state.value = UiState.Success(
                    current.copy(
                        preferredProvider = providerName,
                        notice = "${ProviderCatalog.label(providerName)} is now your preferred server.",
                    ),
                )
            }
            return
        }
        category = nextCategory
        spine = nextSpine
        failedProviders.clear()
        if (current != null) {
            _state.value = UiState.Success(
                current.copy(
                    preferredProvider = globalPreferredProvider,
                    notice = if (rememberProvider) {
                        "${ProviderCatalog.label(providerName)} is now your preferred server."
                    } else {
                        current.notice
                    },
                ),
            )
        }
        launchResolve(currentNumber)
    }

    private suspend fun recordHistory(number: Double, provider: String) {
        val ep = spine.firstOrNull { it.number == number }
        val existing = LibraryStore.historyFor(anilistId)
        val sameEpisode = existing?.takeIf { it.episodeNumber == number }
        LibraryStore.upsertHistory(
            HistoryEntry(
                anilistId = anilistId,
                title = seriesTitle,
                cover = artworkUrl,
                episodeNumber = number,
                episodeTitle = ep?.title,
                provider = provider,
                category = category.api,
                positionMs = sameEpisode?.positionMs ?: 0L,
                durationMs = sameEpisode?.durationMs ?: 0L,
                // This rebuilds the row from scratch on every resolve, so anything the row
                // accumulates has to be carried across explicitly or it is silently dropped.
                watchedEpisodes = existing?.watchedEpisodes.orEmpty(),
            ),
        )
    }

    private var lastProgressSave = 0L
    private var lastKnownPositionMs = 0L
    private var lastKnownDurationMs = 0L
    private var lastKnownNumber: Double? = null

    /**
     * The stream a progress report was accepted for. Playback surfaces keep polling their player
     * while a new episode is being resolved, so the first tick after the episode changes can still
     * carry the *previous* stream's clock. Attributing it to the new episode marked that episode
     * watched before a single frame of it had played: picking episode 2 jumped Continue Watching
     * straight to 3, and re-opening episode 2 then resumed from 0:00 because the stored row no
     * longer pointed at it. Progress is only counted once the same stream has reported twice.
     */
    private var progressStreamUrl: String? = null
    private var progressReportsForStream = 0

    fun onProgress(streamUrl: String, positionMs: Long, durationMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        val expected = data.chosenStream?.url
        if (expected != null && streamUrl != expected) {
            if (progressStreamUrl != streamUrl) {
                DiagnosticsLog.event(
                    "Watch progress ignored from stale stream episode=${fmt(data.current.number)} " +
                        "reportedHost=${runCatching { Uri.parse(streamUrl).host }.getOrNull() ?: "unknown"}",
                )
                progressStreamUrl = streamUrl
                progressReportsForStream = 0
            }
            return
        }
        if (progressStreamUrl != streamUrl) {
            progressStreamUrl = streamUrl
            progressReportsForStream = 0
        }
        progressReportsForStream++
        lastKnownPositionMs = positionMs
        lastKnownDurationMs = durationMs
        lastKnownNumber = data.current.number
        maybeLoadSkipTiming(data, durationMs)
        // A single report is not evidence of playback: it can be the tail of a torn-down player.
        // Watched/synced state only moves once this stream has been observed running.
        if (progressReportsForStream > 1) {
            maybeAdvanceContinueWatching(data, positionMs, durationMs)
            maybeSyncAniListProgress(data.current.number, positionMs, durationMs)
            maybePreheatNextEpisode(data, positionMs)
        }
        val now = System.currentTimeMillis()
        if (now - lastProgressSave < 8_000) return
        lastProgressSave = now
        LibraryStore.updateProgress(anilistId, data.current.number, positionMs, durationMs)
    }

    /**
     * Providers frequently omit skip metadata, and different encodes can have different cuts.
     * Wait until the active player knows its duration, then let AniSkip select the matching set.
     * The lookup is background-only: it never holds up playback and can populate the button live.
     */
    private fun maybeLoadSkipTiming(data: WatchData, durationMs: Long) {
        if (data.sources.skip?.hasUsableWindow() == true) return
        if (data.current.number % 1.0 != 0.0 || durationMs < MIN_SKIP_LOOKUP_DURATION_MS) return
        val durationSeconds = (durationMs / 1000.0).roundToInt().coerceAtLeast(1)
        val key = SkipLookupKey(data.current.number, data.provider, data.category, durationSeconds)
        if (skipLookupKey == key) return

        skipLookupJob?.cancel()
        skipLookupKey = key
        DiagnosticsLog.event(
            "Watch skip lookup start provider=${data.provider} episode=${data.current.displayNumber} " +
                "durationSec=$durationSeconds",
        )
        _state.value = UiState.Success(data.copy(skipTimingStatus = SkipTimingStatus.CHECKING))
        skipLookupJob = viewModelScope.launch {
            val result = runCatching {
                repo.skipTimes(anilistId, data.current.number, durationSeconds.toDouble())
            }
            if (skipLookupKey != key) return@launch
            val current = (_state.value as? UiState.Success)?.data ?: return@launch
            if (
                current.current.number != key.episode ||
                current.provider != key.provider ||
                current.category != key.category
            ) return@launch

            result.onSuccess { skip ->
                if (skip?.hasUsableWindow() == true) {
                    DiagnosticsLog.event(
                        "Watch skip lookup success provider=${key.provider} " +
                            "episode=${current.current.displayNumber} durationSec=$durationSeconds " +
                            skip.diagnosticSummary(),
                    )
                    _state.value = UiState.Success(
                        current.copy(
                            sources = current.sources.copy(skip = skip),
                            skipTimingStatus = SkipTimingStatus.ANISKIP,
                        ),
                    )
                } else {
                    DiagnosticsLog.event(
                        "Watch skip lookup unavailable provider=${key.provider} " +
                            "episode=${current.current.displayNumber} durationSec=$durationSeconds",
                    )
                    _state.value = UiState.Success(
                        current.copy(skipTimingStatus = SkipTimingStatus.UNAVAILABLE),
                    )
                }
            }.onFailure { error ->
                DiagnosticsLog.throwable(
                    "Watch skip lookup failed provider=${key.provider} " +
                        "episode=${current.current.displayNumber} durationSec=$durationSeconds",
                    error,
                )
                _state.value = UiState.Success(
                    current.copy(skipTimingStatus = SkipTimingStatus.SERVICE_ERROR),
                )
            }
        }
    }

    /**
     * TV tears the inline player down when leaving fullscreen. Persist the position it reached —
     * bypassing the periodic-save throttle — and fold it into [WatchData.startPositionMs], so the
     * next play (fullscreen pill, the inline play button, or re-picking the same episode chip)
     * resumes there instead of restarting from the stale resolve-time position.
     */
    fun commitPlaybackPosition() {
        val data = (_state.value as? UiState.Success)?.data ?: return
        val number = lastKnownNumber ?: return
        if (number != data.current.number || lastKnownPositionMs <= 0) return
        lastProgressSave = System.currentTimeMillis()
        LibraryStore.updateProgress(anilistId, number, lastKnownPositionMs, lastKnownDurationMs)
        DiagnosticsLog.event(
            "Watch commit position episode=${fmt(number)} positionMs=$lastKnownPositionMs",
        )
        _state.value = UiState.Success(data.copy(startPositionMs = lastKnownPositionMs))
    }

    private fun maybeSyncAniListProgress(episodeNumber: Double, positionMs: Long, durationMs: Long) {
        val service = AccountService.active ?: return
        if (!SettingsStore.autoSyncAniList.value) return
        if (!shouldSyncAniListProgress(episodeNumber, positionMs, durationMs)) return
        val episode = episodeNumber.toInt()
        if (!scheduledRemoteProgressEpisodes.add(episode)) return
        when (service) {
            AccountService.MAL -> runCatching {
                LibraryStore.enqueueMalProgressSync(anilistId, episode, totalEpisodes)
            }.onFailure { error ->
                scheduledRemoteProgressEpisodes.remove(episode)
                DiagnosticsLog.throwable(
                    "Watch MAL progress enqueue failed id=$anilistId episode=${fmt(episodeNumber)}",
                    error,
                )
            }
            AccountService.ANILIST -> viewModelScope.launch {
                runCatching {
                    repo.saveAniListProgress(anilistId, episode, totalEpisodes)
                }
                    .onSuccess { update ->
                        update?.status?.let { LibraryStore.updateRemoteStatus(anilistId, it) }
                        DiagnosticsLog.event(
                            "AniList progress sync ${if (update == null) "skipped" else "confirmed"} " +
                                "id=$anilistId episode=$episode",
                        )
                    }
                    .onFailure { error ->
                        scheduledRemoteProgressEpisodes.remove(episode)
                        DiagnosticsLog.throwable(
                            "Watch AniList progress sync failed id=$anilistId " +
                                "episode=${fmt(episodeNumber)}",
                            error,
                        )
                    }
            }
        }
    }

    /**
     * Keep the local row aligned with what the viewer actually finished, regardless of whether
     * AniList/MAL syncing is enabled or currently reachable.
     */
    private fun maybeAdvanceContinueWatching(data: WatchData, positionMs: Long, durationMs: Long) {
        val watchedNumber = data.current.number
        if (!shouldSyncAniListProgress(watchedNumber, positionMs, durationMs)) return
        val episode = watchedNumber.toInt()
        if (!locallyWatchedEpisodes.add(episode)) return
        val next = data.episodes.getOrNull(data.currentIndex + 1)
        val seriesCompleted = totalEpisodes?.takeIf { it > 0 }?.let { episode >= it } == true
        LibraryStore.markEpisodeWatched(
            anilistId = anilistId,
            watchedEpisode = watchedNumber,
            nextEpisode = next?.number,
            nextEpisodeTitle = next?.distinctTitle,
            seriesCompleted = seriesCompleted,
        )
    }

    /**
     * Resolves the next episode's stream in the background while this one plays.
     *
     * Deliberately not on TV, and that is the whole design. A TV box has a 96–192 MB heap, the
     * resolver is a hidden WebView, and a hidden WebView that touches media has already been
     * observed taking the hardware decoder away from the playing video (OMX 0x80001013,
     * "preempted"). TV device reports also run at ~15% janky frames against ~1% on phones, so
     * there is no headroom to spend on speculative work; the episode catalog it would otherwise
     * want is already served from the repository cache.
     *
     * The wait until [PREHEAT_AFTER_MS] is what keeps this from competing with the *current*
     * episode's own startup, which is the moment the viewer actually feels.
     */
    private fun maybePreheatNextEpisode(data: WatchData, positionMs: Long) {
        if (AppGraph.isTv) return
        if (positionMs < PREHEAT_AFTER_MS) return
        val next = data.episodes.getOrNull(data.currentIndex + 1) ?: return
        if (!preheatedEpisodes.add(next.number)) return
        viewModelScope.launch {
            runCatching {
                repo.preheatSources(
                    anilistId = anilistId,
                    number = next.number,
                    preferred = data.provider,
                    category = data.category,
                    episodes = mergedEpisodes,
                )
            }.onFailure {
                it.rethrowIfCancellation()
                // Speculative work; the real resolve will surface anything that matters.
                preheatedEpisodes.remove(next.number)
            }
        }
    }

    fun playIndex(index: Int) {
        DiagnosticsLog.event("Watch playIndex requested index=$index spineSize=${spine.size}")
        if (index !in spine.indices) {
            DiagnosticsLog.event("Watch playIndex ignored out of bounds index=$index")
            return
        }
        failedProviders.clear()
        launchResolve(spine[index].number)
    }

    fun next() {
        DiagnosticsLog.event("Watch next requested")
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur + 1)
    }

    fun prev() {
        DiagnosticsLog.event("Watch prev requested")
        val cur = (_state.value as? UiState.Success)?.data?.currentIndex ?: return
        playIndex(cur - 1)
    }

    fun retry() {
        DiagnosticsLog.event("Watch retry requested episode=${fmt(lastRequestedNumber)}")
        failedProviders.clear()
        unavailableSources.removeAll { it.episode == lastRequestedNumber && it.category == category }
        launchResolve(lastRequestedNumber)
    }

    /** All episode resolution goes through here so a failure becomes an error state, not a crash. */
    private fun launchResolve(number: Double, before: (suspend () -> Unit)? = null) {
        DiagnosticsLog.event("Watch launchResolve episode=${fmt(number)}")
        sourceValidationJob?.cancel()
        sourceValidationIsExhaustive = false
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            try {
                before?.invoke()
                resolveAndPlay(number)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Watch resolve failed id=$anilistId episode=${fmt(number)}", e)
                _loadingStatus.value = null
                _state.value = UiState.Error(e.message ?: "Failed to load episode")
            }
        }
    }

    fun onPlaybackError(message: String, streamUrl: String, positionMs: Long) {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
        val streamHost = runCatching { Uri.parse(streamUrl).host }.getOrNull()
        DiagnosticsLog.event(
            "Watch playback error provider=${data.provider} episode=${data.current.displayNumber} " +
                "streamHost=${streamHost ?: "unknown"} " +
                "positionMs=$positionMs message=${message.take(160)}",
        )

        if (data.provider in INTERNAL_FALLBACK_PROVIDERS && streamUrl.isNotBlank()) {
            val providerLabel = ProviderCatalog.label(data.provider)
            if (!failedStreamUrls.add(streamUrl)) {
                DiagnosticsLog.event(
                    "Watch ignored duplicate $providerLabel stream failure host=${Uri.parse(streamUrl).host}",
                )
                return
            }
            val next = nextProviderStream(
                provider = data.provider,
                sources = data.sources,
                currentUrl = streamUrl,
                failedUrls = failedStreamUrls,
            )
            if (next != null) {
                val failed = data.sources.streams.firstOrNull { it.url == streamUrl }
                val resume = maxOf(data.startPositionMs, positionMs.coerceAtLeast(0L))
                DiagnosticsLog.event(
                    "Watch $providerLabel internal fallback failed=${failed?.diagnosticLabel() ?: "unknown"} " +
                        "next=${next.diagnosticLabel()} attempted=${failedStreamUrls.size} resumeMs=$resume",
                )
                _state.value = UiState.Success(
                    data.copy(
                        chosenStream = next,
                        startPositionMs = resume,
                        playbackGeneration = data.playbackGeneration + 1,
                        notice = "${failed?.label ?: "$providerLabel source"} failed. Trying ${next.label}…",
                    ),
                )
                return
            }
            DiagnosticsLog.event(
                "Watch $providerLabel streams exhausted attempted=${failedStreamUrls.size}; trying another provider",
            )
        }

        if (!failedProviders.add(data.provider)) {
            DiagnosticsLog.event("Watch ignored duplicate provider failure provider=${data.provider}")
            return
        }
        // A validation resolve proves only that a URL exists. Stop it before recording the player
        // failure so a concurrent successful lookup cannot make the broken stream selectable again.
        sourceValidationJob?.cancel()
        repo.recordPlaybackFailure(
            anilistId = data.anilistId,
            number = data.current.number,
            provider = data.provider,
            streamHost = streamHost,
            message = message,
        )
        unavailableSources += EpisodeSourceKey(data.current.number, data.provider, data.category)
        launchResolve(data.current.number) {
            _state.value = UiState.Success(
                data.copy(
                    isResolving = true,
                    notice = "${ProviderCatalog.label(data.provider)} failed: $message. Trying another source…",
                ),
            )
        }
    }

    /**
     * Files away the track languages a resolve turned up.
     *
     * Audio labels are provider-authored free text ("Hindi", "English Dub", "sub"), so they are
     * normalised through [normalizeLanguage], and the placeholder labels that just restate the
     * category are dropped — "sub" is not a language.
     */
    private fun recordCapabilities(option: WatchSourceOption, sources: SourcesResult) {
        val audio = sources.streams.mapNotNull { it.audio }
            .mapNotNull(::normalizeLanguage)
            .toSet()
        val subtitles = sources.subtitles
            .mapNotNull { normalizeLanguage(it.language.ifBlank { it.label }) }
            .toSet()
        sourceCapabilities[option.provider to option.category] = SourceCapabilities(
            audioLanguages = audio,
            subtitleLanguages = subtitles,
            known = true,
        )
    }

    fun capabilitiesFor(provider: String, category: Category): SourceCapabilities =
        sourceCapabilities[provider to category] ?: SourceCapabilities()

    /** Finish the slower provider sweep only when the user asks to see every server. */
    fun validateRemainingSources() {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (data.isResolving) return
        launchSourceValidation(data.current.number, exhaustive = true)
    }

    private fun sourceOptions(number: Double): List<WatchSourceOption> =
        visibleSourceOptions(
            candidates = availableSourceOptions(mergedEpisodes, number),
            isConfirmed = { EpisodeSourceKey(number, it.provider, it.category) in confirmedSources },
            isUnavailable = { EpisodeSourceKey(number, it.provider, it.category) in unavailableSources },
        )

    /**
     * Episode catalogs can contain stale rows whose source endpoint returns no stream. Validate
     * those rows in small parallel batches and publish only confirmed server/audio pairs; this
     * keeps the picker truthful without delaying the first playable provider.
     */
    private fun launchSourceValidation(number: Double, exhaustive: Boolean = false) {
        if (sourceValidationIsExhaustive && sourceValidationJob?.isActive == true) {
            return
        }
        sourceValidationJob?.cancel()
        sourceValidationIsExhaustive = exhaustive
        sourceValidationJob = viewModelScope.launch {
            // Validation is useful for a truthful picker but competes with video startup. Every
            // device gives playback a head start; phones use two probes at once and low-RAM TVs one.
            // The automatic pass stops after two fallbacks (or six attempts). Opening the source
            // picker explicitly requests the remaining options.
            if (!exhaustive) kotlinx.coroutines.delay(SOURCE_VALIDATION_START_DELAY_MS)
            val validationConcurrency = sourceValidationConcurrency(AppGraph.isTv)
            val allOptions = availableSourceOptions(mergedEpisodes, number)
            val activeProvider = (_state.value as? UiState.Success)?.data?.provider
            val confirmedFallbackProviders = allOptions
                .filter { option ->
                    option.provider != activeProvider &&
                        EpisodeSourceKey(number, option.provider, option.category) in confirmedSources
                }
                .mapTo(mutableSetOf(), WatchSourceOption::provider)
            val fallbackTarget = if (exhaustive) {
                Int.MAX_VALUE
            } else {
                (SOURCE_VALIDATION_FALLBACK_TARGET - confirmedFallbackProviders.size).coerceAtLeast(0)
            }
            val candidates = allOptions.filter { option ->
                val key = EpisodeSourceKey(number, option.provider, option.category)
                if (key in confirmedSources || key in unavailableSources) return@filter false
                validatesDuringPlayback(option.provider) &&
                    (exhaustive || option.provider != activeProvider)
            }.take(sourceValidationCandidateLimit(exhaustive))
            if (candidates.isEmpty() || fallbackTarget == 0) {
                val data = (_state.value as? UiState.Success)?.data
                if (data != null && data.current.number == number && mergedIncludesAnivexa) {
                    _state.value = UiState.Success(data.copy(isLoadingMoreSources = false))
                }
                return@launch
            }

            val initial = (_state.value as? UiState.Success)?.data
            if (initial != null && initial.current.number == number) {
                _state.value = UiState.Success(initial.copy(isLoadingMoreSources = true))
            }
            val providerNames = mergedEpisodes.providerNames.toSet()
            val newlyConfirmedFallbackProviders = mutableSetOf<String>()
            candidates.chunked(validationConcurrency).forEachIndexed { batchIndex, batch ->
                val results = batch.map { option ->
                    async {
                        val resolution = runCatching {
                            repo.resolveSources(
                                anilistId = anilistId,
                                number = number,
                                preferred = option.provider,
                                category = option.category,
                                episodes = mergedEpisodes,
                                excludedProviders = providerNames - option.provider,
                                maxAttempts = 1,
                                allowInteractiveChallenges = false,
                            )
                        }.onFailure {
                            DiagnosticsLog.throwable(
                                "Watch source validation failed provider=${option.provider} " +
                                    "category=${option.category.api} episode=${fmt(number)}",
                                it,
                            )
                        }.getOrNull()
                        val resolved = resolution?.resolved?.takeIf { it.provider == option.provider }
                        // Keep what the resolve told us about the tracks, not just whether it
                        // worked. This costs nothing extra — the request already happened.
                        resolved?.let { recordCapabilities(option, it.sources) }
                        option to (resolved != null)
                    }
                }.awaitAll()

                if (lastRequestedNumber != number) return@launch
                results.forEach { (option, available) ->
                    val key = EpisodeSourceKey(number, option.provider, option.category)
                    if (available) {
                        confirmedSources += key
                        unavailableSources -= key
                    } else {
                        unavailableSources += key
                    }
                }
                results.filter { (_, available) -> available }
                    .mapTo(newlyConfirmedFallbackProviders) { (option, _) -> option.provider }
                val data = (_state.value as? UiState.Success)?.data ?: return@launch
                if (data.current.number != number) return@launch
                val reachedTarget = !exhaustive &&
                    newlyConfirmedFallbackProviders.size >= fallbackTarget
                val hasMore = !reachedTarget &&
                    (batchIndex + 1) * validationConcurrency < candidates.size
                _state.value = UiState.Success(
                    data.copy(
                        sourceOptions = sourceOptions(number),
                        capabilities = sourceCapabilities.toMap(),
                        isLoadingMoreSources = !mergedIncludesAnivexa || hasMore,
                    ),
                )
                if (reachedTarget) {
                    DiagnosticsLog.event(
                        "Watch source validation paused episode=${fmt(number)} " +
                            "confirmedFallbacks=" +
                            "${confirmedFallbackProviders.size + newlyConfirmedFallbackProviders.size}",
                    )
                    return@launch
                }
            }
        }
    }

    private fun fmt(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()

    private fun StreamItem.diagnosticLabel(): String {
        val type = when {
            isEmbed -> "embed"
            isHls -> "hls"
            else -> "direct"
        }
        return "$type label=${label.take(48)} audio=${audio ?: "unknown"} " +
            "height=${height ?: "auto"} host=${runCatching { Uri.parse(url).host }.getOrNull() ?: "unknown"}"
    }
}

/**
 * Which of the episode's server/audio pairs are shown in the picker. Fast providers (the Miruro
 * pipe's native HLS set and the API-backed Anivexa lookups) appear the moment the catalog has
 * them — quick and reliable, no waiting on a per-server validation round-trip. Slower scrapers
 * must be confirmed playable first so the list never advertises a dead endpoint. Anything proven
 * unavailable for this episode is always hidden, even a fast one that failed validation.
 */
internal fun visibleSourceOptions(
    candidates: List<WatchSourceOption>,
    isConfirmed: (WatchSourceOption) -> Boolean,
    isUnavailable: (WatchSourceOption) -> Boolean,
): List<WatchSourceOption> = candidates.filter { option ->
    !isUnavailable(option) && (isConfirmed(option) || ProviderCatalog.isFast(option.provider))
}

/** Only server/audio pairs carrying the episode currently on screen are valid controls. */
internal fun availableSourceOptions(
    episodes: EpisodesResult,
    number: Double,
): List<WatchSourceOption> = episodes.providers
    .flatMap { provider ->
        provider.categories.mapNotNull { category ->
            val providerEpisodes = provider.episodes(category)
            val hasCurrentEpisode = providerEpisodes.any { it.number == number }
            if (!hasCurrentEpisode) return@mapNotNull null
            WatchSourceOption(
                provider = provider.name,
                category = category,
                hasCurrentEpisode = true,
                episodeCount = providerEpisodes.size,
            )
        }
    }
    .sortedWith(compareBy<WatchSourceOption> { ProviderCatalog.sortKey(it.provider) }.thenBy { it.category.ordinal })

internal fun shouldSyncAniListProgress(episodeNumber: Double, positionMs: Long, durationMs: Long): Boolean {
    if (episodeNumber < 1 || episodeNumber % 1.0 != 0.0) return false
    if (durationMs < MIN_ANILIST_SYNC_DURATION_MS || positionMs <= 0) return false
    return positionMs.toDouble() / durationMs >= ANILIST_SYNC_WATCHED_FRACTION
}

private const val MIN_ANILIST_SYNC_DURATION_MS = 60_000L
private const val ANILIST_SYNC_WATCHED_FRACTION = 0.80

/**
 * How far into an episode the next one is resolved ahead of time.
 *
 * Late enough that the current episode's own startup is finished and the viewer has clearly
 * settled on this title, early enough to be ready long before the credits.
 */
private const val PREHEAT_AFTER_MS = 90_000L
/** Let fallback playback settle before one bounded background resolver recovery. */
private const val MIRURO_RECOVERY_DELAY_MS = 15_000L
private const val SOURCE_VALIDATION_START_DELAY_MS = 10_000L
private const val SOURCE_VALIDATION_FALLBACK_TARGET = 2
private const val SOURCE_VALIDATION_MAX_AUTOMATIC_ATTEMPTS = 6

internal fun sourceValidationConcurrency(isTv: Boolean): Int = if (isTv) 1 else 2

internal fun sourceValidationCandidateLimit(exhaustive: Boolean): Int =
    if (exhaustive) Int.MAX_VALUE else SOURCE_VALIDATION_MAX_AUTOMATIC_ATTEMPTS

private fun SkipTimes?.diagnosticSummary(): String = this?.let {
    "intro=${it.introStart ?: "none"}-${it.introEnd ?: "none"} " +
        "outro=${it.outroStart ?: "none"}-${it.outroEnd ?: "none"}"
} ?: "intro=none outro=none"

/** Provider-specific first-player policy, applied only after that provider has resolved sources. */
internal fun pickProviderStream(provider: String, sources: SourcesResult): StreamItem? {
    val direct = sources.streams.filterNot(StreamItem::isEmbed)
    val embeds = sources.embedStreams

    return when (provider) {
        // Kiwi advertises direct HLS renditions next to its embed, and preferring them is very
        // tempting — native ExoPlayer, hardware decode, real pre-buffering. Measured on a Galaxy
        // S25 against a live episode, it does not work: every rendition failed in turn
        // (1080p, 720p, 360p, all on vault-16.owocdn.top, "Source error"), and only the kwik.cx
        // embed played. Kwik's CDN URLs require the cookies and player flow of the page they came
        // from. Preferring them cost ~84 seconds of dead air before the stream that works.
        //
        // The embed leads, and INTERNAL_FALLBACK_PROVIDERS still lets the direct renditions be
        // tried underneath it, so if Kwik ever opens the CDN up nothing has to change here.
        "kiwi" -> embeds.firstOrNull(StreamItem::isActive)
            ?: embeds.firstOrNull()
            ?: bestHls(direct)
            ?: direct.firstOrNull()

        // Ally mixes AllAnime progressive files with an unreliable HLS mirror. Its direct files
        // are independently playable and retain the selected SUB/DUB category.
        "ally" -> direct.firstOrNull { !it.isHls }
            ?: bestHls(direct)
            ?: embeds.firstOrNull()

        else -> bestHls(direct)
            ?: direct.firstOrNull()
            ?: embeds.firstOrNull(StreamItem::isActive)
            ?: embeds.firstOrNull()
            ?: sources.streams.firstOrNull()
    }
}

/** Every distinct stream in the same order the provider's first-player policy would choose it. */
internal fun providerStreamOrder(
    provider: String,
    sources: SourcesResult,
): List<StreamItem> {
    val remaining = sources.streams.distinctBy(StreamItem::url).toMutableList()
    return buildList {
        while (remaining.isNotEmpty()) {
            val next = pickProviderStream(provider, sources.copy(streams = remaining)) ?: break
            add(next)
            remaining.removeAll { it.url == next.url }
        }
    }
}

/**
 * Providers whose own streams are worth exhausting before abandoning the provider.
 *
 * AllAnime mixes independently playable progressive files with an unreliable HLS mirror. Kiwi
 * publishes direct HLS *and* the embed page those URLs came from: the direct stream is far cheaper
 * to play (hardware decode, real pre-buffering) but its CDN has historically 403'd outside the
 * page, so preferring it is only safe while the embed remains reachable as the next candidate.
 * Without this the first 403 gave up on Kiwi entirely and jumped to an unrelated server.
 */
private val INTERNAL_FALLBACK_PROVIDERS = setOf("allanime", "kiwi")

/** Finds the next untried stream after the one that failed, wrapping once before giving up. */
internal fun nextProviderStream(
    provider: String,
    sources: SourcesResult,
    currentUrl: String,
    failedUrls: Set<String>,
): StreamItem? {
    val ordered = providerStreamOrder(provider, sources)
    if (ordered.isEmpty()) return null
    val currentIndex = ordered.indexOfFirst { it.url == currentUrl }
    val candidates = if (currentIndex >= 0) {
        ordered.drop(currentIndex + 1) + ordered.take(currentIndex)
    } else {
        ordered
    }
    return candidates.firstOrNull { it.url !in failedUrls }
}

/** A saved global choice wins; before the first explicit choice, keep the launch route behavior. */
internal fun preferredProviderForWatch(storedPreferred: String?, routeProvider: String): String {
    val stored = storedPreferred?.trim()?.lowercase().orEmpty()
    if (stored.isNotBlank() && stored != DEFAULT_PREFERRED_PROVIDER) return stored
    return routeProvider.trim().lowercase().ifBlank { DEFAULT_PREFERRED_PROVIDER }
}

/** Whether the picker already contains at least one provider from the Miruro pipe catalog. */
internal fun hasMiruroSourceOptions(options: List<WatchSourceOption>): Boolean = options.any {
    ProviderCatalog.sourceOf(it.provider) == ProviderCatalog.Source.MIRURO
}

/**
 * A merge that completes while the first resolution is suspended still requires a retry: the
 * deciding state is whether the catalog snapshot used by that resolution already contained it.
 */
internal fun shouldRetryWithMergedCatalog(
    hasResolvedSource: Boolean,
    initialCatalogIncludedFullAnivexa: Boolean,
): Boolean = !hasResolvedSource && !initialCatalogIncludedFullAnivexa

/**
 * Whether the background sweep may confirm this provider while an episode is on screen.
 * Providers that resolve through the hidden WebView run a real player to do it. Even larger
 * devices can have a single hardware decoder, and a background resolver may preempt playback or
 * leak its intro audio. Those providers stay unvalidated on every device; selecting one by hand
 * still resolves it normally.
 */
internal fun validatesDuringPlayback(provider: String): Boolean =
    provider != "allanime" && !ProviderCatalog.requiresResolverWebView(provider)

private fun bestHls(streams: List<StreamItem>): StreamItem? = streams
    .filter(StreamItem::isHls)
    .maxByOrNull { (it.height ?: 0) + if (it.isActive) 100_000 else 0 }

internal fun pickNavigationSpine(
    episodes: EpisodesResult,
    preferred: String,
    category: Category,
): List<EpisodeItem> {
    // Multi-audio servers list one set of episodes carrying both audio tracks, so a dub request
    // has to read their sub list or the spine comes back empty and navigation dies.
    fun normalized(provider: String): List<EpisodeItem> = episodes.provider(provider)
        ?.episodes(ProviderCatalog.dubCapableCategory(provider, category))
        .orEmpty()
        .distinctBy(EpisodeItem::number)
        .sortedBy(EpisodeItem::number)

    val preferredList = normalized(preferred)
    val longest = episodes.providerNames
        .asSequence()
        .map(::normalized)
        .maxByOrNull(List<EpisodeItem>::size)
        .orEmpty()
    return if (preferredList.size >= longest.size) preferredList else longest
}

/**
 * Supplemental catalogs must not invalidate playback that already resolved successfully.
 * In particular, a late catalog can replace a provider row that had dubbed episodes with a
 * sub-only row. Publishing that empty candidate with currentIndex=0 makes WatchData.current
 * index into EmptyList and crashes the app.
 */
internal fun retainNonEmptyNavigationSpine(
    current: List<EpisodeItem>,
    candidate: List<EpisodeItem>,
): List<EpisodeItem> = candidate.ifEmpty { current }
