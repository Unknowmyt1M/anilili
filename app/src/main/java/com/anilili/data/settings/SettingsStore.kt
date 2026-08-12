package com.anilili.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.anilili.data.AppGraph
import com.anilili.diagnostics.CrashReporter
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class MenuLanguage(val storedValue: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    SPANISH("es");

    fun usesSpanish(systemLanguage: String = Locale.getDefault().language): Boolean =
        this == SPANISH || (this == SYSTEM && systemLanguage.equals("es", ignoreCase = true))

    companion object {
        fun fromStored(value: String?): MenuLanguage = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/** How an episode list is drawn: rich rows with stills, or compact number chips. */
enum class EpisodeLayout(val storedValue: String) {
    LIST("list"),
    GRID("grid");

    fun toggled(): EpisodeLayout = if (this == LIST) GRID else LIST

    companion object {
        fun fromStored(value: String?): EpisodeLayout =
            entries.firstOrNull { it.storedValue == value } ?: LIST
    }
}

enum class DefaultQuality(
    val storedValue: String,
    val label: String,
    val maxHeight: Int?,
) {
    AUTO("auto", "Auto", null),
    HIGHEST("highest", "Highest", null),
    P1080("1080", "1080p", 1080),
    P720("720", "720p", 720),
    P480("480", "480p", 480),
    P360("360", "360p · Data Saver", 360);

    /** Best matching height from [heights], or null to leave adaptive selection alone. */
    fun pickHeight(heights: List<Int>): Int? = when (this) {
        AUTO -> null
        HIGHEST -> heights.maxOrNull()
        // Closest height without going over. If every real rendition is above the requested cap,
        // use the lowest one so playback still starts instead of rejecting the source.
        else -> {
            val target = requireNotNull(maxHeight)
            heights.filter { it <= target }.maxOrNull() ?: heights.minOrNull()
        }
    }

    companion object {
        fun fromStored(value: String?): DefaultQuality =
            entries.firstOrNull { it.storedValue == value } ?: HIGHEST
    }
}

/** Maximum video rendition kept in an offline download. */
enum class DownloadQuality(
    val storedValue: String,
    val label: String,
    val maxHeight: Int?,
) {
    BEST("best", "Best available", null),
    P1080("1080", "1080p", 1080),
    P720("720", "720p", 720),
    P480("480", "480p", 480),
    P360("360", "360p", 360);

    companion object {
        fun fromStored(value: String?): DownloadQuality =
            entries.firstOrNull { it.storedValue == value } ?: BEST
    }
}

/**
 * Where a downloaded episode ends up.
 *
 * Downloads-folder copies are always built from a library download that is then rewrapped into a
 * single MP4, so `DEVICE_ONLY` does not skip the library — it drops the cached copy afterwards.
 */
enum class DownloadDestination(val storedValue: String, val label: String) {
    APP_ONLY("app", "Anilili library"),
    DEVICE_ONLY("device", "Device Downloads"),
    BOTH("both", "Both");

    val includesApp: Boolean get() = this != DEVICE_ONLY
    val includesDevice: Boolean get() = this != APP_ONLY

    companion object {
        /**
         * Downloads become an MP4 in the device's Downloads folder unless the viewer says
         * otherwise: an episode that only exists as Media3 cache segments is useful to this app
         * and nothing else, and the offline library keeps working either way because it plays the
         * exported file directly.
         */
        fun fromStored(value: String?): DownloadDestination =
            entries.firstOrNull { it.storedValue == value } ?: DEVICE_ONLY
    }
}

/**
 * The quality to use before the viewer has chosen one. TV sticks start adaptive: pinning the
 * highest rendition turns ABR off, so a hardware decoder that gets preempted or falls behind has
 * no lower rung to drop to and the picture simply dies. Everything else opens at its sharpest.
 */
private fun deviceDefaultQuality(): DefaultQuality =
    if (AppGraph.isTv) DefaultQuality.AUTO else DefaultQuality.HIGHEST

/** No global server has been chosen yet; the launch route supplies the initial server. */
const val DEFAULT_PREFERRED_PROVIDER = "auto"

/** Shared safety limit for manually or persistently shifted captions. */
const val MAX_CAPTION_DELAY_MS = 30_000L

/** How many servers the user can rank. Beyond three, the catalog's own order takes over anyway. */
const val MAX_SERVER_PRIORITY = 3

/** The player's manual skip jump: a standard TV anime opening, adjustable to any show's cut. */
const val SKIP_BUTTON_DEFAULT_SECONDS = 85
const val SKIP_BUTTON_MIN_SECONDS = 5
const val SKIP_BUTTON_MAX_SECONDS = 180

/** Transactional DataStore preferences shared by playback and the Library settings UI. */
object SettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: DataStore<Preferences>

    private val _autoplay = MutableStateFlow(true)
    val autoplay = _autoplay.asStateFlow()

    private val _autoSyncAniList = MutableStateFlow(true)
    val autoSyncAniList = _autoSyncAniList.asStateFlow()

    private val _preferDub = MutableStateFlow(false)
    val preferDub = _preferDub.asStateFlow()

    private val _releaseNotifications = MutableStateFlow(true)
    val releaseNotifications = _releaseNotifications.asStateFlow()

    private val _syncSavedToAniList = MutableStateFlow(true)
    val syncSavedToAniList = _syncSavedToAniList.asStateFlow()

    private val _autoSkipIntroOutro = MutableStateFlow(false)
    val autoSkipIntroOutro = _autoSkipIntroOutro.asStateFlow()

    /**
     * A one-press jump forward shown on the player whenever no timed "Skip Intro" window applies.
     *
     * Intro timings come from providers or AniSkip and are missing or wrong often enough that
     * viewers were left scrubbing by hand. [SKIP_BUTTON_DEFAULT_SECONDS] is the length of a
     * standard TV anime opening.
     */
    private val _showSkipButton = MutableStateFlow(true)
    val showSkipButton = _showSkipButton.asStateFlow()

    private val _skipButtonSeconds = MutableStateFlow(SKIP_BUTTON_DEFAULT_SECONDS)
    val skipButtonSeconds = _skipButtonSeconds.asStateFlow()

    private val _hideAdultContent = MutableStateFlow(true)
    val hideAdultContent = _hideAdultContent.asStateFlow()

    private val _blurEpisodeImages = MutableStateFlow(false)
    val blurEpisodeImages = _blurEpisodeImages.asStateFlow()

    /**
     * Brightness, volume and drag-to-seek gestures over the picture. Off means the player only
     * responds to its on-screen controls — the edge drags are easy to trigger by accident when
     * you are only trying to reveal the bar. TV never had them.
     */
    private val _playerGestures = MutableStateFlow(true)
    val playerGestures = _playerGestures.asStateFlow()

    private val _subtitlesWithDub = MutableStateFlow(false)
    val subtitlesWithDub = _subtitlesWithDub.asStateFlow()

    private val _updateCheckOnLaunch = MutableStateFlow(true)
    val updateCheckOnLaunch = _updateCheckOnLaunch.asStateFlow()

    // Kept as one compound value rather than a flow per field: both players and the editor read
    // the whole style at once, and a partial style is never meaningful.
    private val _captionStyle = MutableStateFlow(CaptionStyle())
    val captionStyle = _captionStyle.asStateFlow()

    /** Opted-in subtitle delays keyed by AniList anime ID (which also scopes separate seasons). */
    private val _persistentCaptionDelays = MutableStateFlow<Map<Int, Long>>(emptyMap())

    private val _menuLanguage = MutableStateFlow(MenuLanguage.SYSTEM)
    val menuLanguage = _menuLanguage.asStateFlow()

    private val _defaultQuality = MutableStateFlow(deviceDefaultQuality())
    val defaultQuality = _defaultQuality.asStateFlow()

    private val _downloadQuality = MutableStateFlow(DownloadQuality.BEST)
    val downloadQuality = _downloadQuality.asStateFlow()

    private val _downloadDestination = MutableStateFlow(DownloadDestination.DEVICE_ONLY)
    val downloadDestination = _downloadDestination.asStateFlow()

    // Set once and expected to stick: the viewer who wants dense number chips for a long-runner
    // wants them on the next screen too, not just until they navigate away.
    private val _episodeLayout = MutableStateFlow(EpisodeLayout.LIST)
    val episodeLayout = _episodeLayout.asStateFlow()

    /**
     * The Miruro mirror that last loaded successfully on this network. ISPs block the mirrors
     * piecemeal, and rediscovering that from scratch each launch costs the whole failover walk.
     */
    private val _lastWorkingPipeOrigin = MutableStateFlow("")
    val lastWorkingPipeOrigin = _lastWorkingPipeOrigin.asStateFlow()

    /**
     * The user's servers in the order they should be tried, first choice first, at most
     * [MAX_SERVER_PRIORITY] of them. Empty means "no pinned choice" — the catalog's own order.
     */
    private val _serverPriority = MutableStateFlow<List<String>>(emptyList())
    val serverPriority = _serverPriority.asStateFlow()

    /**
     * Head of [serverPriority], or "auto" when nothing is pinned. Kept as its own flow because the
     * player's server sheet, the ★ badge, and the watch route all read a single preferred name.
     */
    private val _preferredProvider = MutableStateFlow(DEFAULT_PREFERRED_PROVIDER)
    val preferredProvider = _preferredProvider.asStateFlow()

    private val _youtubeApiKey = MutableStateFlow("")
    val youtubeApiKey = _youtubeApiKey.asStateFlow()

    private val _shortsApiUrl = MutableStateFlow(defaultShortsApiUrl())
    val shortsApiUrl = _shortsApiUrl.asStateFlow()

    private val loaded = MutableStateFlow(false)

    fun init(context: Context) {
        val app = context.applicationContext
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { app.preferencesDataStoreFile("anilili_settings") },
        )
        scope.launch {
            runCatching { migrateLegacyPreferences(app) }
                .onFailure { CrashReporter.logNonFatal("Settings migration failed", it) }
            store.data
                .catch { error ->
                    // A settings read must never take the process down; fall back to defaults.
                    if (error !is IOException) CrashReporter.logNonFatal("Settings read failed", error)
                    emit(emptyPreferences())
                }
                .collect(::applyPreferences)
        }
    }

    fun setAutoplay(value: Boolean) = save(AUTOPLAY, value, _autoplay)
    fun setAutoSyncAniList(value: Boolean) = save(AUTO_SYNC, value, _autoSyncAniList)
    fun setPreferDub(value: Boolean) = save(PREFER_DUB, value, _preferDub)
    fun setReleaseNotifications(value: Boolean) = save(RELEASE_NOTIFICATIONS, value, _releaseNotifications)
    fun setSyncSavedToAniList(value: Boolean) = save(SYNC_SAVED_TO_ANILIST, value, _syncSavedToAniList)
    fun setAutoSkipIntroOutro(value: Boolean) = save(AUTO_SKIP_INTRO_OUTRO, value, _autoSkipIntroOutro)
    fun setShowSkipButton(value: Boolean) = save(SHOW_SKIP_BUTTON, value, _showSkipButton)

    fun setSkipButtonSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(SKIP_BUTTON_MIN_SECONDS, SKIP_BUTTON_MAX_SECONDS)
        _skipButtonSeconds.value = clamped
        scope.launch { store.edit { it[SKIP_BUTTON_SECONDS] = clamped } }
    }
    fun setHideAdultContent(value: Boolean) = save(HIDE_ADULT_CONTENT, value, _hideAdultContent)
    fun setBlurEpisodeImages(value: Boolean) = save(BLUR_EPISODE_IMAGES, value, _blurEpisodeImages)
    fun setSubtitlesWithDub(value: Boolean) = save(SUBTITLES_WITH_DUB, value, _subtitlesWithDub)
    fun setUpdateCheckOnLaunch(value: Boolean) = save(UPDATE_CHECK_ON_LAUNCH, value, _updateCheckOnLaunch)

    fun setCaptionBackgroundOpacity(percent: Int) =
        editCaptionStyle { it.copy(backgroundOpacityPercent = percent.coerceIn(0, 100)) }
    fun setCaptionBackgroundColor(value: CaptionBackgroundColor) =
        editCaptionStyle { it.copy(backgroundColor = value) }
    fun setCaptionTextScale(percent: Int) =
        editCaptionStyle { it.copy(textScalePercent = percent.coerceIn(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.MAX_TEXT_SCALE_PERCENT)) }
    fun setCaptionBold(value: Boolean) = editCaptionStyle { it.copy(boldText = value) }
    fun setCaptionBottomMargin(percent: Int) =
        editCaptionStyle { it.copy(bottomMarginPercent = percent.coerceIn(CaptionStyle.MIN_BOTTOM_MARGIN_PERCENT, CaptionStyle.MAX_BOTTOM_MARGIN_PERCENT)) }
    fun setCaptionTextColor(value: CaptionTextColor) = editCaptionStyle { it.copy(textColor = value) }
    fun setCaptionEdgeStyle(value: CaptionEdgeStyle) = editCaptionStyle { it.copy(edgeStyle = value) }
    fun resetCaptionStyle() = editCaptionStyle { CaptionStyle() }

    fun persistentCaptionDelay(animeId: Int): Long? = _persistentCaptionDelays.value[animeId]

    /** A missing entry means carryover is off for this anime/season; zero is a valid saved delay. */
    fun setPersistentCaptionDelay(animeId: Int, delayMs: Long?) {
        if (animeId <= 0) return
        val next = _persistentCaptionDelays.value.toMutableMap().apply {
            if (delayMs == null) remove(animeId)
            else put(animeId, delayMs.coerceIn(-MAX_CAPTION_DELAY_MS, MAX_CAPTION_DELAY_MS))
        }.toMap()
        _persistentCaptionDelays.value = next
        scope.launch { store.edit { it[PERSISTENT_CAPTION_DELAYS] = encodePersistentCaptionDelays(next) } }
    }

    fun setDefaultQuality(value: DefaultQuality) {
        _defaultQuality.value = value
        scope.launch { store.edit { it[DEFAULT_QUALITY] = value.storedValue } }
    }

    fun setDownloadQuality(value: DownloadQuality) {
        _downloadQuality.value = value
        scope.launch { store.edit { it[DOWNLOAD_QUALITY] = value.storedValue } }
    }

    fun setDownloadDestination(value: DownloadDestination) {
        _downloadDestination.value = value
        scope.launch { store.edit { it[DOWNLOAD_DESTINATION] = value.storedValue } }
    }

    fun setEpisodeLayout(value: EpisodeLayout) {
        _episodeLayout.value = value
        scope.launch { store.edit { it[EPISODE_LAYOUT] = value.storedValue } }
    }

    fun setMenuLanguage(value: MenuLanguage) {
        _menuLanguage.value = value
        scope.launch { store.edit { it[MENU_LANGUAGE] = value.storedValue } }
    }

    fun setYoutubeApiKey(value: String) {
        _youtubeApiKey.value = value.trim()
        scope.launch { store.edit { it[YOUTUBE_API_KEY] = value.trim() } }
    }

    fun setShortsApiUrl(value: String) {
        val trimmed = value.trim()
        val finalUrl = trimmed.ifBlank { defaultShortsApiUrl() }
        _shortsApiUrl.value = finalUrl
        scope.launch { store.edit { it[SHORTS_API_URL] = finalUrl } }
    }
    /** Normalises, de-duplicates and caps a priority list, then mirrors the head into [preferredProvider]. */
    private fun applyServerPriority(value: List<String>) {
        val clean = value.map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != DEFAULT_PREFERRED_PROVIDER }
            .distinct()
            .take(MAX_SERVER_PRIORITY)
        _serverPriority.value = clean
        _preferredProvider.value = clean.firstOrNull() ?: DEFAULT_PREFERRED_PROVIDER
    }

    fun setPlayerGestures(value: Boolean) {
        _playerGestures.value = value
        scope.launch { store.edit { it[PLAYER_GESTURES] = value } }
    }

    fun setLastWorkingPipeOrigin(value: String) {
        if (_lastWorkingPipeOrigin.value == value) return
        _lastWorkingPipeOrigin.value = value
        scope.launch { store.edit { it[LAST_PIPE_ORIGIN] = value } }
    }

    fun setServerPriority(value: List<String>) {
        applyServerPriority(value)
        val stored = _serverPriority.value.joinToString(",")
        scope.launch { store.edit { it[SERVER_PRIORITY] = stored } }
    }

    /**
     * Promotes [value] to first choice, keeping the rest of the order behind it. Picking "auto"
     * clears the whole list — with no first choice there is nothing for the fallbacks to fall back
     * from, and leaving them would silently promote one the user never chose.
     */
    fun setPreferredProvider(value: String) {
        val name = value.trim().lowercase().ifBlank { DEFAULT_PREFERRED_PROVIDER }
        if (name == DEFAULT_PREFERRED_PROVIDER) {
            setServerPriority(emptyList())
        } else {
            setServerPriority(listOf(name) + _serverPriority.value.filterNot { it == name })
        }
    }

    /** Guarantees cold-start consumers see the persisted preference instead of the in-memory default. */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    private fun save(key: Preferences.Key<Boolean>, value: Boolean, state: MutableStateFlow<Boolean>) {
        state.value = value
        scope.launch { store.edit { it[key] = value } }
    }

    private fun editCaptionStyle(transform: (CaptionStyle) -> CaptionStyle) {
        val next = transform(_captionStyle.value)
        _captionStyle.value = next
        scope.launch {
            store.edit { prefs ->
                prefs[CAPTION_BACKGROUND_OPACITY] = next.backgroundOpacityPercent
                prefs[CAPTION_BACKGROUND_COLOR] = next.backgroundColor.storedValue
                prefs[CAPTION_TEXT_SCALE] = next.textScalePercent
                prefs[CAPTION_BOLD_TEXT] = next.boldText
                prefs[CAPTION_BOTTOM_MARGIN] = next.bottomMarginPercent
                prefs[CAPTION_TEXT_COLOR] = next.textColor.storedValue
                prefs[CAPTION_EDGE_STYLE] = next.edgeStyle.storedValue
            }
        }
    }

    internal fun readCaptionStyle(prefs: Preferences): CaptionStyle = CaptionStyle(
        backgroundOpacityPercent = prefs[CAPTION_BACKGROUND_OPACITY]?.coerceIn(0, 100)
            ?: CaptionStyle.DEFAULT_BACKGROUND_OPACITY_PERCENT,
        backgroundColor = CaptionBackgroundColor.fromStored(prefs[CAPTION_BACKGROUND_COLOR]),
        textScalePercent = prefs[CAPTION_TEXT_SCALE]?.coerceIn(CaptionStyle.MIN_TEXT_SCALE_PERCENT, CaptionStyle.MAX_TEXT_SCALE_PERCENT)
            ?: CaptionStyle.DEFAULT_TEXT_SCALE_PERCENT,
        boldText = prefs[CAPTION_BOLD_TEXT] ?: CaptionStyle.DEFAULT_BOLD_TEXT,
        bottomMarginPercent = prefs[CAPTION_BOTTOM_MARGIN]?.coerceIn(CaptionStyle.MIN_BOTTOM_MARGIN_PERCENT, CaptionStyle.MAX_BOTTOM_MARGIN_PERCENT)
            ?: CaptionStyle.DEFAULT_BOTTOM_MARGIN_PERCENT,
        textColor = CaptionTextColor.fromStored(prefs[CAPTION_TEXT_COLOR]),
        edgeStyle = CaptionEdgeStyle.fromStored(prefs[CAPTION_EDGE_STYLE]),
    )

    private fun applyPreferences(prefs: Preferences) {
        _autoplay.value = prefs[AUTOPLAY] ?: true
        _autoSyncAniList.value = prefs[AUTO_SYNC] ?: true
        _preferDub.value = prefs[PREFER_DUB] ?: false
        _releaseNotifications.value = prefs[RELEASE_NOTIFICATIONS] ?: true
        _syncSavedToAniList.value = prefs[SYNC_SAVED_TO_ANILIST] ?: true
        _autoSkipIntroOutro.value = prefs[AUTO_SKIP_INTRO_OUTRO] ?: false
        _showSkipButton.value = prefs[SHOW_SKIP_BUTTON] ?: true
        _skipButtonSeconds.value = (prefs[SKIP_BUTTON_SECONDS] ?: SKIP_BUTTON_DEFAULT_SECONDS)
            .coerceIn(SKIP_BUTTON_MIN_SECONDS, SKIP_BUTTON_MAX_SECONDS)
        _hideAdultContent.value = prefs[HIDE_ADULT_CONTENT] ?: true
        _blurEpisodeImages.value = prefs[BLUR_EPISODE_IMAGES] ?: false
        _subtitlesWithDub.value = prefs[SUBTITLES_WITH_DUB] ?: false
        _updateCheckOnLaunch.value = prefs[UPDATE_CHECK_ON_LAUNCH] ?: true
        _captionStyle.value = readCaptionStyle(prefs)
        _persistentCaptionDelays.value = decodePersistentCaptionDelays(prefs[PERSISTENT_CAPTION_DELAYS])
        _menuLanguage.value = MenuLanguage.fromStored(prefs[MENU_LANGUAGE])
        _defaultQuality.value = prefs[DEFAULT_QUALITY]?.let(DefaultQuality::fromStored)
            ?: deviceDefaultQuality()
        _downloadQuality.value = DownloadQuality.fromStored(prefs[DOWNLOAD_QUALITY])
        _downloadDestination.value = DownloadDestination.fromStored(prefs[DOWNLOAD_DESTINATION])
        _episodeLayout.value = EpisodeLayout.fromStored(prefs[EPISODE_LAYOUT])
        // Installs that predate the ordered list still carry a single preferred server; seed the
        // list from it so an existing choice survives the upgrade rather than resetting to auto.
        applyServerPriority(
            prefs[SERVER_PRIORITY]?.takeIf(String::isNotBlank)?.split(",")
                ?: listOfNotNull(prefs[PREFERRED_PROVIDER]?.takeIf(String::isNotBlank)),
        )
        _playerGestures.value = prefs[PLAYER_GESTURES] ?: true
        _lastWorkingPipeOrigin.value = prefs[LAST_PIPE_ORIGIN].orEmpty()
        _youtubeApiKey.value = prefs[YOUTUBE_API_KEY].orEmpty()
        _shortsApiUrl.value = prefs[SHORTS_API_URL]?.takeIf { it.isNotBlank() } ?: defaultShortsApiUrl()
        loaded.value = true
    }

    private suspend fun migrateLegacyPreferences(context: Context) {
        val current = store.data.first()
        if (current[MIGRATED] == true) return
        val legacy = context.getSharedPreferences("anilili_settings", Context.MODE_PRIVATE)
        store.edit { prefs ->
            prefs[AUTOPLAY] = legacy.getBoolean("autoplay", true)
            prefs[AUTO_SYNC] = legacy.getBoolean("auto_sync_anilist", true)
            prefs[PREFER_DUB] = legacy.getBoolean("prefer_dub", false)
            prefs[RELEASE_NOTIFICATIONS] = true
            prefs[SYNC_SAVED_TO_ANILIST] = true
            prefs[AUTO_SKIP_INTRO_OUTRO] = false
            prefs[MIGRATED] = true
        }
        legacy.edit().clear().apply()
    }

    private val AUTOPLAY = booleanPreferencesKey("autoplay")
    private val AUTO_SYNC = booleanPreferencesKey("auto_sync_anilist")
    private val PREFER_DUB = booleanPreferencesKey("prefer_dub")
    private val RELEASE_NOTIFICATIONS = booleanPreferencesKey("release_notifications")
    private val SYNC_SAVED_TO_ANILIST = booleanPreferencesKey("sync_saved_to_anilist")
    private val AUTO_SKIP_INTRO_OUTRO = booleanPreferencesKey("auto_skip_intro_outro")
    private val SHOW_SKIP_BUTTON = booleanPreferencesKey("show_skip_button")
    private val SKIP_BUTTON_SECONDS = intPreferencesKey("skip_button_seconds")
    private val HIDE_ADULT_CONTENT = booleanPreferencesKey("hide_adult_content")
    private val BLUR_EPISODE_IMAGES = booleanPreferencesKey("blur_episode_images")
    private val SUBTITLES_WITH_DUB = booleanPreferencesKey("subtitles_with_dub")
    private val UPDATE_CHECK_ON_LAUNCH = booleanPreferencesKey("update_check_on_launch")
    private val CAPTION_BACKGROUND_OPACITY = intPreferencesKey("caption_background_opacity")
    private val CAPTION_BACKGROUND_COLOR = stringPreferencesKey("caption_background_color")
    private val CAPTION_TEXT_SCALE = intPreferencesKey("caption_text_scale")
    private val CAPTION_BOLD_TEXT = booleanPreferencesKey("caption_bold_text")
    private val CAPTION_BOTTOM_MARGIN = intPreferencesKey("caption_bottom_margin")
    private val CAPTION_TEXT_COLOR = stringPreferencesKey("caption_text_color")
    private val CAPTION_EDGE_STYLE = stringPreferencesKey("caption_edge_style")
    private val PERSISTENT_CAPTION_DELAYS = stringPreferencesKey("persistent_caption_delays")
    private val MENU_LANGUAGE = stringPreferencesKey("menu_language")
    private val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
    private val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
    private val DOWNLOAD_DESTINATION = stringPreferencesKey("download_destination")
    private val EPISODE_LAYOUT = stringPreferencesKey("episode_layout")
    private val PREFERRED_PROVIDER = stringPreferencesKey("preferred_provider")
    private val SERVER_PRIORITY = stringPreferencesKey("server_priority")
    private val LAST_PIPE_ORIGIN = stringPreferencesKey("last_pipe_origin")
    private val PLAYER_GESTURES = booleanPreferencesKey("player_gestures")
    private val YOUTUBE_API_KEY = stringPreferencesKey("youtube_api_key")
    private val SHORTS_API_URL = stringPreferencesKey("shorts_api_url")
    private val MIGRATED = booleanPreferencesKey("migrated_from_shared_preferences")
}

private fun defaultShortsApiUrl(): String {
    val fingerprint = android.os.Build.FINGERPRINT?.lowercase() ?: ""
    val model = android.os.Build.MODEL?.lowercase() ?: ""
    val hardware = android.os.Build.HARDWARE?.lowercase() ?: ""
    return if (fingerprint.contains("generic") || fingerprint.contains("emulator") || model.contains("emulator") || hardware.contains("goldfish") || hardware.contains("ranchu")) {
        "http://10.0.2.2:8000"
    } else {
        "http://127.0.0.1:8000"
    }
}

internal fun encodePersistentCaptionDelays(delays: Map<Int, Long>): String = delays
    .asSequence()
    .filter { (animeId, _) -> animeId > 0 }
    .sortedBy { (animeId, _) -> animeId }
    .joinToString(",") { (animeId, delayMs) ->
        "$animeId:${delayMs.coerceIn(-MAX_CAPTION_DELAY_MS, MAX_CAPTION_DELAY_MS)}"
    }

internal fun decodePersistentCaptionDelays(stored: String?): Map<Int, Long> = stored
    .orEmpty()
    .split(',')
    .mapNotNull { entry ->
        val separator = entry.indexOf(':')
        if (separator <= 0 || separator == entry.lastIndex) return@mapNotNull null
        val animeId = entry.substring(0, separator).toIntOrNull()?.takeIf { it > 0 }
            ?: return@mapNotNull null
        val delayMs = entry.substring(separator + 1).toLongOrNull()
            ?.coerceIn(-MAX_CAPTION_DELAY_MS, MAX_CAPTION_DELAY_MS)
            ?: return@mapNotNull null
        animeId to delayMs
    }
    .toMap()
