package com.anilili.data.remote

import com.anilili.data.model.ReactionRequest
import com.anilili.data.model.ReactionResponse
import com.anilili.data.model.ShortsPage
import com.anilili.data.model.StreamResponse
import com.anilili.data.settings.SettingsStore
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class ConnectionStatus(
    val isConnected: Boolean,
    val message: String,
)

class ShortsApiClient(
    httpClient: OkHttpClient,
    private val json: Json,
) {
    // FIX #7: Adjusted timeouts to handle yt-dlp latency (~15-30s) without false failures.
    // connectTimeout: 10s — fast failure on unreachable backend
    // readTimeout: 45s — yt-dlp resolution can take up to 30s; buffer needed
    // writeTimeout: 10s — request body writes are always small
    // callTimeout: 60s — total budget: network + yt-dlp + proxy response start
    private val httpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun sanitizeBaseUrl(inputUrl: String): String {
        var trimmed = inputUrl.trim().removeSuffix("/")
        if (trimmed.isBlank()) return ""
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "http://$trimmed"
        }
        return trimmed
    }

    private fun buildRequest(url: String, apiKeyOverride: String? = null): Request.Builder {
        val httpUrl = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid URL '$url'. Must start with http:// or https://")
        val builder = Request.Builder().url(httpUrl)
        val apiKey = (apiKeyOverride ?: SettingsStore.youtubeApiKey.value).trim()
        if (apiKey.isNotBlank()) {
            builder.header("X-YouTube-Api-Key", apiKey)
        }
        return builder
    }

    fun testConnection(
        baseUrlOverride: String? = null,
        apiKeyOverride: String? = null,
    ): ConnectionStatus {
        val baseUrl = sanitizeBaseUrl(baseUrlOverride ?: SettingsStore.shortsApiUrl.value)
        if (baseUrl.isBlank()) {
            return ConnectionStatus(isConnected = false, message = "Shorts API URL is empty.")
        }
        val apiKey = (apiKeyOverride ?: SettingsStore.youtubeApiKey.value).trim()

        return try {
            val validateUrl = "$baseUrl/youtube/validate-key"
            val request = buildRequest(validateUrl, apiKey).get().build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                ConnectionStatus(isConnected = true, message = "Connected to Shorts backend & YouTube API key is valid!")
            } else {
                val errorBody = response.body?.string().orEmpty()
                val cleanMsg = errorBody.replace(Regex("""[\{\}\"\:]"""), " ").trim().ifBlank { "HTTP ${response.code}" }
                if (response.code == 401 || response.code == 403) {
                    ConnectionStatus(isConnected = false, message = "Backend reachable, but YouTube API key check failed (${response.code})")
                } else {
                    ConnectionStatus(isConnected = false, message = "Backend error (${response.code}): $cleanMsg")
                }
            }
        } catch (e: Exception) {
            try {
                val rootReq = buildRequest(baseUrl, apiKey).get().build()
                val rootResp = httpClient.newCall(rootReq).execute()
                if (rootResp.isSuccessful) {
                    if (apiKey.isBlank()) {
                        ConnectionStatus(isConnected = false, message = "Backend reachable, but YouTube API Key is missing.")
                    } else {
                        ConnectionStatus(isConnected = false, message = "Backend online, key check error: ${e.localizedMessage}")
                    }
                } else {
                    ConnectionStatus(isConnected = false, message = "Backend returned code ${rootResp.code}")
                }
            } catch (rootError: Exception) {
                ConnectionStatus(isConnected = false, message = "Cannot connect to backend: ${rootError.localizedMessage ?: "Invalid URL or Network error"}")
            }
        }
    }

    suspend fun getShorts(cursor: String? = null, limit: Int = 10): Result<ShortsPage> {
        val baseUrl = sanitizeBaseUrl(SettingsStore.shortsApiUrl.value)
        if (baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("Shorts API URL is not configured."))
        }
        return try {
            val httpUrl = ("$baseUrl/shorts").toHttpUrlOrNull()?.newBuilder()
                ?.apply {
                    if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor)
                    addQueryParameter("limit", limit.toString())
                }?.build() ?: return Result.failure(IllegalArgumentException("Invalid Shorts API URL: $baseUrl"))

            val request = buildRequest(httpUrl.toString()).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                Result.failure(IOException("Server error ${response.code}: $errorBody"))
            } else {
                val bodyString = response.body?.string() ?: ""
                val page = json.decodeFromString<ShortsPage>(bodyString)
                Result.success(page)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStream(shortId: String): Result<StreamResponse> {
        val baseUrl = sanitizeBaseUrl(SettingsStore.shortsApiUrl.value)
        if (baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("Shorts API URL is not configured."))
        }
        return try {
            val url = "$baseUrl/shorts/$shortId/stream"
            val request = buildRequest(url).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                Result.failure(IOException("Stream request failed ${response.code}: $errorBody"))
            } else {
                val bodyString = response.body?.string() ?: ""
                val streamResp = json.decodeFromString<StreamResponse>(bodyString)
                Result.success(streamResp)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun refreshStream(shortId: String): Result<StreamResponse> = getStream(shortId)

    suspend fun react(shortId: String, reaction: String, userId: String): Result<ReactionResponse> {
        val baseUrl = sanitizeBaseUrl(SettingsStore.shortsApiUrl.value)
        if (baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("Shorts API URL is not configured."))
        }
        return try {
            val url = "$baseUrl/shorts/$shortId/react"
            val reqBodyObj = ReactionRequest(reaction = reaction, userId = userId)
            val jsonString = json.encodeToString(ReactionRequest.serializer(), reqBodyObj)
            val request = buildRequest(url).post(jsonString.toRequestBody(jsonMediaType)).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                Result.failure(IOException("Reaction failed ${response.code}: $errorBody"))
            } else {
                val bodyString = response.body?.string() ?: ""
                val reactionResp = json.decodeFromString<ReactionResponse>(bodyString)
                Result.success(reactionResp)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
