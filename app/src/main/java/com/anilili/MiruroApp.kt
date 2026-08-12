package com.anilili

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.anilili.data.AppGraph
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.diagnostics.CrashReporter
import com.anilili.data.library.LibraryStore
import com.anilili.data.settings.SettingsStore
import com.anilili.data.reminder.ReminderManager
import com.anilili.data.reminder.AutomaticReleaseManager
import com.anilili.data.reminder.AniListNotificationPushManager
import com.anilili.data.reminder.ReleaseSyncScheduler
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.DiagnosticsPerformanceMonitor
import com.anilili.diagnostics.DiagnosticsSystemMonitor
import com.anilili.diagnostics.DiagnosticsUploadManager
import com.anilili.playback.EpisodeDownloads
import com.anilili.data.remote.AppLogShipper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MiruroApp : Application(), ImageLoaderFactory {
    /** Lives as long as the process; only used for work deliberately kept out of onCreate. */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.init(this)
        // Install before any other app work so initialization failures are captured synchronously.
        CrashReporter.init(this)
        DiagnosticsLog.event("MiruroApp.onCreate start")
        DiagnosticsLog.installLifecycleCallbacks(this)
        DiagnosticsLog.startMainThreadWatchdog()
        DiagnosticsLog.installApplicationStartInfoListener(this)
        DiagnosticsLog.snapshot(this, "MiruroApp.onCreate")
        DiagnosticsLog.event(
            "MiruroApp process=${currentProcessName() ?: "unknown"} pid=${Process.myPid()} " +
                "thread=${Thread.currentThread().name} abis=${Build.SUPPORTED_ABIS.joinToString()}",
        )
        // Do not query WebView during ordinary startup. Some provider builds initialize the
        // Chromium process even for metadata-looking calls. The actual resolver/login/player
        // factories record the provider after a WebView is genuinely needed.
        if (isDiagnosticsProcess()) {
            DiagnosticsLog.event("MiruroApp diagnostics process; skipping normal app init")
            return
        }
        DiagnosticsSystemMonitor.install(this)
        DiagnosticsPerformanceMonitor.install(this)
        // Off the startup path deliberately. Opening Media3's download index scans the cache
        // directory and opens SQLite — measured at 211ms of the 440ms spent in onCreate on a TV
        // stick, with an empty cache, and it grows with the number of downloads. Nothing during
        // startup reads it: the first real consumer is the Library screen, and every entry point
        // into EpisodeDownloads initializes on demand behind the same lock, so a caller that
        // arrives early simply waits for this to finish rather than racing it.
        startupScope.launch {
            // First, so a report from a device that "just closed" opens with the reason it closed.
            DiagnosticsLog.deviceProfile(this@MiruroApp)
            DiagnosticsLog.logPreviousExits(this@MiruroApp)
            DiagnosticsUploadManager.schedulePending(this@MiruroApp)
            runCatching { EpisodeDownloads.initialize(this@MiruroApp) }
                .onFailure { DiagnosticsLog.throwable("EpisodeDownloads initialization failed", it) }
            // Downloads and exports enqueue one uniquely-named work request per episode, so a
            // batch leaves a long tail of finished rows behind. A device on Android 10 died
            // reading that table — CursorWindowAllocationException, error -12 (out of memory) —
            // so keep it from growing without bound.
            runCatching { androidx.work.WorkManager.getInstance(this@MiruroApp).pruneWork() }
                .onFailure { DiagnosticsLog.throwable("WorkManager prune failed", it) }
        }
        if (BuildConfig.DEBUG) configureStrictMode()
        diagnosticsStep("AppGraph.init") { AppGraph.init(this) }
        diagnosticsStep("LibraryStore.init") { LibraryStore.init(this) }
        diagnosticsStep("SearchHistoryStore.init") { com.anilili.data.library.SearchHistoryStore.init(this) }
        diagnosticsStep("AuthManager.init") { AuthManager.init(this) }
        diagnosticsStep("MalAuthManager.init") { MalAuthManager.init(this, AppGraph.httpClient) }
        diagnosticsStep("SettingsStore.init") { SettingsStore.init(this) }
        diagnosticsStep("AppLogShipper.init") {
            // Use a stable Android device ID as the unique user/device identifier
            @Suppress("HardwareIds")
            val androidId = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown-device"
            AppLogShipper.init(this, androidId)
        }
        diagnosticsStep("ReminderManager.init") { ReminderManager.init(this) }
        diagnosticsStep("AutomaticReleaseManager.init") { AutomaticReleaseManager.init(this) }
        diagnosticsStep("AniListNotificationPushManager.init") { AniListNotificationPushManager.init(this) }
        diagnosticsStep("ReleaseSyncScheduler.schedule") { ReleaseSyncScheduler.schedule(this) }
        DiagnosticsLog.event("MiruroApp.onCreate complete")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiagnosticsLog.event("MiruroApp.onTrimMemory level=$level")
        DiagnosticsLog.snapshot(this, "trimMemory")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DiagnosticsLog.event("MiruroApp.onLowMemory")
        DiagnosticsLog.snapshot(this, "lowMemory")
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Share the app's connection pool and dispatcher, but not its response cache: Coil keeps
        // its own disk cache below, and leaving OkHttp's 50 MB one attached would store every
        // poster twice over — and let image traffic evict the API responses that cache exists for.
        .okHttpClient { AppGraph.httpClient.newBuilder().cache(null).build() }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(if (AppGraph.isTv) 0.12 else 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(if (AppGraph.isTv) 128L * 1024 * 1024 else 256L * 1024 * 1024)
                .build()
        }
        .crossfade(150)
        .respectCacheHeaders(false)
        .build()

    private inline fun diagnosticsStep(name: String, block: () -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        DiagnosticsLog.event("$name start")
        try {
            block()
            DiagnosticsLog.event("$name complete in ${SystemClock.elapsedRealtime() - startedAt}ms")
        } catch (throwable: Throwable) {
            DiagnosticsLog.throwable("$name failed after ${SystemClock.elapsedRealtime() - startedAt}ms", throwable)
            throw throwable
        }
    }

    private fun configureStrictMode() {
        val threadPolicy = StrictMode.ThreadPolicy.Builder().detectAll()
        val vmPolicy = StrictMode.VmPolicy.Builder().detectLeakedClosableObjects()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "anilili-strict-mode").apply { isDaemon = true }
            }
            threadPolicy.penaltyListener(executor) { violation ->
                DiagnosticsLog.throwable("StrictMode thread violation", violation)
            }
            vmPolicy.penaltyListener(executor) { violation ->
                DiagnosticsLog.throwable("StrictMode VM violation", violation)
            }
        } else {
            threadPolicy.penaltyLog()
            vmPolicy.penaltyLog()
        }
        StrictMode.setThreadPolicy(threadPolicy.build())
        StrictMode.setVmPolicy(vmPolicy.build())
    }

    private fun isDiagnosticsProcess(): Boolean = currentProcessName() == "$packageName:diagnostics"

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = Process.myPid()
            @Suppress("DEPRECATION")
            (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
}
