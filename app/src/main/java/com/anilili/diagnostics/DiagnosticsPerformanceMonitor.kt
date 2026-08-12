package com.anilili.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Low-duty-cycle process telemetry for intermittent TV lag without continuous profiling. */
object DiagnosticsPerformanceMonitor {
    private const val SAMPLE_INTERVAL_MS = 30_000L
    private val installed = AtomicBoolean(false)
    private val foregroundActivities = AtomicInteger(0)
    @Volatile private var handler: Handler? = null
    @Volatile private var previousElapsedMs = 0L
    @Volatile private var previousCpuMs = 0L

    private val sample = object : Runnable {
        override fun run() {
            val currentHandler = handler ?: return
            if (foregroundActivities.get() <= 0) return
            val context = appContext ?: return
            runCatching { recordSample(context) }
                .onFailure { DiagnosticsLog.throwable("performance sample failed", it) }
            currentHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    @Volatile private var appContext: Context? = null

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        appContext = application.applicationContext
        val thread = HandlerThread("anilili-performance", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        handler = Handler(thread.looper)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (foregroundActivities.incrementAndGet() == 1) {
                    previousElapsedMs = SystemClock.elapsedRealtime()
                    previousCpuMs = Process.getElapsedCpuTime()
                    handler?.removeCallbacks(sample)
                    handler?.postDelayed(sample, SAMPLE_INTERVAL_MS)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                val remaining = foregroundActivities.decrementAndGet().coerceAtLeast(0)
                foregroundActivities.set(remaining)
                if (remaining == 0) handler?.removeCallbacks(sample)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun recordSample(context: Context) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val wallDelta = (nowElapsed - previousElapsedMs).coerceAtLeast(1)
        val cpuDelta = (nowCpu - previousCpuMs).coerceAtLeast(0)
        previousElapsedMs = nowElapsed
        previousCpuMs = nowCpu

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { runCatching { manager?.getMemoryInfo(it) } }
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        DiagnosticsLog.event(
            category = "performance",
            name = "process.sample",
            attributes = mapOf(
                "pssKb" to Debug.getPss(),
                "nativeHeapKb" to (Debug.getNativeHeapAllocatedSize() / 1024),
                "javaHeapUsedKb" to (javaUsed / 1024),
                "javaHeapMaxKb" to (runtime.maxMemory() / 1024),
                "systemAvailableKb" to (memory.availMem / 1024),
                "systemLowMemory" to memory.lowMemory,
                "memoryClassMb" to (manager?.memoryClass ?: -1),
                "lowRamDevice" to (manager?.isLowRamDevice ?: false),
                "processCpuPercent" to (cpuDelta * 100.0 / wallDelta),
                "activeThreadsApprox" to Thread.activeCount(),
                "thermalStatus" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ThermalApi29Helper.getThermalStatus(power)
                } else {
                    -1
                },
            ),
        )
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private object ThermalApi29Helper {
    fun getThermalStatus(power: PowerManager?): Int {
        return power?.currentThermalStatus ?: -1
    }
}
