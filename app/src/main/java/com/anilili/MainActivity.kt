package com.anilili

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.anilili.data.auth.AuthManager
import com.anilili.data.library.LibraryStore
import com.anilili.data.remote.FlixcloudBridge
import com.anilili.data.remote.HanimeBridge
import com.anilili.data.remote.PipeBridge
import com.anilili.data.reminder.AutomaticReleaseManager
import com.anilili.data.reminder.ReleaseSyncScheduler
import com.anilili.diagnostics.CrashReportDialog
import com.anilili.diagnostics.CrashReporter
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.DiagnosticsJankMonitor
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.MenuLanguage
import com.anilili.data.update.UpdateManager
import com.anilili.ui.detail.DetailScreen
import com.anilili.ui.FlixcloudResolverWebView
import com.anilili.ui.HanimeResolverWebView
import com.anilili.ui.AllAnimeCaptchaHost
import com.anilili.ui.home.HomeScreen
import com.anilili.ui.FirstContent
import com.anilili.ui.PipeWebView
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.TvFocusTarget
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberAppDeviceProfile
import com.anilili.ui.adaptive.rememberTvFocusTarget
import com.anilili.ui.adaptive.tvFocusRedirect
import com.anilili.ui.nav.Routes
import com.anilili.ui.components.AppLaunchSplash
import com.anilili.ui.components.LaunchSplashFadeMillis
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.LocalAppChromeVisible
import com.anilili.ui.notifications.NotificationsScreen
import com.anilili.ui.profile.ProfileScreen
import com.anilili.ui.schedule.ScheduleScreen
import com.anilili.ui.search.SearchScreen
import com.anilili.ui.components.ShortsIcon
import com.anilili.ui.shorts.ShortsScreen
import com.anilili.ui.settings.SettingsScreen
import com.anilili.ui.settings.UpdatePromptHost
import com.anilili.ui.theme.MiruroTheme
import com.anilili.ui.watch.WatchScreen
import com.anilili.ui.watch.DownloadedEpisodeScreen
import com.anilili.playback.PlaybackStatus
import com.anilili.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : FragmentActivity() {
    private var inPictureInPicture by mutableStateOf(false)
    private var pendingRoute by mutableStateOf<String?>(null)
    private var pictureInPictureReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DiagnosticsLog.event("MainActivity.onCreate start savedState=${savedInstanceState != null}")
        window.setBackgroundDrawable(ColorDrawable(Color.rgb(5, 5, 6)))
        window.decorView.setBackgroundColor(Color.rgb(5, 5, 6))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        DiagnosticsJankMonitor.install(this)
        window.decorView.setBackgroundColor(Color.rgb(5, 5, 6))
        DiagnosticsLog.snapshot(this, "MainActivity.afterSuper")
        DiagnosticsLog.event(
            "MainActivity intent action=${intent.action ?: "none"} " +
                "data=${intent.dataString ?: "none"} categories=${intent.categories?.joinToString() ?: "none"} " +
                "routeExtra=${intent.getStringExtra(Routes.EXTRA_ROUTE) ?: "none"}",
        )
        DiagnosticsLog.watchFirstDraw(window.decorView, "MainActivity")
        pendingRoute = intent.getStringExtra(Routes.EXTRA_ROUTE)
        DiagnosticsLog.event("MainActivity pendingRoute=${pendingRoute ?: "none"}")
        handleAuthRedirect(intent)
        DiagnosticsLog.event("MainActivity.setContent start")
        setContent {
            LaunchedEffect(Unit) {
                DiagnosticsLog.event("MainActivity content composed")
            }
            MiruroTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var showLaunchSplash by rememberSaveable { mutableStateOf(true) }
                    Box(Modifier.fillMaxSize()) {
                        MiruroRoot(
                            inPictureInPicture = inPictureInPicture,
                            onPictureInPictureReadyChanged = ::setPictureInPictureReady,
                            pendingRoute = pendingRoute,
                            onRouteConsumed = { pendingRoute = null },
                        )
                        AllAnimeCaptchaHost()
                        var crashReport by remember { mutableStateOf(CrashReporter.pendingReport()) }
                        crashReport?.let { report ->
                            CrashReportDialog(
                                report = report,
                                onAccepted = {
                                    // The snapshot is already uploaded or durably staged before this clears.
                                    CrashReporter.clear()
                                    crashReport = null
                                },
                                onDiscard = {
                                    CrashReporter.clear()
                                    crashReport = null
                                },
                            )
                        }
                        AnimatedVisibility(
                            visible = showLaunchSplash,
                            modifier = Modifier.fillMaxSize(),
                            exit = fadeOut(tween(LaunchSplashFadeMillis)),
                        ) {
                            AppLaunchSplash(onFinished = { showLaunchSplash = false })
                        }
                    }
                }
            }
        }
        DiagnosticsLog.event("MainActivity.setContent complete")
        window.decorView.post {
            DiagnosticsLog.event(
                "MainActivity decor after setContent attached=${window.decorView.isAttachedToWindow} " +
                    "shown=${window.decorView.isShown} size=${window.decorView.width}x${window.decorView.height} " +
                    "visibility=${window.decorView.visibilityName()} focus=${window.decorView.hasWindowFocus()}",
            )
        }
        lifecycleScope.launch {
            PlaybackStatus.isPlaying.collect { playing ->
                DiagnosticsLog.event("PlaybackStatus.isPlaying=$playing")
                if (playing) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagnosticsLog.event("MainActivity.onStart")
        PlaybackService.allowMediaButtonResume()
        LibraryStore.refreshRemoteLibrary()
    }

    override fun onResume() {
        super.onResume()
        DiagnosticsLog.event("MainActivity.onResume")
        DiagnosticsLog.snapshot(this, "MainActivity.onResume")
    }

    override fun onPause() {
        super.onPause()
        DiagnosticsLog.event("MainActivity.onPause")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(Routes.EXTRA_ROUTE)
        DiagnosticsLog.event(
            "MainActivity.onNewIntent pendingRoute=${pendingRoute ?: "none"} " +
                "action=${intent.action ?: "none"} data=${intent.dataString ?: "none"}",
        )
        handleAuthRedirect(intent)
    }

    private fun handleAuthRedirect(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (!AuthManager.isRedirect(url)) return
        DiagnosticsLog.event("Auth redirect received")
        AuthManager.extractToken(url)?.let { token ->
            AuthManager.setToken(token)
            LibraryStore.syncSavedToRemote()
            LibraryStore.refreshRemoteLibrary(force = true)
            pendingRoute = Routes.MORE
            DiagnosticsLog.event("Auth redirect accepted")
        }
    }

    override fun onStop() {
        super.onStop()
        DiagnosticsLog.event("MainActivity.onStop")
        PlaybackService.pauseActivePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        DiagnosticsLog.event("MainActivity.onDestroy finishing=$isFinishing changingConfigurations=$isChangingConfigurations")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        DiagnosticsLog.event(
            "MainActivity.onWindowFocusChanged hasFocus=$hasFocus " +
                "decorShown=${window.decorView.isShown} size=${window.decorView.width}x${window.decorView.height}",
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DiagnosticsLog.event(
            "MainActivity.onConfigurationChanged orientation=${newConfig.orientation} " +
                "screenDp=${newConfig.screenWidthDp}x${newConfig.screenHeightDp} uiMode=${newConfig.uiMode}",
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        DiagnosticsLog.event("PictureInPicture changed active=$isInPictureInPictureMode")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.O until Build.VERSION_CODES.S) return
        if (!pictureInPictureReady || inPictureInPicture) return
        enterPictureInPicture()
    }

    private fun setPictureInPictureReady(ready: Boolean) {
        if (pictureInPictureReady == ready) return
        pictureInPictureReady = ready
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture()) return
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setAutoEnterEnabled(ready)
                        setSeamlessResizeEnabled(true)
                    }
                }
                .build()
            setPictureInPictureParams(params)
        }.onFailure { DiagnosticsLog.throwable("PictureInPicture params failed", it) }
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture()) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }.onSuccess { entered ->
            DiagnosticsLog.event("PictureInPicture manual enter accepted=$entered")
        }.onFailure { DiagnosticsLog.throwable("PictureInPicture enter failed", it) }
    }

    private fun supportsPictureInPicture(): Boolean =
        packageManager.hasSystemFeature("android.software.picture_in_picture")

}

private fun View.visibilityName(): String = when (visibility) {
    View.VISIBLE -> "visible"
    View.INVISIBLE -> "invisible"
    View.GONE -> "gone"
    else -> visibility.toString()
}

private enum class Tab(
    val route: String,
    private val englishLabel: String,
    private val spanishLabel: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "Home", "Inicio", Icons.Default.Home),
    SHORTS(Routes.SHORTS, "Shorts", "Shorts", ShortsIcon),
    SEARCH(Routes.SEARCH, "Search", "Buscar", Icons.Default.Search),
    SCHEDULE(Routes.SCHEDULE, "Schedule", "Calendario", Icons.Default.DateRange),
    MORE(Routes.MORE, "Library", "Biblioteca", Icons.AutoMirrored.Filled.List),
    SETTINGS(Routes.SETTINGS, "Settings", "Ajustes", Icons.Default.Settings),
    ;

    fun label(language: MenuLanguage): String = if (language.usesSpanish()) spanishLabel else englishLabel
}

/** Search is launched from Home's top action on phones; TV keeps it in the navigation rail. */
private val phoneTabs = Tab.entries.filterNot { it == Tab.SEARCH }

/** Compact phone navigation content height; the system navigation inset is added separately. */
private val PhoneNavigationBarHeight = 64.dp

@Composable
private fun MiruroRoot(
    inPictureInPicture: Boolean,
    onPictureInPictureReadyChanged: (Boolean) -> Unit,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
) {
    val deviceProfile = rememberAppDeviceProfile()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = Routes.tabRoute(backStack?.destination?.route)
    val showBottomBar = currentRoute in Routes.tabRoutes
    val menuLanguage by SettingsStore.menuLanguage.collectAsState()
    var chromeVisible by remember { mutableStateOf(true) }
    val chromeScope = rememberCoroutineScope()
    var restoreChromeJob by remember { mutableStateOf<Job?>(null) }
    val tvSearchRailFocusRequester = remember { FocusRequester() }
    // Redirect destinations, not plain requesters: both live on screens that are only composed on
    // some routes and only once their data lands, and a redirect to an absent one crashes the app.
    val tvSearchFieldFocusTarget = rememberTvFocusTarget()
    val tvHomePrimaryFocusTarget = rememberTvFocusTarget()
    // Direction-based like YouTube/Chrome: hide once a downward scroll passes a small threshold,
    // show the moment the user scrolls up (or goes idle). The threshold stops micro-scrolls from
    // flickering the chrome, and hide/show firing once per direction change (instead of on every
    // scroll frame) is what keeps the animation smooth.
    val chromeHideThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    val chromeScrollConnection = remember(deviceProfile.isTv, chromeHideThresholdPx) {
        object : NestedScrollConnection {
            private var accumulated = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (deviceProfile.isTv || available.y == 0f) return Offset.Zero
                if ((accumulated < 0f) != (available.y < 0f)) accumulated = 0f
                accumulated += available.y
                if (accumulated < -chromeHideThresholdPx && chromeVisible) {
                    chromeVisible = false
                } else if (accumulated > chromeHideThresholdPx && !chromeVisible) {
                    chromeVisible = true
                    restoreChromeJob?.cancel()
                }
                if (!chromeVisible) {
                    // Long enough that a pause mid-scroll does not summon the navigation bar
                    // under a thumb already on its way to a poster, short enough that the bars
                    // feel available rather than dismissed.
                    restoreChromeJob?.cancel()
                    restoreChromeJob = chromeScope.launch {
                        delay(1_000)
                        chromeVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(Unit) {
        DiagnosticsLog.event(
            "MiruroRoot composed formFactor=${deviceProfile.formFactor} " +
                "widthDp=${deviceProfile.widthDp} navRail=${deviceProfile.useNavigationRail}",
        )
    }

    LaunchedEffect(currentRoute) {
        DiagnosticsLog.event("Nav route=${currentRoute ?: "none"}")
    }

    LaunchedEffect(pendingRoute) {
        pendingRoute?.takeIf { it.isNotBlank() }?.let { route ->
            DiagnosticsLog.event("Consuming pending route=$route")
            // Tabs must go through navigateTab: a plain navigate pushes the tab on top of the
            // start destination, and the next Home tap then restores that entry as Home's state.
            if (route in Routes.tabRoutes) nav.navigateTab(route) else nav.navigate(route) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    val context = LocalContext.current
    val pipeResolverRequired by PipeBridge.resolverRequired.collectAsState()
    val flixcloudResolverRequired by FlixcloudBridge.resolverRequired.collectAsState()
    val hanimeResolverRequired by HanimeBridge.resolverRequired.collectAsState()
    val playbackActive by PlaybackStatus.isPlaying.collectAsState()
    // Constructing a WebView during first composition delays the first visible frame, badly so on
    // a TV stick. The warm bridge is worth having, but not at the cost of a slower cold start, so
    // it is stood up once the UI has settled.
    //
    // On TV it additionally waits for the first screen to finish loading. A device report caught
    // the bridge building itself while home's own request was still in flight — AniList was
    // unreachable on that network and hung for 12 seconds — so two expensive things ran at once
    // on a box already dropping frames. Nothing waits on the resolver at startup, so it can.
    val firstContentSettled by FirstContent.settled.collectAsState()
    var startupGraceElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(deviceProfile.isTv) {
        delay(if (deviceProfile.isTv) 6_000 else 2_500)
        startupGraceElapsed = true
    }
    val resolverWebViewsReady =
        startupGraceElapsed && (!deviceProfile.isTv || firstContentSettled)
    val hideAdult by SettingsStore.hideAdultContent.collectAsState()

    LaunchedEffect(deviceProfile.isTv, currentRoute) {
        // A watch deep link can be the process's first screen. Do not let a launch-only library
        // refresh wake up halfway through that episode; returning to a top-level tab starts this
        // bounded delay afresh. Explicit login/list/permission syncs still bypass this path.
        if (currentRoute !in Routes.tabRoutes) return@LaunchedEffect
        SettingsStore.awaitLoaded()
        // Keep notification/library network fan-out away from first composition and the initial
        // D-pad focus hand-off. A freshness check below prevents activity recreation from turning
        // this bounded delay into repeated work.
        delay(if (deviceProfile.isTv) 15_000 else 8_000)
        ReleaseSyncScheduler.runAfterStartupIfStale(context)
    }

    LaunchedEffect(Unit) {
        SettingsStore.awaitLoaded()
        if (!SettingsStore.updateCheckOnLaunch.value) {
            DiagnosticsLog.event("UpdateManager.autoCheckIfDue skipped (disabled in settings)")
            return@LaunchedEffect
        }
        DiagnosticsLog.event("UpdateManager.autoCheckIfDue start")
        UpdateManager.autoCheckIfDue(context)
        DiagnosticsLog.event("UpdateManager.autoCheckIfDue complete")
    }

    val navBarInsetForChrome = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hasPhoneBottomBarForChrome = showBottomBar && !deviceProfile.useNavigationRail
    CompositionLocalProvider(
        LocalAppDeviceProfile provides deviceProfile,
        LocalAppChromeVisible provides (chromeVisible || deviceProfile.isTv),
        LocalAppChromeBottomInset provides if (hasPhoneBottomBarForChrome) {
            PhoneNavigationBarHeight + navBarInsetForChrome
        } else {
            0.dp
        },
    ) {
        NotificationPermissionEffect()
        UpdatePromptHost()
        Box(Modifier.fillMaxSize().nestedScroll(chromeScrollConnection)) {
            val hasPhoneBottomBar = showBottomBar && !deviceProfile.useNavigationRail
            // The bar overlays the content and slides out via graphicsLayer, so it neither
            // re-lays out the screen during animation nor leaves a reserved background band.
            // Tab content also draws behind the system navigation area for a fully edge-to-edge
            // viewport when the bar is hidden; non-tab screens retain Scaffold's safe inset.
            val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                val showTvTopNavigation = deviceProfile.isTv &&
                    (showBottomBar || currentRoute == Routes.DETAIL)
                Box(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                bottom = if (hasPhoneBottomBar) 0.dp else innerPadding.calculateBottomPadding(),
                            ),
                    ) {
                        if (showBottomBar && deviceProfile.useNavigationRail && !deviceProfile.isTv) {
                            AppNavigationRail(
                                currentRoute = currentRoute,
                                menuLanguage = menuLanguage,
                                onNavigate = nav::navigateTab,
                                searchRailFocusRequester = tvSearchRailFocusRequester,
                                searchFieldFocusTarget = tvSearchFieldFocusTarget,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                        AppNavHost(
                            nav = nav,
                            inPictureInPicture = inPictureInPicture,
                            onPictureInPictureReadyChanged = onPictureInPictureReadyChanged,
                            tvSearchFieldFocusTarget = tvSearchFieldFocusTarget,
                            tvHomePrimaryFocusTarget = tvHomePrimaryFocusTarget,
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (
                                        showTvTopNavigation &&
                                        currentRoute != Routes.HOME &&
                                        currentRoute != Routes.DETAIL
                                    ) {
                                        Modifier.padding(top = 82.dp)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    if (showTvTopNavigation) {
                        AppTvTopNavigation(
                            currentRoute = currentRoute,
                            menuLanguage = menuLanguage,
                            onNavigate = nav::navigateTab,
                            onNotificationsClick = {
                                nav.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true }
                            },
                            searchNavFocusRequester = tvSearchRailFocusRequester,
                            searchFieldFocusTarget = tvSearchFieldFocusTarget,
                            homeContentFocusTarget = tvHomePrimaryFocusTarget,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
            if (hasPhoneBottomBar) {
                val chromeShift by animateFloatAsState(
                    targetValue = if (chromeVisible) 0f else 1f,
                    animationSpec = tween(220),
                    label = "chromeShift",
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .height(PhoneNavigationBarHeight + navBarInset)
                        .graphicsLayer { translationY = size.height * chromeShift },
                ) {
                    phoneTabs.forEach { tab ->
                        val label = tab.label(menuLanguage)
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
            // The Miruro bridge is kept alive between requests rather than rebuilt for each one.
            //
            // Tearing it down after the last request meant every resolve that followed an idle
            // gap paid to construct a WebView, load a mirror and clear Cloudflare before it could
            // run a single fetch — users reported exactly this as servers "loading fast on 0.1.49
            // and slow after", which is the release that made it on-demand. PipeBridge already
            // calls onPause() once idle, so a resident bridge is a paused renderer holding a page,
            // not one burning CPU.
            //
            // TV still gives it up during playback. That is the one window where the memory
            // genuinely matters (96-192 MB heaps, low-RAM sticks) and where a hidden browser has
            // been seen taking the hardware decoder away from the video. Everywhere else — all of
            // mobile, and TV while browsing — it stays warm.
            val keepPipeBridgeWarm = !deviceProfile.isTv || !playbackActive
            if (pipeResolverRequired || (resolverWebViewsReady && keepPipeBridgeWarm)) {
                PipeWebView()
            }
            if (flixcloudResolverRequired) {
                FlixcloudResolverWebView()
            }
            if (!hideAdult && hanimeResolverRequired) {
                HanimeResolverWebView()
            }
        }
    }
}

/** Frames to keep retrying the rail's initial focus on slow TV boxes before giving up. */
private const val TV_FOCUS_ATTEMPTS = 10

private val tvPrimaryTabs = Tab.entries.filterNot { it == Tab.SETTINGS }
private val TvClockFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun AppTvTopNavigation(
    currentRoute: String?,
    menuLanguage: MenuLanguage,
    onNavigate: (String) -> Unit,
    onNotificationsClick: () -> Unit,
    searchNavFocusRequester: FocusRequester,
    searchFieldFocusTarget: TvFocusTarget,
    homeContentFocusTarget: TvFocusTarget,
    modifier: Modifier = Modifier,
) {
    val focusRequesters = remember(searchNavFocusRequester) {
        Tab.entries.associateWith { tab ->
            if (tab == Tab.SEARCH) searchNavFocusRequester else FocusRequester()
        }
    }
    val unread by com.anilili.data.reminder.NotificationCenter.unread.collectAsState()
    var clockText by remember { mutableStateOf(LocalTime.now().format(TvClockFormatter)) }

    LaunchedEffect(Unit) {
        while (true) {
            clockText = LocalTime.now().format(TvClockFormatter)
            delay(30_000)
        }
    }
    LaunchedEffect(currentRoute) {
        Tab.entries.firstOrNull { it.route == currentRoute }
            ?.let { tab -> runCatching { focusRequesters.getValue(tab).requestFocus() } }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to ComposeColor.Black.copy(.74f),
                    1f to ComposeColor.Transparent,
                ),
            )
            .padding(horizontal = 34.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.anilili_launcher),
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(CircleShape),
            )
            Text(
                // The wordmark is capital-A "Anilili" everywhere else — launcher label, phone
                // rail, APK filenames — so the TV header uses the brand as written, not shouted.
                // Sized to stand level with the 42.dp mark beside it; tracking eases off as the
                // type grows, since 1.5sp of it reads as a gap at this size.
                text = stringResource(R.string.app_name),
                color = ComposeColor.White,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tvPrimaryTabs.forEach { tab ->
                TvTopNavigationItem(
                    tab = tab,
                    label = tab.label(menuLanguage),
                    selected = currentRoute == tab.route ||
                        (currentRoute == Routes.DETAIL && tab == Tab.HOME),
                    onClick = { onNavigate(tab.route) },
                    focusRequester = focusRequesters.getValue(tab),
                    searchFieldFocusTarget = searchFieldFocusTarget,
                    homeContentFocusTarget = homeContentFocusTarget,
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(
                badge = {
                    if (unread > 0) {
                        Badge { Text(if (unread > 99) "99+" else unread.toString()) }
                    }
                },
            ) {
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.focusHighlight(CircleShape, focusedScale = 1.08f),
                ) {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications",
                        tint = ComposeColor.White.copy(.78f),
                    )
                }
            }
            IconButton(
                onClick = { onNavigate(Tab.SETTINGS.route) },
                modifier = Modifier
                    .focusRequester(focusRequesters.getValue(Tab.SETTINGS))
                    .background(
                        if (currentRoute == Tab.SETTINGS.route) {
                            MaterialTheme.colorScheme.primary.copy(.24f)
                        } else {
                            ComposeColor.Transparent
                        },
                        CircleShape,
                    )
                    .focusHighlight(CircleShape, focusedScale = 1.08f),
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = Tab.SETTINGS.label(menuLanguage),
                    tint = if (currentRoute == Tab.SETTINGS.route) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        ComposeColor.White.copy(.78f)
                    },
                )
            }
            Spacer(Modifier.width(3.dp))
            Text(
                clockText,
                color = ComposeColor.White.copy(.72f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TvTopNavigationItem(
    tab: Tab,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    searchFieldFocusTarget: TvFocusTarget,
    homeContentFocusTarget: TvFocusTarget,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val container = when {
        focused -> ComposeColor.White
        selected -> MaterialTheme.colorScheme.primary.copy(.24f)
        else -> ComposeColor.Transparent
    }
    val content = when {
        focused -> ComposeColor.Black
        selected -> ComposeColor.White
        else -> ComposeColor.White.copy(.68f)
    }

    Row(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties {
                // Only redirect into a destination that is on screen right now. HOME stays
                // "selected" while a detail page is open and the home rows are gone, and the
                // search box is absent until Search composes — redirecting at either of those
                // moments throws out of the focus owner and kills the app.
                if (selected) {
                    when (tab) {
                        Tab.HOME -> tvFocusRedirect(homeContentFocusTarget) { down = it }
                        Tab.SEARCH -> tvFocusRedirect(searchFieldFocusTarget) { down = it }
                        else -> Unit
                    }
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(container)
            .border(
                width = if (focused) 0.dp else if (selected) 1.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(.5f) else ComposeColor.Transparent,
                shape = shape,
            )
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(tab.icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NotificationPermissionEffect() {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val enabled by SettingsStore.releaseNotifications.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        SettingsStore.setReleaseNotifications(granted)
        if (granted) ReleaseSyncScheduler.runNow(context) else AutomaticReleaseManager.cancelAll()
    }

    LaunchedEffect(enabled, device.isTv) {
        if (!enabled) {
            AutomaticReleaseManager.cancelAll()
            return@LaunchedEffect
        }
        if (device.isTv || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            // The launch-wide, freshness-gated effect handles an already-granted permission.
            // A permission granted by the user in this session still runs immediately above.
            return@LaunchedEffect
        }
        val prefs = context.getSharedPreferences("anilili_permissions", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("release_notifications_prompted", false)) {
            prefs.edit().putBoolean("release_notifications_prompted", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SettingsStore.setReleaseNotifications(false)
            AutomaticReleaseManager.cancelAll()
        }
    }
}

@Composable
private fun AppNavigationRail(
    currentRoute: String?,
    menuLanguage: MenuLanguage,
    onNavigate: (String) -> Unit,
    searchRailFocusRequester: FocusRequester,
    searchFieldFocusTarget: TvFocusTarget,
    modifier: Modifier = Modifier,
) {
    val device = LocalAppDeviceProfile.current
    val focusRequesters = remember(searchRailFocusRequester) {
        Tab.entries.associateWith { tab ->
            if (tab == Tab.SEARCH) searchRailFocusRequester else FocusRequester()
        }
    }
    // Slow Fire TV boxes (AFTTIFF43) can run this before the rail's focus targets are attached,
    // and FocusRequester.requestFocus() throws outright when its node is not there yet. Retry
    // across a few frames instead of swallowing the failure: a silent catch left the rail with
    // nothing focused at all, which on a D-pad means the user cannot move.
    LaunchedEffect(currentRoute, device.isTv) {
        if (!device.isTv) return@LaunchedEffect
        val tab = Tab.entries.firstOrNull { it.route == currentRoute } ?: return@LaunchedEffect
        val requester = focusRequesters.getValue(tab)
        repeat(TV_FOCUS_ATTEMPTS) { attempt ->
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos {}
            if (attempt == TV_FOCUS_ATTEMPTS - 1) {
                DiagnosticsLog.event("Navigation rail focus never attached route=$currentRoute")
            }
        }
    }
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Text(
                stringResource(R.string.app_name),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 20.dp),
            )
        },
    ) {
        Tab.entries.forEach { tab ->
            val label = tab.label(menuLanguage)
            NavigationRailItem(
                selected = currentRoute == tab.route,
                onClick = { onNavigate(tab.route) },
                icon = { Icon(tab.icon, contentDescription = label) },
                label = { Text(label) },
                alwaysShowLabel = device.isTv,
                modifier = Modifier
                    .focusRequester(focusRequesters.getValue(tab))
                    .focusProperties {
                        // Spatial focus search prefers the Movies chip because it is horizontally
                        // aligned with the rail item. Route Right to the actual search box — but
                        // only while that box is really attached, since resolving a redirect to a
                        // requester nothing holds throws out of the focus owner.
                        if (device.isTv && tab == Tab.SEARCH && currentRoute == Routes.SEARCH) {
                            tvFocusRedirect(searchFieldFocusTarget) { right = it }
                        }
                    }
                    .focusHighlight(),
            )
        }
    }
}

@Composable
private fun AppNavHost(
    nav: androidx.navigation.NavHostController,
    inPictureInPicture: Boolean,
    onPictureInPictureReadyChanged: (Boolean) -> Unit,
    tvSearchFieldFocusTarget: TvFocusTarget,
    tvHomePrimaryFocusTarget: TvFocusTarget,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
            composable(Routes.HOME) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route HOME content entered") }
                HomeScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    onWatchNow = { id ->
                        val saved = com.anilili.data.library.LibraryStore.historyFor(id)
                        if (saved != null) nav.navigate(Routes.watch(id, saved.provider, saved.category, saved.episodeLabel))
                        else nav.navigate(Routes.watch(id, "auto", if (com.anilili.data.settings.SettingsStore.preferDub.value) "dub" else "sub", "1"))
                    },
                    onResume = { e -> nav.navigate(Routes.watch(e.anilistId, e.provider, e.category, e.episodeLabel)) },
                    onSearchClick = { nav.navigateTab(Routes.SEARCH) },
                    onGenreClick = { genre ->
                        if (genre == null) nav.navigateTab(Routes.SEARCH)
                        else nav.navigate(Routes.genreSearch(genre)) { launchSingleTop = true }
                    },
                    onNotificationsClick = { nav.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true } },
                    onShortsClick = { nav.navigateTab(Routes.SHORTS) },
                    tvPrimaryFocusTarget = tvHomePrimaryFocusTarget,
                )
            }
            composable(Routes.NOTIFICATIONS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route NOTIFICATIONS content entered") }
                NotificationsScreen(
                    onBack = { nav.popBackStack() },
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                )
            }
            composable(
                route = Routes.SEARCH_DESTINATION,
                arguments = listOf(
                    navArgument(Routes.Arg.STUDIO_ID) {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                    navArgument(Routes.Arg.GENRE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Routes.Arg.STUDIO_NAME) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SEARCH content entered") }
                SearchScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    tvFieldFocusTarget = tvSearchFieldFocusTarget,
                    initialStudioId = entry.arguments?.getInt(Routes.Arg.STUDIO_ID)?.takeIf { it > 0 },
                    initialStudioName = entry.arguments?.getString(Routes.Arg.STUDIO_NAME),
                    initialGenre = entry.arguments?.getString(Routes.Arg.GENRE),
                )
            }
            composable(Routes.SCHEDULE) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SCHEDULE content entered") }
                ScheduleScreen(onAnimeClick = { id -> nav.navigate(Routes.detail(id)) })
            }
            composable(Routes.MORE) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route MORE content entered") }
                ProfileScreen(
                    onAnimeClick = { id -> nav.navigate(Routes.detail(id)) },
                    onResume = { e ->
                        nav.navigate(Routes.watch(e.anilistId, e.provider, e.category, e.episodeLabel))
                    },
                    onPlayDownload = { downloadId ->
                        nav.navigate(Routes.download(downloadId))
                    },
                )
            }
            composable(Routes.SHORTS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SHORTS content entered") }
                ShortsScreen(
                    onWatchAnime = { id, ep ->
                        val preferDub = SettingsStore.preferDub.value
                        val category = if (preferDub) "dub" else "sub"
                        nav.navigate(Routes.watch(id, provider = "auto", category = category, episode = ep.toString()))
                    },
                    onOpenSettings = {
                        nav.navigateTab(Routes.SETTINGS)
                    },
                )
            }
            composable(Routes.SETTINGS) {
                LaunchedEffect(Unit) { DiagnosticsLog.event("Route SETTINGS content entered") }
                SettingsScreen()
            }

            composable(
                route = Routes.DOWNLOAD,
                arguments = listOf(navArgument(Routes.Arg.DOWNLOAD_ID) { type = NavType.StringType }),
            ) { entry ->
                val downloadId = entry.arguments?.getString(Routes.Arg.DOWNLOAD_ID)
                    ?: return@composable
                LaunchedEffect(downloadId) {
                    DiagnosticsLog.event("Route DOWNLOAD content entered id=$downloadId")
                }
                DownloadedEpisodeScreen(
                    downloadId = downloadId,
                    onBack = { nav.popBackStack() },
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument(Routes.Arg.ID) { type = NavType.IntType }),
            ) { entry ->
                val id = entry.arguments?.getInt(Routes.Arg.ID) ?: return@composable
                val deviceProfile = LocalAppDeviceProfile.current
                LaunchedEffect(id) { DiagnosticsLog.event("Route DETAIL content entered id=$id") }
                DetailScreen(
                    animeId = id,
                    onBack = { nav.popBackStack() },
                    onAnimeClick = { relatedId ->
                        if (relatedId != id) nav.navigate(Routes.detail(relatedId))
                    },
                    onStudioClick = { studio ->
                        val name = studio.name
                        if (studio.id > 0 && !name.isNullOrBlank()) {
                            nav.navigate(Routes.studioSearch(studio.id, name)) { launchSingleTop = true }
                        }
                    },
                    onPlay = { playId, provider, category, episode ->
                        // TV: Watch lands on the episode grid (playback starts inline) so the
                        // user picks an episode; going straight to fullscreen autoplay left no
                        // way to choose one. Phones keep the direct-to-player behavior.
                        // playId may be another season of the same series — the detail page
                        // hosts the whole chain and its Episodes tab filters between seasons.
                        if (deviceProfile.isTv) {
                            nav.navigate(Routes.episodes(playId, provider, category, episode))
                        } else {
                            nav.navigate(Routes.watch(playId, provider, category, episode))
                        }
                    },
                    onSeasonWatch = { seasonId ->
                        val saved = com.anilili.data.library.LibraryStore.historyFor(seasonId)
                        if (saved != null) {
                            nav.navigate(Routes.episodes(seasonId, saved.provider, saved.category, saved.episodeLabel))
                        } else {
                            nav.navigate(Routes.episodes(seasonId, "auto", if (com.anilili.data.settings.SettingsStore.preferDub.value) "dub" else "sub", "1"))
                        }
                    },
                )
            }

            composable(
                route = Routes.WATCH,
                arguments = listOf(
                    navArgument(Routes.Arg.ID) { type = NavType.IntType },
                    navArgument(Routes.Arg.PROVIDER) { type = NavType.StringType },
                    navArgument(Routes.Arg.CATEGORY) { type = NavType.StringType },
                    navArgument(Routes.Arg.EPISODE) { type = NavType.StringType },
                    navArgument(Routes.Arg.SHOW_EPISODES) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                val args = entry.arguments ?: return@composable
                val watchId = args.getInt(Routes.Arg.ID)
                val watchProvider = args.getString(Routes.Arg.PROVIDER).orEmpty()
                val watchCategory = args.getString(Routes.Arg.CATEGORY).orEmpty()
                val watchEpisode = args.getString(Routes.Arg.EPISODE).orEmpty()
                val showEpisodes = args.getBoolean(Routes.Arg.SHOW_EPISODES)
                LaunchedEffect(watchId, watchProvider, watchCategory, watchEpisode) {
                    DiagnosticsLog.event(
                        "Route WATCH content entered id=$watchId provider=$watchProvider " +
                            "category=$watchCategory episode=$watchEpisode",
                    )
                }
                WatchScreen(
                    animeId = watchId,
                    provider = watchProvider,
                    category = watchCategory,
                    episode = watchEpisode,
                    showEpisodeListInitially = showEpisodes,
                    inPictureInPicture = inPictureInPicture,
                    onPictureInPictureReadyChanged = onPictureInPictureReadyChanged,
                    onBack = { nav.popBackStack() },
                    onOpenAnime = { nav.navigate(Routes.detail(watchId)) },
                )
            }
        }
}

private fun NavController.navigateTab(route: String) {
    val restoreTabState = Routes.shouldRestoreTabState(route)
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = restoreTabState }
        launchSingleTop = true
        restoreState = restoreTabState
    }
}
