package com.anilili.data.remote

import android.content.Context
import android.os.Build
import android.util.Log
import com.anilili.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * AppLogShipper — Ships Android app logs (Debug/Verbose/Info/Warning/Error) to
 * the Anilili Shorts backend at /api/app-logs in batches.
 *
 * Usage:
 *   // In Application.onCreate() or wherever you init your app:
 *   AppLogShipper.init(context, deviceId = "some-unique-id")
 *
 *   // Anywhere you want to log:
 *   AppLogShipper.log("INFO", "ShortsPrefetch", "[PREFETCH_READY] shortId=xyz")
 *
 * The shipper auto-batches logs and ships them every 5 seconds.
 * This is designed to be lightweight — OkHttp calls are on IO dispatcher.
 */
object AppLogShipper {

    private const val TAG = "AppLogShipper"
    private const val BATCH_INTERVAL_MS = 5_000L
    private const val MAX_QUEUE_SIZE = 2000

    @Serializable
    data class LogEntry(
        val level: String,
        val tag: String,
        val message: String,
        val timestamp: String,
    )

    @Serializable
    data class LogBatch(
        val deviceId: String,
        val appVersion: String,
        val logs: List<LogEntry>,
    )

    private val queue = ConcurrentLinkedQueue<LogEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var deviceId: String = "unknown"
    private var appVersion: String = "unknown"
    private var initialized = false

    /**
     * Initialize the shipper. Call once in Application.onCreate().
     * @param context Application context (used to read package version)
     * @param deviceId Unique ID for this device/user (e.g. Android ID or UUID stored in SharedPrefs)
     */
    fun init(context: Context, deviceId: String) {
        if (initialized) return
        initialized = true

        this.deviceId = deviceId
        this.appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        log("INFO", TAG, "[AppLogShipper] Initialized — deviceId=$deviceId appVersion=$appVersion model=${Build.MODEL}")

        // Start batch shipping loop
        scope.launch {
            while (true) {
                delay(BATCH_INTERVAL_MS)
                shipPending()
            }
        }
    }

    /**
     * Enqueue a log entry for shipping. Thread-safe.
     * Call this from anywhere in the app.
     */
    fun log(level: String, tag: String, message: String) {
        if (queue.size >= MAX_QUEUE_SIZE) {
            queue.poll() // Drop oldest if queue overflows
        }
        val ts = java.time.Instant.now().toString()
        queue.add(LogEntry(level = level.uppercase(), tag = tag, message = message, timestamp = ts))
    }

    // Convenience wrappers matching Android Log levels
    fun d(tag: String, msg: String) { log("DEBUG", tag, msg); Log.d(tag, msg) }
    fun i(tag: String, msg: String) { log("INFO", tag, msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { log("WARNING", tag, msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String) { log("ERROR", tag, msg); Log.e(tag, msg) }
    fun v(tag: String, msg: String) { log("VERBOSE", tag, msg); Log.v(tag, msg) }

    /**
     * Force ship all pending logs immediately (e.g., on screen pause or app backgrounded).
     */
    fun flush() {
        scope.launch { shipPending() }
    }

    private suspend fun shipPending() {
        if (queue.isEmpty()) return

        val apiUrl = SettingsStore.shortsApiUrl.value.trim().trimEnd('/')
        if (apiUrl.isBlank()) return

        // Drain up to 200 logs per batch
        val batch = mutableListOf<LogEntry>()
        repeat(200) {
            val entry = queue.poll() ?: return@repeat
            batch.add(entry)
        }
        if (batch.isEmpty()) return

        val payload = LogBatch(
            deviceId = deviceId,
            appVersion = appVersion,
            logs = batch,
        )

        try {
            val bodyStr = json.encodeToString(payload)
            val request = Request.Builder()
                .url("$apiUrl/api/app-logs")
                .post(bodyStr.toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "[AppLogShipper] Ship failed HTTP ${response.code} — re-queuing ${batch.size} logs")
                // Re-enqueue on failure (best effort, don't re-queue if overflow)
                if (queue.size + batch.size <= MAX_QUEUE_SIZE) {
                    batch.forEach { queue.offer(it) }
                }
            }
            response.body?.close()
        } catch (e: Exception) {
            Log.w(TAG, "[AppLogShipper] Ship exception: ${e.message} — re-queuing ${batch.size} logs")
            if (queue.size + batch.size <= MAX_QUEUE_SIZE) {
                batch.forEach { queue.offer(it) }
            }
        }
    }
}
