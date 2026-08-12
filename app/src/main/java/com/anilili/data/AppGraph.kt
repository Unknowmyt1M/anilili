package com.anilili.data

import android.content.Context
import com.anilili.data.cache.AppCache
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AniSkipClient
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.remote.JikanClient
import com.anilili.data.remote.KonohaClient
import com.anilili.data.remote.MalClient
import com.anilili.data.remote.PipeClient
import com.anilili.data.remote.ShortsApiClient
import com.anilili.data.remote.ShortsRepository
import com.anilili.diagnostics.DiagnosticsHttpEventListener
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Cache
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tiny manual DI container. Built once from [MiruroApp.onCreate] and read by ViewModels.
 * Avoids pulling in Hilt for a project this size.
 */
object AppGraph {
    lateinit var repository: MiruroRepository
        private set
    lateinit var httpClient: OkHttpClient
        private set
    lateinit var json: Json
        private set
    lateinit var shortsApiClient: ShortsApiClient
        private set
    lateinit var shortsRepository: ShortsRepository
        private set

    /** TV boxes are memory/CPU-starved; non-UI layers use this to throttle background work. */
    var isTv: Boolean = false
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return

        isTv = (context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        httpClient = OkHttpClient.Builder()
            .eventListenerFactory(DiagnosticsHttpEventListener.Factory)
            .cache(Cache(File(context.applicationContext.cacheDir, "http"), 50L * 1024 * 1024))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        shortsApiClient = ShortsApiClient(httpClient, json)
        shortsRepository = ShortsRepository(shortsApiClient)

        val aniList = AniListClient(httpClient, json)
        val cache = AppCache(context, json)
        repository = MiruroRepository(
            aniList = aniList,
            pipe = PipeClient(json),
            anivexa = AnivexaClient(context, httpClient, json, aniList, cache),
            jikan = JikanClient(httpClient, json),
            aniSkip = AniSkipClient(httpClient, json),
            mal = MalClient(httpClient, json),
            konoha = KonohaClient(context, httpClient, json, cache),
            cache = cache,
        )
    }
}
