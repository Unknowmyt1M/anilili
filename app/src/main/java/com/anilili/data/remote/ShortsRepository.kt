package com.anilili.data.remote

import com.anilili.data.model.ReactionResponse
import com.anilili.data.model.ShortsPage
import com.anilili.data.model.StreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShortsRepository(
    private val apiClient: ShortsApiClient,
) {
    suspend fun fetchShorts(cursor: String? = null, limit: Int = 10): Result<ShortsPage> =
        withContext(Dispatchers.IO) {
            apiClient.getShorts(cursor, limit)
        }

    suspend fun getStream(shortId: String): Result<StreamResponse> =
        withContext(Dispatchers.IO) {
            apiClient.getStream(shortId)
        }

    suspend fun refreshStream(shortId: String): Result<StreamResponse> = getStream(shortId)

    suspend fun react(shortId: String, reaction: String, userId: String): Result<ReactionResponse> =
        withContext(Dispatchers.IO) {
            apiClient.react(shortId, reaction, userId)
        }

    suspend fun testConnection(baseUrl: String? = null, apiKey: String? = null): ConnectionStatus =
        withContext(Dispatchers.IO) {
            apiClient.testConnection(baseUrl, apiKey)
        }
}
