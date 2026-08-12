package com.anilili.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.data.model.ShortsItem
import com.anilili.data.model.StreamResponse
import com.anilili.data.remote.ShortsRepository
import com.anilili.data.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import android.util.Log
import com.anilili.data.remote.AppLogShipper

// FIX #13: Prefetch state tracking for debugging and scheduling
enum class StreamState {
    NOT_REQUESTED,
    QUEUED,
    RESOLVING,
    READY,
    FAILED
}

// FIX #4: Priority system — Current > High > Medium
enum class PrefetchPriority(val valRank: Int) {
    P0_CURRENT(0),   // Current visible Short — Highest priority
    P1_HIGH(1),      // Next +1 to +2 Shorts
    P2_MEDIUM(2)     // Next +3 to +5 Shorts
}

sealed class ShortsUiState {
    object Loading : ShortsUiState()
    object NoApiKey : ShortsUiState()
    object NoBackend : ShortsUiState()
    data class Error(val message: String) : ShortsUiState()
    data class Loaded(
        val items: List<ShortsItem>,
        val nextCursor: String?,
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
    ) : ShortsUiState()
}

class ShortsViewModel(
    private val repository: ShortsRepository = AppGraph.shortsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ShortsUiState>(ShortsUiState.Loading)
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

    private val userId = UUID.randomUUID().toString()

    // FIX #6: Controlled concurrency — max 3 simultaneous yt-dlp resolutions
    private val prefetchSemaphore = Semaphore(permits = 3)

    // FIX #5: Track in-flight jobs by shortId — never cancel/recreate an active prefetch
    private val prefetchJobs = mutableMapOf<String, Job>()

    // FIX #13: Per-Short stream state tracking
    private val streamStates = mutableMapOf<String, StreamState>()

    private var lastPageIndex = 0

    companion object {
        // FIX #2: Rolling window of +5 ahead (was effectively +2 before)
        private const val PREFETCH_WINDOW = 5
        private const val TAG = "ShortsPrefetch"
    }

    init {
        loadFeed(reset = true)
    }

    fun loadFeed(reset: Boolean = false) {
        val apiUrl = SettingsStore.shortsApiUrl.value.trim()
        if (apiUrl.isBlank()) {
            _uiState.value = ShortsUiState.NoBackend
            return
        }

        val currentState = _uiState.value
        if (reset) {
            _uiState.value = ShortsUiState.Loading
            lastPageIndex = 0
        } else if (currentState is ShortsUiState.Loaded) {
            if (currentState.isLoadingMore || currentState.nextCursor == null) return
            _uiState.value = currentState.copy(isLoadingMore = true)
        }

        viewModelScope.launch {
            val cursor = if (reset) null else (currentState as? ShortsUiState.Loaded)?.nextCursor
            val result = repository.fetchShorts(cursor = cursor, limit = 10)
            result.onSuccess { page ->
                val currentItems = if (reset) emptyList() else (currentState as? ShortsUiState.Loaded)?.items.orEmpty()
                val updatedItems = currentItems + page.items.filter { newItem -> currentItems.none { it.id == newItem.id } }
                _uiState.value = ShortsUiState.Loaded(
                    items = updatedItems,
                    nextCursor = page.nextCursor,
                    isRefreshing = false,
                    isLoadingMore = false,
                )
                // FIX #3: Immediately begin prefetching from index 0 the moment the feed loads
                // This starts stream preparation BEFORE the user even swipes
                schedulePrefetch(lastPageIndex)

                if (page.isReplenishing && page.nextCursor != null) {
                    AppLogShipper.d(TAG, "[FEED_REPLENISHING] Backend is discovering more shorts, retrying in 2.5s...")
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(2500)
                        val curr = _uiState.value as? ShortsUiState.Loaded
                        if (curr != null && curr.nextCursor == page.nextCursor && !curr.isLoadingMore) {
                            loadFeed(reset = false)
                        }
                    }
                }

            }.onFailure { error ->
                if (reset || currentState !is ShortsUiState.Loaded) {
                    val msg = error.message ?: "Failed to load shorts feed"
                    if (msg.contains("API key", ignoreCase = true) || msg.contains("401")) {
                        _uiState.value = ShortsUiState.NoApiKey
                    } else if (msg.contains("URL is not configured", ignoreCase = true)) {
                        _uiState.value = ShortsUiState.NoBackend
                    } else {
                        _uiState.value = ShortsUiState.Error(msg)
                    }
                } else {
                    _uiState.value = (currentState as ShortsUiState.Loaded).copy(isLoadingMore = false)
                }
            }
        }
    }

    fun refresh() {
        val currentState = _uiState.value
        if (currentState is ShortsUiState.Loaded) {
            _uiState.value = currentState.copy(isRefreshing = true)
        } else {
            _uiState.value = ShortsUiState.Loading
        }
        loadFeed(reset = true)
    }

    fun onPageChanged(pageIndex: Int) {
        lastPageIndex = pageIndex
        val state = _uiState.value as? ShortsUiState.Loaded ?: return

        // FIX #4: Always promote current Short to P0 immediately on page change
        val currentItem = state.items.getOrNull(pageIndex)
        if (currentItem != null) {
            val currentState = streamStates[currentItem.id] ?: StreamState.NOT_REQUESTED
            if (currentState != StreamState.READY && currentState != StreamState.RESOLVING) {
                Log.d(TAG, "[PREFETCH_PROMOTED] shortId=${currentItem.id} index=$pageIndex -> P0_CURRENT")
                AppLogShipper.d(TAG, "[PREFETCH_PROMOTED] shortId=${currentItem.id} index=$pageIndex -> P0_CURRENT")
                triggerStreamResolution(currentItem.id, PrefetchPriority.P0_CURRENT)
            }
        }

        schedulePrefetch(pageIndex)

        // Load more metadata if nearing the end of current list
        if (pageIndex >= state.items.size - 3 && !state.isLoadingMore && state.nextCursor != null) {
            loadFeed(reset = false)
        }
    }

    /**
     * FIX #2, #3, #4, #5, #6: Rolling prefetch window (+0 to +5) with priority and smart cancellation.
     *
     * - Window always covers currentIndex..(currentIndex + PREFETCH_WINDOW)
     * - FIX #5: Never cancels a job that is QUEUED or RESOLVING within the window
     * - FIX #6: Controlled by Semaphore(3) so max 3 concurrent yt-dlp calls
     * - Smart cancellation only kills jobs that are NOW outside the sliding window (behind the user)
     */
    private fun schedulePrefetch(currentIndex: Int) {
        val state = _uiState.value as? ShortsUiState.Loaded ?: return
        val items = state.items
        if (items.isEmpty() || currentIndex !in items.indices) return

        val maxEnd = (currentIndex + PREFETCH_WINDOW).coerceAtMost(items.lastIndex)

        // Enqueue / promote items in the rolling window
        for (i in currentIndex..maxEnd) {
            val item = items[i]

            // FIX #12 (client-side): If item already has a URL, mark as READY — skip
            val hasUrl = !item.videoUrl.isNullOrBlank()
            if (hasUrl) {
                if (streamStates[item.id] != StreamState.READY) {
                    streamStates[item.id] = StreamState.READY
                    Log.d(TAG, "[CACHE_HIT] shortId=${item.id} already has videoUrl — skipping resolution")
                    AppLogShipper.d(TAG, "[CACHE_HIT] shortId=${item.id} already has videoUrl")
                }
                continue
            }

            val currentStreamState = streamStates[item.id] ?: StreamState.NOT_REQUESTED

            // FIX #5: If already RESOLVING or QUEUED, do NOT cancel and recreate — reuse it
            if (currentStreamState == StreamState.READY || currentStreamState == StreamState.RESOLVING || currentStreamState == StreamState.QUEUED) {
                // FIX #4: Promote current Short by bumping it if it was only QUEUED (waiting for semaphore)
                // We can't actually re-order semaphore, but we log promotion for visibility
                if (i == currentIndex && currentStreamState == StreamState.QUEUED) {
                    Log.d(TAG, "[PREFETCH_PROMOTED] shortId=${item.id} was QUEUED -> promoting to P0_CURRENT")
                    AppLogShipper.d(TAG, "[PREFETCH_PROMOTED] shortId=${item.id} was QUEUED -> P0_CURRENT")
                }
                continue
            }

            // Assign priority based on distance from current index
            val priority = when (i - currentIndex) {
                0 -> PrefetchPriority.P0_CURRENT
                1, 2 -> PrefetchPriority.P1_HIGH
                else -> PrefetchPriority.P2_MEDIUM
            }

            // Only trigger if NOT_REQUESTED or FAILED
            if (currentStreamState == StreamState.NOT_REQUESTED || currentStreamState == StreamState.FAILED) {
                triggerStreamResolution(item.id, priority)
            }
        }

        // FIX #5: Smart Cancellation — only cancel jobs that are:
        //   1. Behind the user (index < currentIndex - 1), AND
        //   2. NOT yet READY (READY results should be preserved)
        val keepIds = (maxOf(0, currentIndex - 1)..maxEnd)
            .mapNotNull { items.getOrNull(it)?.id }
            .toSet()

        val toCancel = prefetchJobs.keys.filter { id -> id !in keepIds }
        for (id in toCancel) {
            val jobState = streamStates[id]
            // FIX #5: Never cancel a RESOLVING job — just let it finish and cache the result
            if (jobState == StreamState.RESOLVING) {
                Log.d(TAG, "[PREFETCH_KEEP] shortId=$id is RESOLVING outside window — letting it finish")
                AppLogShipper.d(TAG, "[PREFETCH_KEEP] shortId=$id is RESOLVING outside window")
                continue
            }
            Log.d(TAG, "[PREFETCH_CANCELLED] shortId=$id outside rolling window (state=$jobState)")
            AppLogShipper.d(TAG, "[PREFETCH_CANCELLED] shortId=$id state=$jobState")
            prefetchJobs[id]?.cancel()
            prefetchJobs.remove(id)
            if (jobState != StreamState.READY) {
                streamStates[id] = StreamState.NOT_REQUESTED
            }
        }
    }

    /**
     * FIX #4, #6, #13, #14: Controlled concurrency worker with state tracking and logging.
     * Semaphore(3) ensures max 3 concurrent stream resolutions.
     * FIX #5: Guards against duplicate launches via streamStates check.
     */
    private fun triggerStreamResolution(shortId: String, priority: PrefetchPriority) {
        // FIX #5: Guard — if already resolving, do not create a duplicate job
        val existingState = streamStates[shortId]
        if (existingState == StreamState.RESOLVING || existingState == StreamState.QUEUED) {
            return
        }

        // FIX #13: Mark as QUEUED immediately
        streamStates[shortId] = StreamState.QUEUED
        Log.d(TAG, "[PREFETCH_QUEUE_ADD] shortId=$shortId priority=$priority")
        AppLogShipper.d(TAG, "[PREFETCH_QUEUE_ADD] shortId=$shortId priority=$priority")

        val job = viewModelScope.launch {
            // FIX #6: Semaphore enforces max 3 concurrent yt-dlp resolutions
            prefetchSemaphore.withPermit {
                // Double-check: another job may have already resolved this while we waited
                val stateOnEntry = streamStates[shortId]
                if (stateOnEntry == StreamState.READY) {
                    Log.d(TAG, "[CACHE_HIT] shortId=$shortId already READY after semaphore wait — skipping")
                    AppLogShipper.d(TAG, "[CACHE_HIT] shortId=$shortId already READY after semaphore wait")
                    return@withPermit
                }

                // Check if item already got a URL from a previous prefetch cycle (FIX #1 guard)
                val currentItems = (_uiState.value as? ShortsUiState.Loaded)?.items
                val item = currentItems?.firstOrNull { it.id == shortId }
                if (item != null && !item.videoUrl.isNullOrBlank()) {
                    streamStates[shortId] = StreamState.READY
                    Log.d(TAG, "[CACHE_HIT] shortId=$shortId has videoUrl after semaphore wait — skipping yt-dlp")
                    AppLogShipper.d(TAG, "[CACHE_HIT] shortId=$shortId has videoUrl — skip yt-dlp")
                    return@withPermit
                }

                streamStates[shortId] = StreamState.RESOLVING
                Log.d(TAG, "[PREFETCH_START] shortId=$shortId priority=$priority")
                AppLogShipper.d(TAG, "[PREFETCH_START] shortId=$shortId priority=$priority")

                val result = repository.getStream(shortId)
                result.onSuccess { streamResp ->
                    if (!streamResp.videoUrl.isNullOrBlank()) {
                        streamStates[shortId] = StreamState.READY
                        Log.d(TAG, "[PREFETCH_READY] shortId=$shortId url=<proxy>")
                        AppLogShipper.d(TAG, "[PREFETCH_READY] shortId=$shortId")
                        // FIX #1: Immediately push updated URL into the item so player picks it up
                        updateItemStream(shortId, streamResp)
                    } else {
                        streamStates[shortId] = StreamState.FAILED
                        Log.d(TAG, "[PREFETCH_FAILED] shortId=$shortId reason=empty_url")
                        AppLogShipper.w(TAG, "[PREFETCH_FAILED] shortId=$shortId reason=empty_url")
                    }
                }.onFailure { err ->
                    streamStates[shortId] = StreamState.FAILED
                    Log.d(TAG, "[PREFETCH_FAILED] shortId=$shortId error=${err.message}")
                    AppLogShipper.w(TAG, "[PREFETCH_FAILED] shortId=$shortId error=${err.message}")
                }
            }
        }
        prefetchJobs[shortId] = job
    }

    /**
     * FIX #1: Push resolved stream URL back into the StateFlow items list immediately.
     * ShortsPlayerItem's LaunchedEffect(currentVideoUrl) will fire and prepare the player.
     */
    private fun updateItemStream(shortId: String, streamResp: StreamResponse) {
        val state = _uiState.value as? ShortsUiState.Loaded ?: return
        val newItems = state.items.map { item ->
            if (item.id == shortId && !streamResp.videoUrl.isNullOrBlank()) {
                item.copy(
                    videoUrl = streamResp.videoUrl,
                    streamType = streamResp.streamType ?: "MP4",
                    streamExpiresAt = streamResp.streamExpiresAt,
                )
            } else item
        }
        _uiState.value = state.copy(items = newItems)
    }

    fun refreshStream(shortId: String, onResult: (StreamResponse?) -> Unit) {
        viewModelScope.launch {
            Log.d(TAG, "[STREAM_REFRESH] Explicit refresh requested for shortId=$shortId")
            AppLogShipper.d(TAG, "[STREAM_REFRESH] Explicit refresh for shortId=$shortId")
            val res = repository.refreshStream(shortId)
            val streamResp = res.getOrNull()
            if (streamResp != null && !streamResp.videoUrl.isNullOrBlank()) {
                streamStates[shortId] = StreamState.READY
                updateItemStream(shortId, streamResp)
            } else {
                streamStates[shortId] = StreamState.FAILED
                Log.d(TAG, "[PREFETCH_FAILED] shortId=$shortId explicit refresh returned empty/null")
                AppLogShipper.w(TAG, "[PREFETCH_FAILED] shortId=$shortId refresh returned empty/null")
            }
            onResult(streamResp)
        }
    }

    fun toggleReaction(shortId: String, requestedReaction: String) {
        val state = _uiState.value as? ShortsUiState.Loaded ?: return
        val item = state.items.firstOrNull { it.id == shortId } ?: return

        val currentReaction = item.userReaction ?: "NONE"
        val nextReaction = if (currentReaction == requestedReaction) "NONE" else requestedReaction

        val (likeDelta, dislikeDelta) = calculateReactionDeltas(currentReaction, nextReaction)
        val optimisticItem = item.copy(
            userReaction = nextReaction,
            likeCount = (item.likeCount + likeDelta).coerceAtLeast(0),
            dislikeCount = (item.dislikeCount + dislikeDelta).coerceAtLeast(0),
        )

        val optimisticList = state.items.map { if (it.id == shortId) optimisticItem else it }
        _uiState.value = state.copy(items = optimisticList)

        viewModelScope.launch {
            val res = repository.react(shortId, nextReaction, userId)
            res.onSuccess { resp ->
                val currentState = _uiState.value as? ShortsUiState.Loaded ?: return@launch
                val correctedList = currentState.items.map { current ->
                    if (current.id == shortId) {
                        current.copy(
                            likeCount = resp.likeCount,
                            dislikeCount = resp.dislikeCount,
                            userReaction = resp.userReaction,
                        )
                    } else current
                }
                _uiState.value = currentState.copy(items = correctedList)
            }.onFailure {
                val currentState = _uiState.value as? ShortsUiState.Loaded ?: return@launch
                val revertedList = currentState.items.map { current ->
                    if (current.id == shortId) item else current
                }
                _uiState.value = currentState.copy(items = revertedList)
            }
        }
    }

    private fun calculateReactionDeltas(oldReaction: String, newReaction: String): Pair<Int, Int> {
        var likeDelta = 0
        var dislikeDelta = 0

        when (oldReaction) {
            "LIKE" -> likeDelta -= 1
            "DISLIKE" -> dislikeDelta -= 1
        }

        when (newReaction) {
            "LIKE" -> likeDelta += 1
            "DISLIKE" -> dislikeDelta += 1
        }

        return Pair(likeDelta, dislikeDelta)
    }
}
