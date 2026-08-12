package com.anilili.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.ApplicationStartInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.view.ViewTreeObserver
import android.provider.Settings
import androidx.work.WorkManager
import com.anilili.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.ArrayDeque
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local-first, structured diagnostics for sideloaded phones and Android/Fire TV devices. */
object DiagnosticsLog {
    private const val LOG_DIR = "diagnostics"
    private const val SHARE_FILE_PREFIX = "Anilili-diagnostics"
    private const val CACHED_REPORT_MAX_AGE_MS = 24L * 60 * 60 * 1_000
    private const val EVENT_FILE_BYTES = 1_000_000L
    private const val MAX_EXIT_TRACE_BYTES = 1_500_000L
    private const val PROCESS_STATE_MIN_INTERVAL_MS = 5_000L
    private const val MAX_RECENT_SUMMARY_EVENTS = 160
    private val KNOWN_WEBVIEW_PROVIDERS = listOf(
        "com.amazon.webview",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.webview.beta",
        "com.google.android.webview.dev",
        "com.google.android.webview.canary",
    )
    private val categorySanitizer = Regex("[^A-Za-z0-9_-]")
    private val eventNameSanitizer = Regex("[^A-Za-z0-9_.-]")
    private val processSuffixSanitizer = Regex("[^A-Za-z0-9_.-]")

    @Volatile private var appContext: Context? = null
    @Volatile private var store: DiagnosticFileStore? = null
    @Volatile private var processName = "unknown"
    @Volatile private var lifecycleCallbacksInstalled = false
    @Volatile private var watchdogStarted = false
    @Volatile private var lastMainBlockLogAt = 0L
    @Volatile private var lastProcessStateAt = 0L

    private val sessionId = UUID.randomUUID().toString()
    private val processStartedElapsedMs = SystemClock.elapsedRealtime()
    private val sequence = AtomicLong(0)

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        processName = currentProcessName(app) ?: app.packageName
        if (store == null) {
            val processSuffix = processName
                .removePrefix(app.packageName)
                .trimStart(':')
                .ifBlank { "main" }
                .replace(processSuffixSanitizer, "_")
            store = DiagnosticFileStore(
                directory = diagnosticsDirectory(app),
                fileStem = "events-$processSuffix",
                maxBytes = EVENT_FILE_BYTES,
            )
        }
        event(
            category = "app",
            name = "process.start",
            attributes = mapOf(
                "appVersion" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "buildType" to BuildConfig.BUILD_TYPE,
                "buildSha" to BuildConfig.GIT_SHA,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
            ),
        )
    }

    fun event(message: String) {
        record(
            level = "INFO",
            category = inferredCategory(message),
            name = inferredName(message),
            message = message,
        )
        maybeUpdateProcessState(message)
    }

    fun event(
        category: String,
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        message: String? = null,
    ) {
        record(
            level = "INFO",
            category = category,
            name = name,
            message = message,
            attributes = attributes.mapValues { (_, value) -> value?.toString() ?: "null" },
        )
    }

    fun throwable(message: String, throwable: Throwable) {
        recordThrowable("ERROR", message, throwable, blocking = false)
    }

    /** Synchronous and fs-backed so the final event survives process teardown. */
    fun fatal(message: String, throwable: Throwable) {
        recordThrowable("FATAL", message, throwable, blocking = true)
    }

    fun threadStack(message: String, thread: Thread) {
        record(
            level = "WARN",
            category = "thread",
            name = "thread.stack",
            message = message,
            attributes = mapOf(
                "targetThread" to thread.name,
                "stackTrace" to stackTrace(thread),
            ),
        )
    }

    fun flush(timeoutMs: Long = 2_000): Boolean = store?.flush(timeoutMs) ?: true

    fun installLifecycleCallbacks(application: Application) {
        if (lifecycleCallbacksInstalled) return
        lifecycleCallbacksInstalled = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) =
                lifecycle(activity, "created", mapOf("savedState" to (savedInstanceState != null)))

            override fun onActivityStarted(activity: Activity) = lifecycle(activity, "started")
            override fun onActivityResumed(activity: Activity) = lifecycle(activity, "resumed")
            override fun onActivityPaused(activity: Activity) = lifecycle(activity, "paused")
            override fun onActivityStopped(activity: Activity) = lifecycle(activity, "stopped")

            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) =
                lifecycle(activity, "save_instance_state")

            override fun onActivityDestroyed(activity: Activity) = lifecycle(
                activity,
                "destroyed",
                mapOf("finishing" to activity.isFinishing),
            )
        })
    }

    fun startMainThreadWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        val main = Handler(Looper.getMainLooper())
        Thread {
            while (true) {
                val postedAt = SystemClock.elapsedRealtime()
                val responded = AtomicBoolean(false)
                main.post {
                    responded.set(true)
                    val delayMs = SystemClock.elapsedRealtime() - postedAt
                    if (delayMs > 5_000) {
                        event("thread", "main.recovered", mapOf("blockedMs" to delayMs))
                    }
                }
                runCatching { Thread.sleep(6_000) }
                if (!responded.get()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMainBlockLogAt > 15_000) {
                        lastMainBlockLogAt = now
                        val mainThread = Looper.getMainLooper().thread
                        record(
                            level = "WARN",
                            category = "thread",
                            name = "main.blocked",
                            attributes = mapOf(
                                "blockedMoreThanMs" to "6000",
                                "mainStack" to stackTrace(mainThread),
                                "allThreads" to allThreadStacks(),
                            ),
                        )
                    }
                }
                runCatching { Thread.sleep(2_000) }
            }
        }.apply {
            name = "anilili-diagnostics-watchdog"
            isDaemon = true
            start()
        }
        event("thread", "main.watchdog.started")
    }

    fun snapshot(context: Context, label: String) {
        event(
            category = "system",
            name = "system.snapshot",
            attributes = systemSnapshot(context) + ("label" to label),
        )
    }

    fun deviceProfile(context: Context) {
        runCatching {
            event(
                category = "system",
                name = "device.profile",
                attributes = deviceProfileMap(context) + networkSnapshot(context),
            )
        }.onFailure { throwable("device profile unavailable", it) }
    }

    fun webViewPackage(label: String) {
        // WebView.getCurrentWebViewPackage() looks like a metadata query but WebView 150 can fully
        // initialize Chromium and launch a sandbox renderer. On a low-memory TV that diagnostic
        // call alone consumed ~122 MB before any resolver existed. Read Android's selected
        // provider setting and package metadata instead; an unknown provider is safer than
        // starting a browser solely to identify it.
        val context = appContext
        val configuredPackage = context?.let {
            runCatching { Settings.Global.getString(it.contentResolver, "webview_provider") }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        }
        @Suppress("DEPRECATION")
        val pkg = context?.let { app ->
            buildList {
                configuredPackage?.let(::add)
                addAll(KNOWN_WEBVIEW_PROVIDERS)
            }.distinct().firstNotNullOfOrNull { candidate ->
                runCatching { app.packageManager.getPackageInfo(candidate, 0) }
                    .getOrNull()
                    ?.takeIf { it.applicationInfo?.enabled != false }
            }
        }
        event(
            category = "webview",
            name = "webview.package",
            attributes = mapOf(
                "label" to label,
                "package" to (pkg?.packageName ?: configuredPackage ?: "unknown"),
                "version" to (pkg?.versionName ?: "none"),
                "versionCode" to (pkg?.longVersionCodeCompat() ?: 0L),
                "lookup" to "selected-provider-setting",
            ),
        )
    }

    fun watchFirstDraw(view: View, label: String, timeoutMs: Long = 5_000) {
        val startedAt = SystemClock.elapsedRealtime()
        val drawn = AtomicBoolean(false)
        view.post {
            event(
                "render",
                "decor.posted",
                viewAttributes(view) + mapOf("label" to label),
            )
        }
        Choreographer.getInstance().postFrameCallback {
            event(
                "render",
                "first.choreographer_frame",
                mapOf("label" to label, "elapsedMs" to (SystemClock.elapsedRealtime() - startedAt)),
            )
        }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (drawn.compareAndSet(false, true)) {
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
                    event(
                        "render",
                        "first.pre_draw",
                        viewAttributes(view) + mapOf(
                            "label" to label,
                            "elapsedMs" to (SystemClock.elapsedRealtime() - startedAt),
                        ),
                    )
                }
                return true
            }
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        view.postDelayed({
            if (!drawn.get()) {
                event(
                    "render",
                    "first.pre_draw.timeout",
                    viewAttributes(view) + mapOf("label" to label, "timeoutMs" to timeoutMs),
                )
            }
        }, timeoutMs)
    }

    /** Captures system-owned exit reasons plus ANR/native traces on Android 11 and newer. */
    fun logPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            event("process", "exit_history.unavailable", mapOf("sdk" to Build.VERSION.SDK_INT))
            return
        }
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (exits.isEmpty()) {
                event("process", "exit_history.empty")
                return
            }
            exits.forEachIndexed { index, info ->
                val state = info.processStateSummary
                    ?.toString(StandardCharsets.UTF_8)
                    ?.let(DiagnosticRedactor::redactText)
                val traceName = saveExitTrace(context, index, info)
                event(
                    category = "process",
                    name = "process.previous_exit",
                    attributes = mapOf(
                        "reason" to exitReasonName(info.reason),
                        "status" to info.status,
                        "importance" to info.importance,
                        "pssKb" to info.pss,
                        "rssKb" to info.rss,
                        "timestampMs" to info.timestamp,
                        "process" to info.processName,
                        "pid" to info.pid,
                        "description" to (info.description ?: "none"),
                        "processState" to (state ?: "none"),
                        "traceFile" to (traceName ?: "none"),
                    ),
                )
            }
        }.onFailure { throwable("previous exit reasons unavailable", it) }
    }

    /** Uses Android 15's authoritative cold/warm/hot and first-frame startup record. */
    fun installApplicationStartInfoListener(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            lateinit var listener: Consumer<ApplicationStartInfo>
            listener = Consumer { info ->
                logApplicationStartInfo(info)
                runCatching { manager.removeApplicationStartInfoCompletionListener(listener) }
            }
            manager.addApplicationStartInfoCompletionListener(context.mainExecutor, listener)
        }.onFailure { throwable("application start info unavailable", it) }
    }

    fun updateProcessState(component: String, state: String, attributes: Map<String, Any?> = emptyMap()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessStateAt < PROCESS_STATE_MIN_INTERVAL_MS) return
        lastProcessStateAt = now
        val safe = buildString {
            append(component.take(24))
            append(':')
            append(DiagnosticRedactor.redactText(state).take(72))
            DiagnosticRedactor.redactAttributes(attributes).entries.take(3).forEach { (key, value) ->
                append(';').append(key.take(12)).append('=').append(value.take(20))
            }
        }.toByteArray(StandardCharsets.UTF_8).take(128).toByteArray()
        runCatching {
            (appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.setProcessStateSummary(safe)
        }.onFailure { throwable("process state summary unavailable", it) }
    }

    @Synchronized
    fun createShareSnapshot(context: Context): File {
        snapshot(context, "report-generation")
        flush()
        val diagnosticsDir = diagnosticsDirectory(context)
        val outputDirectory = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
        cleanupCachedReports(outputDirectory)
        val reportName = "$SHARE_FILE_PREFIX-${fileTimestamp()}-${UUID.randomUUID().toString().take(8)}.zip"
        val report = outputDirectory.resolve(reportName)
        val temporary = outputDirectory.resolve("$reportName.tmp")
        val eventSnapshotDirectory = outputDirectory.resolve("event-snapshot-${UUID.randomUUID()}")
        try {
            val eventFiles = DiagnosticFileStore.snapshotEventFiles(diagnosticsDir, eventSnapshotDirectory)
            var eventCount = 0
            val recentLines = ArrayDeque<String>(MAX_RECENT_SUMMARY_EVENTS)
            DiagnosticFileStore.forEachOrderedLine(eventFiles) { line ->
                eventCount++
                if (recentLines.size == MAX_RECENT_SUMMARY_EVENTS) recentLines.removeFirst()
                recentLines.addLast(line)
            }
            val system = systemSnapshot(context) + deviceProfileMap(context) + networkSnapshot(context)
            val work = workManagerSummary(context)
            val manifest = DiagnosticManifest(
                generatedUtc = timestampUtc(),
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildType = BuildConfig.BUILD_TYPE,
                buildSha = BuildConfig.GIT_SHA,
                packageName = context.packageName,
                device = system,
                diagnostics = mapOf(
                    "eventCount" to eventCount.toString(),
                    "droppedEventsThisProcess" to (store?.droppedCount() ?: 0L).toString(),
                    "sessionId" to sessionId,
                    "process" to processName,
                    "format" to "zip/jsonl",
                    "privacy" to "central-redaction-enabled",
                    "nativeTracePrivacy" to "raw-system-trace-may-contain-device-or-process-details",
                ),
            )
            ZipOutputStream(FileOutputStream(temporary).buffered()).use { zip ->
                zip.putText("manifest.json", DiagnosticEventCodec.encode(manifest))
                zip.putText("summary.txt", humanSummary(manifest, recentLines.toList(), eventCount, work))
                zip.putEventLines("events.jsonl", eventFiles)
                CrashReporter.pendingReport()?.takeIf(String::isNotBlank)?.let { reportText ->
                    zip.putText("crash.txt", DiagnosticRedactor.redactText(reportText))
                }
                zip.putText("workmanager.txt", work)
                diagnosticsDir.listFiles()
                    .orEmpty()
                    .filter { it.isFile && it.name.startsWith("exit-") }
                    .sortedByDescending(File::lastModified)
                    .take(5)
                    .forEach { trace -> zip.putFile("exit-traces/${trace.name}", trace) }
            }
            if (report.exists()) report.delete()
            if (!temporary.renameTo(report)) {
                temporary.copyTo(report, overwrite = true)
                temporary.delete()
            }
            event("share", "report.created", mapOf("bytes" to report.length(), "events" to eventCount))
            return report
        } finally {
            runCatching { temporary.delete() }
            runCatching { eventSnapshotDirectory.deleteRecursively() }
        }
    }

    private fun recordThrowable(level: String, message: String, throwable: Throwable, blocking: Boolean) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val event = newEvent(
            level = level,
            category = inferredCategory(message),
            name = if (level == "FATAL") "exception.fatal" else "exception.caught",
            message = message,
            exception = DiagnosticException(
                type = throwable.javaClass.name,
                message = throwable.message,
                stackTrace = trace,
            ),
        )
        if (blocking) store?.appendBlocking(event) else store?.append(event)
    }

    private fun record(
        level: String,
        category: String,
        name: String,
        message: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        store?.append(newEvent(level, category, name, message, attributes))
    }

    private fun newEvent(
        level: String,
        category: String,
        name: String,
        message: String? = null,
        attributes: Map<String, String> = emptyMap(),
        exception: DiagnosticException? = null,
    ): DiagnosticEvent {
        val thread = Thread.currentThread()
        val nowElapsed = SystemClock.elapsedRealtime()
        return DiagnosticEvent(
            timestampUtc = timestampUtc(),
            elapsedRealtimeMs = nowElapsed,
            processUptimeMs = (nowElapsed - processStartedElapsedMs).coerceAtLeast(0),
            sessionId = sessionId,
            sequence = sequence.incrementAndGet(),
            process = processName,
            pid = Process.myPid(),
            thread = thread.name,
            threadId = thread.id,
            level = level,
            category = category.lowercase(Locale.US).take(40),
            name = name.lowercase(Locale.US).replace(' ', '_').take(80),
            message = message,
            attributes = attributes,
            exception = exception,
        )
    }

    private fun lifecycle(activity: Activity, state: String, extra: Map<String, Any?> = emptyMap()) {
        event(
            category = "lifecycle",
            name = "activity.$state",
            attributes = mapOf("activity" to activity.javaClass.simpleName) + extra,
        )
    }

    private fun systemSnapshot(context: Context): Map<String, String> {
        val app = context.applicationContext
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { runCatching { activityManager?.getMemoryInfo(it) } }
        val runtime = Runtime.getRuntime()
        val configuration = app.resources.configuration
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            display?.hdrCapabilities?.supportedHdrTypes?.joinToString("/").orEmpty()
        } else {
            "unavailable"
        }
        return mapOf(
            "orientation" to orientation(configuration),
            "uiMode" to uiMode(configuration),
            "fontScale" to configuration.fontScale.toString(),
            "screenDp" to "${configuration.screenWidthDp}x${configuration.screenHeightDp}",
            "smallestDp" to configuration.smallestScreenWidthDp.toString(),
            "memoryAvailableMb" to (memory.availMem / 1024 / 1024).toString(),
            "memoryLow" to memory.lowMemory.toString(),
            "memoryThresholdMb" to (memory.threshold / 1024 / 1024).toString(),
            // Requests still open well past the point of usefulness, with the phase each reached.
            // A caller that gives up before OkHttp does leaves no completion event, so without
            // this a report can show a screen failing to load and nothing about why.
            "httpStalled" to DiagnosticsHttpEventListener.InFlight.stalled().ifEmpty { "none" },
            "heapUsedMb" to ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024).toString(),
            "heapMaxMb" to (runtime.maxMemory() / 1024 / 1024).toString(),
            "storageAvailableMb" to (app.filesDir.usableSpace / 1024 / 1024).toString(),
            "storageTotalMb" to (app.filesDir.totalSpace / 1024 / 1024).toString(),
            "batteryPercent" to batteryPercent(battery).toString(),
            "batteryCharging" to batteryCharging(battery).toString(),
            "batteryTemperatureC" to ((battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f).toString(),
            "thermalStatus" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThermalLogApi29Helper.getThermalStatus(power)
            } else {
                "unavailable"
            },
            "display" to displaySummary(display),
            "hdrTypes" to hdrTypes,
        ) + ResolverDiagnostics.snapshotAttributes() + WebViewProcessController.snapshotAttributes()
    }

    @Suppress("DEPRECATION")
    private fun displaySummary(display: Display?): String {
        if (display == null) return "unknown"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            display.mode.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }
        } else {
            "${display.width}x${display.height}@${display.refreshRate}"
        }
    }

    private fun deviceProfileMap(context: Context): Map<String, String> {
        val activityManager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val fireOs = listOf("ro.build.version.fireos", "ro.build.version.fireos.sdk")
            .firstNotNullOfOrNull { systemProperty(it)?.takeIf(String::isNotBlank) }
        val amazon = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "fingerprint" to Build.FINGERPRINT,
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "securityPatch" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "unavailable",
            "fireOs" to (fireOs ?: if (amazon) "amazon-unknown" else "no"),
            "lowRam" to (activityManager?.isLowRamDevice?.toString() ?: "unknown"),
            "memoryClassMb" to (activityManager?.memoryClass?.toString() ?: "unknown"),
            "largeMemoryClassMb" to (activityManager?.largeMemoryClass?.toString() ?: "unknown"),
            "abis" to Build.SUPPORTED_ABIS.joinToString("/"),
        )
    }

    private fun networkSnapshot(context: Context): Map<String, String> = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return mapOf("network" to "unknown")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            return mapOf(
                "network" to (manager.activeNetworkInfo?.typeName?.lowercase(Locale.US) ?: "offline"),
            )
        }
        val network = manager.activeNetwork ?: return mapOf("network" to "offline")
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return mapOf("network" to "unknown")
        mapOf(
            "network" to networkTransport(capabilities),
            "networkValidated" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).toString(),
            "networkMetered" to (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).toString(),
            "networkCaptivePortal" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL).toString(),
            "networkVpn" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN).toString(),
            "networkDownKbps" to capabilities.linkDownstreamBandwidthKbps.toString(),
            "networkUpKbps" to capabilities.linkUpstreamBandwidthKbps.toString(),
        )
    }.getOrElse { mapOf("network" to "unknown") }

    private fun workManagerSummary(context: Context): String {
        if (processName.endsWith(":diagnostics")) return "Unavailable from the lightweight diagnostics process."
        return runCatching {
            val manager = WorkManager.getInstance(context.applicationContext)
            listOf("episode-export", "release-sync").joinToString("\n") { tag ->
                val infos = manager.getWorkInfosByTag(tag).get(2, TimeUnit.SECONDS)
                val states = infos.groupingBy { it.state.name }.eachCount()
                "$tag: total=${infos.size} states=$states"
            }
        }.getOrElse { "WorkManager snapshot unavailable: ${it.javaClass.simpleName}" }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun saveExitTrace(context: Context, index: Int, info: ApplicationExitInfo): String? = runCatching {
        val input = info.traceInputStream ?: return null
        val nativeTrace = info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val extension = if (nativeTrace) "pb" else "txt"
        val target = diagnosticsDirectory(context).resolve(
            "exit-${info.timestamp}-$index-${exitReasonName(info.reason).lowercase(Locale.US)}.$extension",
        )
        input.use { source ->
            val bytes = source.readBytesCapped(MAX_EXIT_TRACE_BYTES)
            if (nativeTrace) {
                target.writeBytes(bytes)
            } else {
                target.writeText(
                    DiagnosticRedactor.redactText(bytes.toString(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8,
                )
            }
        }
        cleanupExitTraces(context)
        target.name
    }.getOrNull()

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun logApplicationStartInfo(info: ApplicationStartInfo) {
        val timestamps = info.startupTimestamps
        val fork = timestamps[ApplicationStartInfo.START_TIMESTAMP_FORK]
        val firstFrame = timestamps[ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME]
        val attributes = mutableMapOf<String, Any?>(
            "reason" to startReasonName(info.reason),
            "startType" to startTypeName(info.startType),
            "startupState" to info.startupState,
            "process" to info.processName,
            "pid" to info.pid,
            "wasForceStopped" to info.wasForceStopped(),
            "forkToFirstFrameMs" to if (fork != null && firstFrame != null) (firstFrame - fork) / 1_000_000 else "unknown",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            attributes["startComponent"] = info.startComponent
        }
        event("startup", "application.start_info", attributes)
    }

    private fun humanSummary(
        manifest: DiagnosticManifest,
        recentLines: List<String>,
        eventCount: Int,
        work: String,
    ): String = buildString {
        appendLine("Anilili diagnostics")
        appendLine("Generated: ${manifest.generatedUtc}")
        appendLine("App: ${manifest.appVersion} (${manifest.versionCode}) ${manifest.buildType}")
        appendLine("Build: ${manifest.buildSha}")
        appendLine("Device: ${manifest.device["manufacturer"]} ${manifest.device["model"]}")
        appendLine("Android: ${manifest.device["android"]} (SDK ${manifest.device["sdk"]})")
        appendLine("Events: $eventCount")
        appendLine("Privacy: URL paths/query strings, credentials, tokens, cookies, search terms, titles, and slugs are redacted.")
        appendLine("Native traces: raw Android native-crash traces can contain low-level device or process details.")
        appendLine()
        appendLine("== system ==")
        manifest.device.toSortedMap().forEach { (key, value) -> appendLine("$key: $value") }
        appendLine()
        appendLine("== background work ==")
        appendLine(work)
        appendLine()
        appendLine("== recent events ==")
        recentLines.forEach { line ->
            runCatching { DiagnosticEventCodec.decode(line) }.getOrNull()?.let { event ->
                append(event.timestampUtc)
                append(" [${event.level}/${event.category}] ")
                append(event.name)
                event.message?.let { append(" — ").append(it) }
                if (event.attributes.isNotEmpty()) append(" ").append(event.attributes)
                event.exception?.let { append(" — ").append(it.type).append(": ").append(it.message.orEmpty()) }
                appendLine()
            }
        }
    }

    private fun maybeUpdateProcessState(message: String) {
        val important = listOf("Nav route=", "PlaybackService player state=", "PlaybackService route=", "Watch resolve")
            .firstOrNull(message::startsWith) ?: return
        updateProcessState(important.substringBefore(' ').lowercase(Locale.US), message)
    }

    private fun inferredCategory(message: String): String = message
        .substringBefore(' ')
        .substringBefore('.')
        .replace(categorySanitizer, "")
        .ifBlank { "app" }
        .lowercase(Locale.US)

    private fun inferredName(message: String): String = message
        .substringBefore(' ')
        .replace(eventNameSanitizer, "_")
        .ifBlank { "event" }
        .lowercase(Locale.US)

    private fun allThreadStacks(): String = Thread.getAllStackTraces()
        .entries
        .sortedBy { it.key.name }
        .take(24)
        .joinToString("\n") { (thread, frames) ->
            buildString {
                append('"').append(thread.name).append("\" state=").append(thread.state).append('\n')
                frames.take(120).forEach { append("    at ").append(it).append('\n') }
            }
        }

    private fun stackTrace(thread: Thread): String =
        thread.stackTrace.joinToString("\n") { "    at $it" }

    private fun viewAttributes(view: View): Map<String, Any?> = mapOf(
        "attached" to view.isAttachedToWindow,
        "shown" to view.isShown,
        "width" to view.width,
        "height" to view.height,
        "visibility" to view.visibility,
        "windowFocus" to view.hasWindowFocus(),
    )

    private fun batteryPercent(intent: Intent?): Int {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun batteryCharging(intent: Intent?): Boolean {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun networkTransport(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }

    private fun cleanupExitTraces(context: Context) {
        diagnosticsDirectory(context).listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("exit-") }
            .sortedByDescending(File::lastModified)
            .drop(5)
            .forEach(File::delete)
    }

    private fun cleanupCachedReports(directory: File) {
        val cutoff = System.currentTimeMillis() - CACHED_REPORT_MAX_AGE_MS
        directory.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile && file.name.startsWith(SHARE_FILE_PREFIX) && file.lastModified() < cutoff
            }
            .forEach { file -> runCatching { file.delete() } }
    }

    private fun systemProperty(key: String): String? = runCatching {
        @Suppress("PrivateApi")
        val properties = Class.forName("android.os.SystemProperties")
        properties.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()

    private fun diagnosticsDirectory(context: Context): File =
        File(context.applicationContext.filesDir, LOG_DIR).apply { mkdirs() }

    private fun timestampUtc(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun fileTimestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun orientation(configuration: Configuration): String = when (configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        else -> "undefined"
    }

    private fun uiMode(configuration: Configuration): String = when (
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    ) {
        Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
        Configuration.UI_MODE_TYPE_CAR -> "car"
        Configuration.UI_MODE_TYPE_WATCH -> "watch"
        Configuration.UI_MODE_TYPE_NORMAL -> "normal"
        else -> "unknown"
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCES"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "UNKNOWN($reason)"
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun startReasonName(reason: Int): String = when (reason) {
        ApplicationStartInfo.START_REASON_ALARM -> "ALARM"
        ApplicationStartInfo.START_REASON_BACKUP -> "BACKUP"
        ApplicationStartInfo.START_REASON_BOOT_COMPLETE -> "BOOT_COMPLETE"
        ApplicationStartInfo.START_REASON_BROADCAST -> "BROADCAST"
        ApplicationStartInfo.START_REASON_CONTENT_PROVIDER -> "CONTENT_PROVIDER"
        ApplicationStartInfo.START_REASON_JOB -> "JOB"
        ApplicationStartInfo.START_REASON_LAUNCHER -> "LAUNCHER"
        ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS -> "LAUNCHER_RECENTS"
        ApplicationStartInfo.START_REASON_PUSH -> "PUSH"
        ApplicationStartInfo.START_REASON_SERVICE -> "SERVICE"
        ApplicationStartInfo.START_REASON_START_ACTIVITY -> "START_ACTIVITY"
        else -> "OTHER($reason)"
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun startTypeName(type: Int): String = when (type) {
        ApplicationStartInfo.START_TYPE_COLD -> "COLD"
        ApplicationStartInfo.START_TYPE_WARM -> "WARM"
        ApplicationStartInfo.START_TYPE_HOT -> "HOT"
        else -> "UNSET"
    }

    private fun currentProcessName(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
        }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun java.io.InputStream.readBytesCapped(maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putEventLines(name: String, eventFiles: List<File>) {
        putNextEntry(ZipEntry(name))
        DiagnosticFileStore.forEachOrderedLine(eventFiles) { line ->
            write(line.toByteArray(StandardCharsets.UTF_8))
            write('\n'.code)
        }
        closeEntry()
    }

    private fun ZipOutputStream.putFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private object ThermalLogApi29Helper {
    fun getThermalStatus(power: PowerManager?): String {
        return power?.currentThermalStatus?.toString() ?: "unknown"
    }
}
