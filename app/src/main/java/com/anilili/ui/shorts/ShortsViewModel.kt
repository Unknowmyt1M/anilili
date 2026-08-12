package com.anilili.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anilili.data.AppGraph
import com.anilili.data.model.ShortsItem
import com.anilili.data.model.StreamResponse
import com.anilili.data.remote.ShortsRepository
import com.anilili.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

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
                    _uiState.value = currentState.copy(isLoadingMore = false)
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

    private val prefetchJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun onPageChanged(pageIndex: Int) {
        val state = _uiState.value as? ShortsUiState.Loaded ?: return
        val items = state.items

        if (pageIndex in items.indices) {
            val currentItem = items[pageIndex]
            if (currentItem.videoUrl.isNullOrBlank()) {
                resolveStreamFor(currentItem.id)
            }

            val nextIndex1 = pageIndex + 1
            if (nextIndex1 in items.indices) {
                val item1 = items[nextIndex1]
                if (item1.videoUrl.isNullOrBlank()) {
                    prefetchStreamFor(item1.id)
                }
            }

            val nextIndex2 = pageIndex + 2
            if (nextIndex2 in items.indices) {
                val item2 = items[nextIndex2]
                if (item2.videoUrl.isNullOrBlank()) {
                    prefetchStreamFor(item2.id)
                }
            }

            val allowedIds = setOfNotNull(
                items.getOrNull(pageIndex)?.id,
                items.getOrNull(pageIndex + 1)?.id,
                items.getOrNull(pageIndex + 2)?.id,
            )
            prefetchJobs.keys.filter { it !in allowedIds }.forEach { distantId ->
                prefetchJobs[distantId]?.cancel()
                prefetchJobs.remove(distantId)
            }
        }
    }

    private fun resolveStreamFor(shortId: String) {
        prefetchJobs[shortId]?.cancel()
        val job = viewModelScope.launch {
            val res = repository.getStream(shortId)
            res.getOrNull()?.let { streamResp ->
                updateItemStream(shortId, streamResp)
            }
        }
        prefetchJobs[shortId] = job
    }

    private fun prefetchStreamFor(shortId: String) {
        if (prefetchJobs.containsKey(shortId)) return
        val job = viewModelScope.launch {
            val res = repository.getStream(shortId)
            res.getOrNull()?.let { streamResp ->
                updateItemStream(shortId, streamResp)
            }
        }
        prefetchJobs[shortId] = job
    }

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
            val res = repository.refreshStream(shortId)
            val streamResp = res.getOrNull()
            if (streamResp != null && !streamResp.videoUrl.isNullOrBlank()) {
                updateItemStream(shortId, streamResp)
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
