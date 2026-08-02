package com.anilili.playback

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.MainActivity
import com.anilili.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide playback state used by the activity to decide when PiP is appropriate. */
object PlaybackStatus {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    internal fun update(playing: Boolean) {
        _isPlaying.value = playing
    }
}

internal data class PlaybackBufferPolicy(
    val minimumMs: Int,
    val maximumMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
)

internal fun playbackBufferPolicy(
    isTv: Boolean,
    isLowRamDevice: Boolean,
    memoryClassMb: Int,
): PlaybackBufferPolicy? =
    if (isTv || isLowRamDevice || memoryClassMb <= 256) {
        PlaybackBufferPolicy(
            minimumMs = 10_000,
            maximumMs = 30_000,
            playbackMs = 1_500,
            rebufferMs = 3_000,
        )
    } else {
        null
    }

/**
 * Owns the app playback player so playback survives activity backgrounding and exposes Android
 * notification, lock-screen, headset, Bluetooth, and Cast controls through a MediaSession.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var castRoute: CastRoute? = null
    private lateinit var session: MediaSession
    private lateinit var httpFactory: HttpDataSource.Factory

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLog.event("PlaybackService.onCreate")
        // Native Media3 playback must not initialize Chromium just to obtain an HTTP header.
        val playerUserAgent = NATIVE_PLAYBACK_USER_AGENT
        activeUserAgent = playerUserAgent
        val defaultHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(playerUserAgent)
            .setAllowCrossProtocolRedirects(true)
        val cronetEngine = CronetUtil.buildCronetEngine(this, null, true)
        httpFactory = if (cronetEngine != null) {
            DiagnosticsLog.event("PlaybackService HTTP transport=Cronet provider=${cronetEngine.versionString}")
            CronetDataSource.Factory(cronetEngine, Runnable::run)
                .setUserAgent(playerUserAgent)
        } else {
            DiagnosticsLog.event("PlaybackService HTTP transport=DefaultHttpDataSource")
            defaultHttpFactory
        }
        activeHttpFactory = httpFactory
        plainHttpFactory = defaultHttpFactory
        // Cronet everywhere except sources known to trip it, which fall back to the plain stack.
        // Both factories are kept configured so the choice can change between episodes.
        val upstreamDataSource = DataSource.Factory {
            val factory = if (activeAvoidCronet) defaultHttpFactory else httpFactory
            factory.createDataSource()
        }
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(MediaCache.get(this))
            .setUpstreamDataSourceFactory(upstreamDataSource)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val downloadedOrStreamingDataSource = EpisodeDownloads.readOnlyPlaybackFactory(
            this,
            cacheDataSource,
        )
        val localAwareDataSource = DefaultDataSource.Factory(this, downloadedOrStreamingDataSource)
        val playbackDataSource = DataSource.Factory {
            FlixcloudPlaylistDataSource(localAwareDataSource.createDataSource()) {
                activePlaylistKey
            }
        }

        // Constrained phones can hit the same playback heap ceiling as Fire TV. Keep the default
        // only on devices with a larger app memory class.
        val isTvDevice = (getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val bufferPolicy = playbackBufferPolicy(
            isTv = isTvDevice,
            isLowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
        )
        val loadControl = if (bufferPolicy != null) {
            androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    bufferPolicy.minimumMs,
                    bufferPolicy.maximumMs,
                    bufferPolicy.playbackMs,
                    bufferPolicy.rebufferMs,
                )
                .build()
        } else {
            androidx.media3.exoplayer.DefaultLoadControl.Builder().build()
        }
        DiagnosticsLog.event(
            "PlaybackService buffer policy constrained=${bufferPolicy != null} " +
                "tv=$isTvDevice lowRam=${activityManager.isLowRamDevice} " +
                "memoryClassMb=${activityManager.memoryClass} maxMs=${bufferPolicy?.maximumMs ?: -1}",
        )
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSource))
            .setRenderersFactory(SubtitleDelayRenderersFactory(this))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
            }
        // Null on TV builds, and on mobile devices whose Play Services cannot serve the cast
        // module — playback just runs local.
        castRoute = CastRoutes.create(this)
        activePlayer = player
        PlaybackDiagnostics.attach(player)
        player.addListener(playbackListener(player))
        castRoute?.let { it.player.addListener(playbackListener(it.player)) }

        val localEpisodeAwarePlayer = episodeAwarePlayer(player)

        // Brand the media notification: Media3's provider handles layout, actions, and artwork
        // (loading MediaMetadata.artworkUri itself); only the small icon needs overriding.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply { setSmallIcon(com.anilili.R.drawable.ic_notification) },
        )
        session = MediaSession.Builder(this, localEpisodeAwarePlayer)
            .setSessionActivity(sessionActivity(null))
            .setCallback(object : MediaSession.Callback {
                // A paused-but-loaded session normally resumes on any KEYCODE_MEDIA_PLAY —
                // including ones a Bluetooth headset or car fires on reconnect, which made
                // paused audio restart in the background "by itself". While the app is
                // backgrounded after a lifecycle pause, ignore media-button play; the
                // notification's own play action arrives as a controller command (not a key
                // event), so deliberate resumes still work.
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    val isResumeKey = key?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        key?.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        key?.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    if (isResumeKey && suppressMediaButtonResume && activePlayer?.isPlaying != true) {
                        DiagnosticsLog.event(
                            "PlaybackService ignored background media-button resume key=${key?.keyCode}",
                        )
                        return true
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            })
            .build()
        session.setMediaButtonPreferences(
            listOf(
                CommandButton.Builder(CommandButton.ICON_REWIND)
                    .setDisplayName("Rewind 10 seconds")
                    .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_FAST_FORWARD)
                    .setDisplayName("Forward 10 seconds")
                    .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .build(),
            ),
        )

        // Media3 1.9 introduced CastPlayer.Builder.setLocalPlayer(), but that release also raised
        // minSdk to 23. Keep the same local/cast hand-off explicitly so Fire OS 5 (API 22) can use
        // the player without sacrificing casting on newer devices.
        castRoute?.let { route ->
            val cast = route.player
            val castEpisodeAwarePlayer = episodeAwarePlayer(cast)
            route.onSessionChanged(
                onAvailable = { switchPlaybackPlayer(player, cast, castEpisodeAwarePlayer) },
                onUnavailable = { switchPlaybackPlayer(cast, player, localEpisodeAwarePlayer) },
            )
        }
    }

    private fun playbackListener(source: Player): Player.Listener =
        object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.event("PlaybackService player isPlaying=$isPlaying")
                        PlaybackStatus.update(isPlaying)
                        // Any playback that actually starts (notification play, in-app play)
                        // re-arms the media buttons; the suppression only covers the window
                        // between the lifecycle pause and the next deliberate start.
                        if (isPlaying) allowMediaButtonResume()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        DiagnosticsLog.event("PlaybackService player state=${playbackState.stateName()}")
                    }

                    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                        if (activePlayer !== source) return
                        val route = if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                            "remote"
                        } else {
                            "local"
                        }
                        DiagnosticsLog.event(
                            "PlaybackService route=$route " +
                                "routingController=${deviceInfo.routingControllerId ?: "none"}",
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.throwable("PlaybackService player error code=${error.errorCodeName}", error)
                    }

                    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                        if (activePlayer !== source) return
                        DiagnosticsLog.event(
                            "PlaybackService media transition reason=$reason " +
                                "mediaId=${mediaItem?.mediaId?.take(120) ?: "none"}",
                        )
                        if (!::session.isInitialized) return
                        val route = mediaItem?.mediaMetadata?.extras?.getString(EXTRA_WATCH_ROUTE)
                        session.setSessionActivity(sessionActivity(route))
                    }
                }

    private fun episodeAwarePlayer(delegate: Player): ForwardingPlayer =
        // The playlist holds one media item at a time (episodes resolve lazily, per provider),
        // but the session still advertises next/previous by forwarding them to the app's
        // episode navigator. That lights up Media3's built-in prev/next buttons and makes the
        // media notification and hardware/Bluetooth media keys switch episodes.
        object : ForwardingPlayer(delegate) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                    super.isCommandAvailable(command)

            override fun hasNextMediaItem(): Boolean = episodeNavigator != null
            override fun hasPreviousMediaItem(): Boolean = episodeNavigator != null

            override fun seekToNext() {
                DiagnosticsLog.event("PlaybackService seekToNext navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(1) ?: super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                DiagnosticsLog.event("PlaybackService seekToNextMediaItem navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(1) ?: super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                DiagnosticsLog.event("PlaybackService seekToPrevious navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(-1) ?: super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                DiagnosticsLog.event("PlaybackService seekToPreviousMediaItem navigator=${episodeNavigator != null}")
                episodeNavigator?.invoke(-1) ?: super.seekToPreviousMediaItem()
            }
        }

    private fun switchPlaybackPlayer(from: Player, to: Player, sessionPlayer: Player) {
        if (activePlayer === to) return
        val mediaItems = List(from.mediaItemCount) { from.getMediaItemAt(it) }
        val currentIndex = from.currentMediaItemIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        val currentPosition = from.currentPosition.coerceAtLeast(0L)
        val shouldPlay = from.playWhenReady

        val transfer = runCatching {
            if (mediaItems.isNotEmpty()) {
                to.setMediaItems(mediaItems, currentIndex, currentPosition)
                to.prepare()
                to.playWhenReady = shouldPlay
            }
        }
        if (transfer.isFailure) {
            runCatching {
                to.stop()
                to.clearMediaItems()
            }
            DiagnosticsLog.throwable(
                "PlaybackService route transfer failed target=${if (to === castRoute?.player) "remote" else "local"}",
                checkNotNull(transfer.exceptionOrNull()),
            )
            return
        }

        activePlayer = to
        session.player = sessionPlayer
        from.stop()
        from.clearMediaItems()
        DiagnosticsLog.event(
            "PlaybackService switched route=${if (to === castRoute?.player) "remote" else "local"} " +
                "items=${mediaItems.size} positionMs=$currentPosition play=$shouldPlay",
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /**
     * Stands the service down when it was foreground-started with nothing to play.
     *
     * Android wakes the whole process just to hand this service a `startForegroundService`, and
     * then requires a `startForeground` within a few seconds or it kills the app with
     * [android.app.RemoteServiceException.ForegroundServiceDidNotStartInTimeException]. Media3 only
     * posts its notification once a player is really playing, and a stray media button — a headset
     * or car stereo reconnecting is the usual source — is one [MediaSession.Callback.onMediaButtonEvent]
     * deliberately ignores while the app is in the background. So nothing plays, nothing posts a
     * notification, and the process is killed: three crash reports on 0.1.54 all show a cold start
     * reaching `MiruroApp.onCreate` with no activity and no playback, then the fatal.
     *
     * Ending the service cancels that timer, which is the honest response — there is nothing here
     * to be in the foreground for. Playback started from the app binds the service instead of
     * starting it, so it never takes this path; a notification button does, and by then the player
     * holds the episode it belongs to.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if ((activePlayer?.mediaItemCount ?: 0) == 0) {
            DiagnosticsLog.event(
                "PlaybackService foreground start with nothing queued; stopping " +
                    "action=${intent?.action ?: "none"}",
            )
            stopSelf()
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val active = activePlayer
        DiagnosticsLog.event("PlaybackService.onTaskRemoved playing=${active?.playWhenReady == true}")
        if (active?.playWhenReady != true || active.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        DiagnosticsLog.event("PlaybackService.onDestroy")
        activeHttpFactory = null
        activePlayer = null
        episodeNavigator = null
        PlaybackStatus.update(false)
        if (::session.isInitialized) session.release()
        castRoute?.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun sessionActivity(route: String?): PendingIntent {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(Routes.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(
            this,
            route?.hashCode() ?: 0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val EXTRA_WATCH_ROUTE = "watch_route"
        @Volatile
        private var activeHttpFactory: HttpDataSource.Factory? = null

        /** The plain stack, kept alongside Cronet for sources Cronet cannot read. */
        @Volatile
        private var plainHttpFactory: HttpDataSource.Factory? = null

        @Volatile
        private var activeAvoidCronet: Boolean = false
        @Volatile
        private var activeUserAgent: String = NATIVE_PLAYBACK_USER_AGENT
        @Volatile
        private var activePlaylistKey: String? = null
        @Volatile
        private var activePlayer: Player? = null

        /**
         * Set by the watch screen while it is visible: receives +1/-1 and resolves the
         * next/previous episode through the normal source-resolution flow. Invoked on the
         * main thread (session commands arrive on the application looper).
         */
        @Volatile
        var episodeNavigator: ((direction: Int) -> Unit)? = null

        /** Used when switching explicitly from native playback to a provider WebView. */
        fun stopActivePlayback() {
            DiagnosticsLog.event("PlaybackService.stopActivePlayback")
            activePlayer?.run {
                stop()
                clearMediaItems()
            }
        }

        @Volatile
        private var suppressMediaButtonResume = false

        /** Pause playback when the app is backgrounded while keeping the current media loaded. */
        fun pauseActivePlayback() {
            DiagnosticsLog.event("PlaybackService.pauseActivePlayback")
            val active = activePlayer ?: return
            if (active.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                DiagnosticsLog.event("PlaybackService.pauseActivePlayback skipped remote route")
                return
            }
            active.pause()
            // The media stays loaded so the notification can resume it, but stray media-button
            // events (Bluetooth reconnects) must not restart it while the app is away.
            suppressMediaButtonResume = true
        }

        /** The app is visible again (or playback deliberately restarted): buttons act normally. */
        fun allowMediaButtonResume() {
            suppressMediaButtonResume = false
        }

        /** Applies per-provider headers before Media3 creates manifest and segment data sources. */
        fun configureRequestHeaders(
            referer: String?,
            playlistKey: String?,
            extraHeaders: Map<String, String> = emptyMap(),
            avoidCronet: Boolean = false,
        ) {
            activePlaylistKey = playlistKey
            activeAvoidCronet = avoidCronet
            val safeReferer = referer ?: "https://www.miruro.to/"
            val refererUri = android.net.Uri.parse(safeReferer)
            val origin = refererUri.let { uri ->
                if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else safeReferer
            }
            val headers = mutableMapOf("Referer" to safeReferer, "Origin" to origin)
            if (refererUri.host.orEmpty().endsWith("flixcloud.cc", ignoreCase = true)) {
                val chromiumMajor = Regex("(?:Chrome|Chromium)/(\\d+)")
                    .find(activeUserAgent)?.groupValues?.get(1) ?: "137"
                headers += mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-CH-UA" to "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"$chromiumMajor\", \"Android WebView\";v=\"$chromiumMajor\"",
                    "Sec-CH-UA-Mobile" to "?1",
                    "Sec-CH-UA-Platform" to "\"Android\"",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-site",
                    "X-Requested-With" to "com.miruronative",
                )
            }
            // Provider-supplied last so a stream that carries its own auth token wins over the
            // defaults derived from the referer.
            headers += extraHeaders
            // Both stacks stay configured; the upstream factory picks between them per stream.
            activeHttpFactory?.setDefaultRequestProperties(headers)
            plainHttpFactory?.setDefaultRequestProperties(headers)
            DiagnosticsLog.event(
                "PlaybackService.configureRequestHeaders " +
                    "refererHost=${android.net.Uri.parse(safeReferer).host ?: "unknown"} " +
                    "playlistKey=${playlistKey != null} extraHeaders=${extraHeaders.keys.sorted()} " +
                    "avoidCronet=$avoidCronet",
            )
        }
    }
}

private fun Int.stateName(): String = when (this) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> toString()
}
