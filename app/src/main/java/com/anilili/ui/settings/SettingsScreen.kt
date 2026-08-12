package com.anilili.ui.settings

import com.anilili.R
import com.anilili.BuildConfig
import com.anilili.data.AppGraph
import com.anilili.data.remote.ConnectionStatus
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.cache.CacheManager
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.MalExportFile
import com.anilili.data.library.MalImport
import com.anilili.data.reminder.AutomaticReleaseManager
import com.anilili.data.reminder.ReleaseSyncScheduler
import com.anilili.data.ProviderCatalog
import com.anilili.data.settings.DEFAULT_PREFERRED_PROVIDER
import com.anilili.data.settings.DefaultQuality
import com.anilili.data.settings.DownloadDestination
import com.anilili.data.settings.DownloadQuality
import com.anilili.data.settings.MAX_SERVER_PRIORITY
import com.anilili.data.settings.SKIP_BUTTON_MAX_SECONDS
import com.anilili.data.settings.SKIP_BUTTON_MIN_SECONDS
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.MenuLanguage
import com.anilili.data.update.UpdateManager
import com.anilili.diagnostics.DiagnosticTrigger
import com.anilili.diagnostics.DiagnosticSendResult
import com.anilili.diagnostics.DiagnosticSubmissionDialog
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.DiagnosticsUploadManager
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusHighlight
import com.anilili.ui.adaptive.rememberScreenReaderActive
import com.anilili.ui.components.CaptionAppearanceDialog
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.ui.profile.AniListProfile
import com.anilili.ui.profile.confirmSignedIn
import com.anilili.ui.profile.LoginWebView
import com.anilili.ui.profile.MalImportProgress
import com.anilili.ui.profile.MalImportStage
import com.anilili.ui.profile.ProfileViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val screenReaderActive = rememberScreenReaderActive()
    val token by AuthManager.token.collectAsState()
    val malLoggedIn by MalAuthManager.loggedIn.collectAsState()
    val profileState by vm.profile.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val autoSkip by SettingsStore.autoSkipIntroOutro.collectAsState()
    val showSkipButton by SettingsStore.showSkipButton.collectAsState()
    val skipButtonSeconds by SettingsStore.skipButtonSeconds.collectAsState()
    val autoSync by SettingsStore.autoSyncAniList.collectAsState()
    val preferDub by SettingsStore.preferDub.collectAsState()
    val defaultQuality by SettingsStore.defaultQuality.collectAsState()
    val playerGestures by SettingsStore.playerGestures.collectAsState()
    val serverPriority by SettingsStore.serverPriority.collectAsState()
    val downloadQuality by SettingsStore.downloadQuality.collectAsState()
    val downloadDestination by SettingsStore.downloadDestination.collectAsState()
    val releaseNotifications by SettingsStore.releaseNotifications.collectAsState()
    val hideAdultContent by SettingsStore.hideAdultContent.collectAsState()
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    val subtitlesWithDub by SettingsStore.subtitlesWithDub.collectAsState()
    val updateCheckOnLaunch by SettingsStore.updateCheckOnLaunch.collectAsState()
    val syncSavedToAniList by SettingsStore.syncSavedToAniList.collectAsState()
    val menuLanguage by SettingsStore.menuLanguage.collectAsState()
    val youtubeApiKey by SettingsStore.youtubeApiKey.collectAsState()
    val shortsApiUrl by SettingsStore.shortsApiUrl.collectAsState()
    val updateState by UpdateManager.state.collectAsState()
    val profile = (profileState as? UiState.Success<AniListProfile>)?.data
    val scope = rememberCoroutineScope()
    var pendingMalExport by remember { mutableStateOf<MalExportFile?>(null) }
    var malExportBusy by remember { mutableStateOf(false) }
    var malExportMessage by remember { mutableStateOf<String?>(null) }
    var malImportBusy by remember { mutableStateOf(false) }
    var malImportMessage by remember { mutableStateOf<String?>(null) }
    var malImportProgress by remember { mutableStateOf<MalImportProgress?>(null) }
    var malImportJob by remember { mutableStateOf<Job?>(null) }
    var loginService by remember { mutableStateOf<AccountService?>(null) }
    var diagnosticsMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticsBusy by remember { mutableStateOf(false) }
    var diagnosticsDialogVisible by remember { mutableStateOf(false) }
    var diagnosticsError by remember { mutableStateOf<String?>(null) }
    var captionAppearanceVisible by remember { mutableStateOf(false) }
    var cacheUsage by remember { mutableStateOf<Long?>(null) }
    var cacheClearing by remember { mutableStateOf(false) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
    // Only the first group opens by default: the page is long enough that showing every row at
    // once is what made it hard to scan, and on a remote it is a lot of rows to travel past.
    var expandedSections by rememberSaveable { mutableStateOf(listOf("Playback")) }
    fun toggleSection(name: String) {
        expandedSections =
            if (name in expandedSections) expandedSections - name else expandedSections + name
    }

    LaunchedEffect(token, malLoggedIn) { vm.loadIfLoggedIn() }
    LaunchedEffect(Unit) {
        cacheUsage = withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
    }

    if (captionAppearanceVisible) {
        CaptionAppearanceDialog(
            onDismiss = { captionAppearanceVisible = false },
            footnote = "Applies to the built-in player. Servers that play in a web view render " +
                "their own subtitles and may ignore some of these.",
        )
    }
    if (diagnosticsDialogVisible) {
        DiagnosticSubmissionDialog(
            title = "Send diagnostics",
            introduction = "Describe the problem so the report has useful context. This uploads the " +
                "app timeline, device, performance, playback and network diagnostics. Passwords, " +
                "cookies, tokens and sensitive links are removed.",
            descriptionRequired = true,
            sending = diagnosticsBusy,
            errorMessage = diagnosticsError,
            onDismiss = {
                diagnosticsDialogVisible = false
                diagnosticsError = null
            },
            onSend = { submission ->
                diagnosticsBusy = true
                diagnosticsError = null
                diagnosticsMessage = "Preparing the full diagnostic report…"
                scope.launch {
                    try {
                        val result = DiagnosticsUploadManager.send(
                            context,
                            DiagnosticTrigger.MANUAL,
                            submission,
                        )
                        if (result is DiagnosticSendResult.Failed) {
                            diagnosticsError = result.reason
                        } else {
                            diagnosticsMessage = result.userMessage()
                            diagnosticsDialogVisible = false
                        }
                    } finally {
                        diagnosticsBusy = false
                    }
                }
            },
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        SettingsStore.setReleaseNotifications(granted)
        if (granted) ReleaseSyncScheduler.runNow(context)
    }
    val malExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml"),
    ) { uri ->
        val file = pendingMalExport
        pendingMalExport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(file.xml.toByteArray(Charsets.UTF_8))
            } ?: error("Couldn't open export file")
        }.onSuccess {
            malExportMessage = buildString {
                append("Exported ${file.exportedCount} anime")
                if (file.skippedCount > 0) append("; skipped ${file.skippedCount} without MAL IDs")
            }
        }.onFailure { error ->
            malExportMessage = error.message ?: "MAL export failed"
        }
    }
    // MAL serves exports as .xml.gz, which file pickers report under assorted mime types, so the
    // filter stays wide open and the parser decides.
    val malImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            DiagnosticsLog.event("mal_import", "picker.cancelled")
            return@rememberLauncherForActivityResult
        }
        malImportJob = scope.launch {
            malImportBusy = true
            malImportMessage = null
            malImportProgress = MalImportProgress(MalImportStage.READING)
            try {
                val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                val reportedBytes = context.malImportReportedSize(uri)
                DiagnosticsLog.event(
                    category = "mal_import",
                    name = "picker.file_selected",
                    attributes = mapOf(
                        "mimeType" to (mimeType ?: "unknown"),
                        "reportedBytes" to (reportedBytes ?: -1L),
                    ),
                )
                if (reportedBytes != null && reportedBytes > MalImport.MAX_SOURCE_BYTES) {
                    error("The MAL export is larger than the 16 MB safety limit.")
                }
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        MalImport.readSource(stream)
                    }
                        ?: error("Couldn't open that file")
                }
                DiagnosticsLog.event(
                    category = "mal_import",
                    name = "picker.file_read",
                    attributes = mapOf("actualBytes" to bytes.size),
                )
                val summary = vm.importMalXml(bytes) { progress ->
                    malImportProgress = progress
                }
                malImportMessage = buildString {
                    append("Imported ${summary.added} new anime")
                    if (summary.alreadySaved > 0) append("; ${summary.alreadySaved} already saved")
                    if (summary.unmatched > 0) append("; ${summary.unmatched} not found on AniList")
                }
            } catch (error: CancellationException) {
                DiagnosticsLog.event(
                    category = "mal_import",
                    name = "ui.cancelled",
                    attributes = mapOf("stage" to (malImportProgress?.stage?.name?.lowercase() ?: "reading")),
                )
                malImportMessage = "MyAnimeList import cancelled"
                throw error
            } catch (error: Exception) {
                DiagnosticsLog.event(
                    category = "mal_import",
                    name = "ui.failed",
                    attributes = mapOf(
                        "stage" to (malImportProgress?.stage?.name?.lowercase() ?: "reading"),
                        "errorType" to error.javaClass.simpleName,
                        "message" to (error.message ?: "none"),
                    ),
                )
                malImportMessage = error.message ?: "MAL import failed"
            } finally {
                malImportProgress = null
                malImportBusy = false
                malImportJob = null
            }
        }
    }

    when (loginService) {
        AccountService.ANILIST -> {
            LoginWebView(
                authorizeUrl = remember(loginService) { AuthManager.authorizeUrl() },
                isRedirect = AuthManager::isRedirect,
                extractResult = AuthManager::extractToken,
                onResult = {
                    loginService = null
                    vm.onLoggedIn(it)
                    confirmSignedIn(context, "AniList")
                },
                onCancel = { loginService = null },
            )
            return
        }
        AccountService.MAL -> {
            LoginWebView(
                authorizeUrl = remember(loginService) { MalAuthManager.authorizeUrl() },
                isRedirect = MalAuthManager::isRedirect,
                extractResult = MalAuthManager::extractCode,
                onResult = { loginService = null; vm.onMalCode(it) },
                onCancel = { loginService = null },
            )
            return
        }
        null -> Unit
    }

    fun setReleaseNotifications(enabled: Boolean) {
        if (!enabled) {
            SettingsStore.setReleaseNotifications(false)
            AutomaticReleaseManager.cancelAll()
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SettingsStore.setReleaseNotifications(true)
            ReleaseSyncScheduler.runNow(context)
        }
    }

    fun setWatchlistSync(enabled: Boolean) {
        SettingsStore.setSyncSavedToAniList(enabled)
        if (enabled) {
            LibraryStore.syncSavedToRemote()
            vm.loadIfLoggedIn(refresh = true)
        }
    }

    fun exportMal() {
        if (malExportBusy) return
        scope.launch {
            malExportBusy = true
            malExportMessage = null
            runCatching { vm.buildMalExport(profile, watchlist, history) }
                .onSuccess { file ->
                    if (file.exportedCount == 0) {
                        malExportMessage = "No MAL-mapped anime to export"
                    } else {
                        pendingMalExport = file
                        malExportLauncher.launch(file.fileName)
                    }
                }
                .onFailure { error -> malExportMessage = error.message ?: "MAL export failed" }
            malExportBusy = false
        }
    }

    fun clearCache() {
        if (cacheClearing) return
        scope.launch {
            cacheClearing = true
            cacheMessage = null
            val before = cacheUsage ?: withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
            withContext(Dispatchers.IO) { CacheManager.clear(context) }
            val after = withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
            cacheUsage = after
            cacheMessage = "Freed ${Formatter.formatShortFileSize(context, (before - after).coerceAtLeast(0))}"
            cacheClearing = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            ScrollAwareTopBar { TopAppBar(
                title = {
                    Text(if (menuLanguage.usesSpanish()) "Ajustes" else "Settings", fontWeight = FontWeight.Black)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            ) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Scroll padding, not layout padding: rows travel under the bars instead of the list
            // being shunted about by them.
            contentPadding = PaddingValues(
                start = device.pagePadding,
                end = device.pagePadding,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + LocalAppChromeBottomInset.current + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            settingsSection(
                title = "Playback",
                expanded = "Playback" in expandedSections,
                onToggle = { toggleSection("Playback") },
            ) {
            item { SettingSwitch("Autoplay next episode", "Continue automatically", autoplay, SettingsStore::setAutoplay) }
            item { SettingSwitch("Auto-skip intro and outro", "Use provider skip times when available", autoSkip, SettingsStore::setAutoSkipIntroOutro) }
            item {
                SettingSwitch(
                    "Skip button",
                    "A one-press jump on the player for when a show has no intro timings",
                    showSkipButton,
                    SettingsStore::setShowSkipButton,
                )
            }
            if (showSkipButton) {
                item {
                    SkipButtonLengthSetting(
                        seconds = skipButtonSeconds,
                        onSelect = SettingsStore::setSkipButtonSeconds,
                    )
                }
            }
            item {
                DefaultQualitySetting(
                    selected = defaultQuality,
                    onSelect = SettingsStore::setDefaultQuality,
                )
            }
            item { SettingSwitch("Prefer dubbed audio", "Use dub first when available", preferDub, SettingsStore::setPreferDub) }
            item {
                SettingSwitch(
                    "Subtitles with dubbed audio",
                    "Show subtitles on dubbed episodes too (applies from the next episode)",
                    subtitlesWithDub,
                    SettingsStore::setSubtitlesWithDub,
                )
            }
            item {
                SettingSwitch(
                    "Player touch gestures",
                    "Swipe the left half for brightness, the right half for volume, across for " +
                        "seek. Tap to show the controls either way.",
                    playerGestures,
                    SettingsStore::setPlayerGestures,
                )
            }
            item {
                SettingsAction(
                    title = "Caption appearance",
                    icon = { Icon(Icons.Default.ClosedCaption, contentDescription = null) },
                    enabled = true,
                    onClick = { captionAppearanceVisible = true },
                )
            }
            }

            if (device.isTv) {
            settingsSection(
                title = "Accessibility",
                expanded = "Accessibility" in expandedSections,
                onToggle = { toggleSection("Accessibility") },
            ) {
                item {
                    SettingsAction(
                        title = "Spoken feedback: ${if (screenReaderActive) "On" else "Off"}",
                        icon = { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) },
                        enabled = true,
                        onClick = { openAccessibilitySettings(context) },
                    )
                }
                item {
                    Text(
                        "Off by default. Opens Android TV Accessibility settings for viewers who want narration.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            }

            settingsSection(
                title = "Servers",
                expanded = "Servers" in expandedSections,
                onToggle = { toggleSection("Servers") },
            ) {
            item {
                ServerPrioritySetting(
                    priority = serverPriority,
                    onChange = SettingsStore::setServerPriority,
                )
            }
            }

            settingsSection(
                title = "Shorts & YouTube",
                expanded = "Shorts & YouTube" in expandedSections,
                onToggle = { toggleSection("Shorts & YouTube") },
            ) {
            item {
                ShortsSettingsSection(
                    youtubeApiKey = youtubeApiKey,
                    shortsApiUrl = shortsApiUrl,
                    onApiKeyChange = SettingsStore::setYoutubeApiKey,
                    onApiUrlChange = SettingsStore::setShortsApiUrl,
                )
            }
            }

            settingsSection(
                title = "Downloads",
                expanded = "Downloads" in expandedSections,
                onToggle = { toggleSection("Downloads") },
            ) {
            item {
                DownloadQualitySetting(
                    selected = downloadQuality,
                    onSelect = SettingsStore::setDownloadQuality,
                )
            }
            item {
                DownloadDestinationSetting(
                    selected = downloadDestination,
                    deviceDownloadsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    onSelect = SettingsStore::setDownloadDestination,
                )
            }
            }

            settingsSection(
                title = "Content",
                expanded = "Content" in expandedSections,
                onToggle = { toggleSection("Content") },
            ) {
            item {
                SettingSwitch(
                    "Blur episode images",
                    "Hide possible spoilers. You can also tap any episode image to toggle this everywhere.",
                    blurEpisodeImages,
                    SettingsStore::setBlurEpisodeImages,
                )
            }
            item {
                SettingSwitch(
                    "Hide adult content",
                    "Keep hentai out of Home, Search, Browse, and Schedule",
                    hideAdultContent,
                    SettingsStore::setHideAdultContent,
                )
            }
            }

            settingsSection(
                title = "List sync (AniList / MyAnimeList)",
                expanded = "List sync (AniList / MyAnimeList)" in expandedSections,
                onToggle = { toggleSection("List sync (AniList / MyAnimeList)") },
            ) {
            item {
                SettingsAction(
                    title = if (token != null) "Reconnect AniList" else "Sign in to AniList",
                    description = when {
                        token != null -> "AniList is the active list service"
                        malLoggedIn -> "Switches list sync to AniList after sign-in succeeds"
                        else -> "Connect lists, scores, and episode progress"
                    },
                    icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                    enabled = true,
                    onClick = { loginService = AccountService.ANILIST },
                )
            }
            item {
                SettingsAction(
                    title = if (malLoggedIn) "Reconnect MyAnimeList" else "Sign in to MyAnimeList",
                    description = when {
                        malLoggedIn && token == null -> "MyAnimeList is the active list service"
                        token != null -> "Switches list sync to MyAnimeList after sign-in succeeds"
                        else -> "Connect lists, scores, and episode progress"
                    },
                    icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                    enabled = true,
                    onClick = { loginService = AccountService.MAL },
                )
            }
            item { SettingSwitch("Sync episode progress", "Update watched episodes while playing", autoSync, SettingsStore::setAutoSyncAniList) }
            item {
                SettingSwitch(
                    "Sync watchlist with Planning",
                    "Import Planning after login and add new saves without replacing active progress",
                    syncSavedToAniList,
                    ::setWatchlistSync,
                )
            }
            }

            settingsSection(
                title = "Notifications",
                expanded = "Notifications" in expandedSections,
                onToggle = { toggleSection("Notifications") },
            ) {
            item {
                SettingSwitch(
                    "Notification alerts",
                    "Logged in: AniList notifications; logged out: saved anime releases",
                    releaseNotifications,
                    ::setReleaseNotifications,
                )
            }
            }

            settingsSection(
                title = "Data",
                expanded = "Data" in expandedSections,
                onToggle = { toggleSection("Data") },
            ) {
            item {
                SettingsAction(
                    title = if (malExportBusy) "Preparing MyAnimeList export..." else "Export MyAnimeList XML",
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    enabled = !malExportBusy && ((token == null && !malLoggedIn) || profile != null),
                    onClick = ::exportMal,
                )
            }
            malExportMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                SettingsAction(
                    title = if (malImportBusy) "Cancel MyAnimeList XML import" else "Import MyAnimeList XML",
                    description = malImportProgress?.label,
                    icon = {
                        Icon(
                            if (malImportBusy) Icons.Default.Close else Icons.Default.Upload,
                            contentDescription = null,
                        )
                    },
                    enabled = true,
                    onClick = {
                        if (malImportBusy) {
                            malImportJob?.cancel()
                        } else {
                            malImportLauncher.launch(
                                arrayOf("text/xml", "application/xml", "application/gzip", "application/octet-stream", "*/*"),
                            )
                        }
                    },
                )
            }
            malImportMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                SettingsAction(
                    title = "Clear viewing history",
                    icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                    enabled = history.isNotEmpty(),
                    onClick = LibraryStore::clearHistory,
                )
            }
            item {
                SettingsAction(
                    title = cacheUsage.let { usage ->
                        when {
                            cacheClearing -> "Clearing cache..."
                            usage != null -> "Clear cache (${Formatter.formatShortFileSize(context, usage)})"
                            else -> "Clear cache"
                        }
                    },
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    enabled = !cacheClearing && (cacheUsage ?: 0L) > 0L,
                    onClick = ::clearCache,
                )
            }
            item {
                Text(
                    cacheMessage
                        ?: "Streamed video and images kept on this device for faster playback. The video cache auto-trims at 512 MB.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            }

            settingsSection(
                title = (if (menuLanguage.usesSpanish()) "Aplicación" else "App"),
                expanded = (if (menuLanguage.usesSpanish()) "Aplicación" else "App") in expandedSections,
                onToggle = { toggleSection((if (menuLanguage.usesSpanish()) "Aplicación" else "App")) },
            ) {
            item {
                MenuLanguageSetting(
                    selected = menuLanguage,
                    onSelect = SettingsStore::setMenuLanguage,
                )
            }
            item {
                SettingsAction(
                    title = "Send diagnostics",
                    description = if (BuildConfig.DIAGNOSTICS_UPLOAD_URL.isNotBlank()) {
                        "Uploads a full debugging report. Passwords, cookies, tokens and sensitive links are removed."
                    } else {
                        "Temporarily unavailable while the private diagnostics service is being activated."
                    },
                    icon = { Icon(Icons.Default.Upload, contentDescription = null) },
                    enabled = !diagnosticsBusy && BuildConfig.DIAGNOSTICS_UPLOAD_URL.isNotBlank(),
                    onClick = {
                        diagnosticsError = null
                        diagnosticsDialogVisible = true
                    },
                )
            }
            diagnosticsMessage?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                SettingSwitch(
                    "Check for updates on launch",
                    "Prompt when a new version is available each time the app opens",
                    updateCheckOnLaunch,
                    SettingsStore::setUpdateCheckOnLaunch,
                )
            }
            item {
                SettingsAction(
                    title = when (updateState) {
                        is UpdateManager.State.Checking -> "Checking for updates..."
                        is UpdateManager.State.Downloading -> "Downloading update..."
                        else -> "Check for updates"
                    },
                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                    enabled = updateState !is UpdateManager.State.Checking &&
                        updateState !is UpdateManager.State.Downloading,
                    onClick = { UpdateManager.check(context, manual = true) },
                )
            }
            item {
                Text(
                    when (val state = updateState) {
                        is UpdateManager.State.UpToDate -> {
                            if (state.latestPublishedVersion == UpdateManager.currentVersion) {
                                "You're on the latest published version (v${UpdateManager.currentVersion})"
                            } else {
                                "Installed v${UpdateManager.currentVersion} · published v${state.latestPublishedVersion}"
                            }
                        }
                        else -> "Version ${UpdateManager.currentVersion}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            }

            settingsSection(
                title = "About",
                expanded = "About" in expandedSections,
                onToggle = { toggleSection("About") },
            ) {
            item {
                SettingsAction(
                    title = "Share Anilili with friends",
                    icon = { Icon(Icons.Default.Share, contentDescription = null) },
                    enabled = true,
                    onClick = { shareAnilili(context, openWebsite = device.isTv) },
                )
            }
            item {
                SettingsAction(
                    title = "Join Telegram Group",
                    icon = { Icon(painterResource(R.drawable.ic_telegram), contentDescription = null) },
                    enabled = true,
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/anililiapk"))
                            context.startActivity(intent)
                        }
                    },
                )
            }
            item {
                SettingsAction(
                    title = "GitHub Repository",
                    icon = { Icon(painterResource(R.drawable.ic_github), contentDescription = null) },
                    enabled = true,
                    onClick = {
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/kompoti121/anilili"))
                            context.startActivity(intent)
                        }
                    },
                )
            }
            }
        }
    }
}

private const val ANILILI_WEBSITE = "https://kompoti121.github.io/Anilili/"

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }.onFailure {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }
}

private fun shareAnilili(context: Context, openWebsite: Boolean) {
    val website = Uri.parse(ANILILI_WEBSITE)
    if (openWebsite) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, website)) }
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Anilili anime app")
        putExtra(
            Intent.EXTRA_TEXT,
            "Try Anilili for anime on Android and Android TV: $ANILILI_WEBSITE",
        )
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share Anilili"))
    }.onFailure {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, website)) }
    }
}

/**
 * Server priority as a single ranked picker, the way a photo grid numbers its selection.
 *
 * Tapping an unpicked server appends it and it takes the next number; tapping a picked one removes
 * it and everything behind closes the gap. Three separate "preferred / first fallback / second
 * fallback" dropdowns expressed the same thing but let a user leave a hole in the middle, and gave
 * no single place to read the resulting order off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerPrioritySetting(
    priority: List<String>,
    onChange: (List<String>) -> Unit,
) {
    val hideAdult by SettingsStore.hideAdultContent.collectAsState()
    val servers = remember(hideAdult) { ProviderCatalog.selectableProviders(hideAdult) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            "Server priority",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (priority.isEmpty()) {
                "Pick up to $MAX_SERVER_PRIORITY servers in the order you want them tried. " +
                    "With none picked, the built-in order is used."
            } else {
                priority.mapIndexed { index, server ->
                    "${index + 1}. ${ProviderCatalog.label(server)}"
                }.joinToString("   ")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            servers.forEach { server ->
                val rank = priority.indexOf(server)
                val picked = rank >= 0
                val full = priority.size >= MAX_SERVER_PRIORITY
                FilterChip(
                    selected = picked,
                    // A full list still lets you tap a picked chip, so the only way out is not
                    // "clear everything" — you drop the one you no longer want.
                    enabled = picked || !full,
                    onClick = {
                        onChange(
                            if (picked) priority.filterNot { it == server } else priority + server,
                        )
                    },
                    label = { Text(ProviderCatalog.label(server)) },
                    leadingIcon = if (picked) {
                        { Text("${rank + 1}", fontWeight = FontWeight.Bold) }
                    } else {
                        null
                    },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
        if (priority.isNotEmpty()) {
            TextButton(
                onClick = { onChange(emptyList()) },
                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
            ) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun ShortsSettingsSection(
    youtubeApiKey: String,
    shortsApiUrl: String,
    onApiKeyChange: (String) -> Unit,
    onApiUrlChange: (String) -> Unit,
) {
    var keyText by remember(youtubeApiKey) { mutableStateOf(youtubeApiKey) }
    var urlText by remember(shortsApiUrl) { mutableStateOf(shortsApiUrl) }

    val scope = rememberCoroutineScope()
    var connectionStatus by remember { mutableStateOf<ConnectionStatus?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    fun testConnectionNow(url: String, key: String) {
        val cleanUrl = url.trim()
        val cleanKey = key.trim()
        if (cleanUrl.isBlank()) {
            connectionStatus = ConnectionStatus(isConnected = false, message = "Shorts API URL is empty.")
            return
        }
        isTesting = true
        connectionStatus = null
        scope.launch {
            val res = AppGraph.shortsRepository.testConnection(cleanUrl, cleanKey)
            isTesting = false
            connectionStatus = res
        }
    }

    LaunchedEffect(Unit) {
        if (shortsApiUrl.isNotBlank()) {
            testConnectionNow(shortsApiUrl, youtubeApiKey)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                "YouTube Data API Key",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Required for discovering anime Shorts. Saved only on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            androidx.compose.material3.OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("AIzaSy...") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
        }

        Column {
            Text(
                "Shorts API Service URL",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Endpoint of your Anilili Shorts backend service (default http://10.0.2.2:8000 on emulator).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            androidx.compose.material3.OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("http://10.0.2.2:8000") },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    val cleanKey = keyText.trim()
                    val cleanUrl = urlText.trim()
                    onApiKeyChange(cleanKey)
                    onApiUrlChange(cleanUrl)
                    testConnectionNow(cleanUrl, cleanKey)
                },
                enabled = !isTesting,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isTesting) "Testing..." else "Apply & Test Connection")
            }
        }

        val currentStatus = connectionStatus
        when {
            isTesting -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Testing connection to backend service...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            currentStatus != null -> {
                val isOk = currentStatus.isConnected
                val bgColor = if (isOk) Color(0xFF1B5E20).copy(alpha = 0.25f) else Color(0xFFB71C1C).copy(alpha = 0.25f)
                val textColor = if (isOk) Color(0xFF81C784) else Color(0xFFE57373)
                val icon = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(bgColor)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isOk) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        )
                        Text(
                            text = currentStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.9f),
                        )
                    }
                }
            }
            keyText.isBlank() || urlText.isBlank() -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Enter API Key & Shorts API URL above, then tap Apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DefaultQualitySetting(
    selected: DefaultQuality,
    onSelect: (DefaultQuality) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            "Default quality",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Picked automatically when an episode starts. Data Saver caps playback at 360p " +
                "when possible and otherwise uses the closest available low resolution.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // Quality chips outgrow a single row on narrow portrait widths, so let them wrap.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            DefaultQuality.entries.forEach { quality ->
                FilterChip(
                    selected = selected == quality,
                    onClick = { onSelect(quality) },
                    label = { Text(quality.label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

/**
 * How far the player's skip button jumps.
 *
 * Presets cover the openings people actually meet; the steppers exist because a remote cannot
 * drag a slider and a viewer who wants 95s should not have to settle for 90s.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipButtonLengthSetting(
    seconds: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            "Skip button length",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Jumps forward ${seconds}s. A standard TV anime opening is 85 seconds.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            SKIP_BUTTON_PRESETS.forEach { preset ->
                FilterChip(
                    selected = seconds == preset,
                    onClick = { onSelect(preset) },
                    label = { Text("${preset}s") },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
            AssistChip(
                onClick = { onSelect(seconds - 5) },
                enabled = seconds > SKIP_BUTTON_MIN_SECONDS,
                label = { Text("−5s") },
                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
            )
            AssistChip(
                onClick = { onSelect(seconds + 5) },
                enabled = seconds < SKIP_BUTTON_MAX_SECONDS,
                label = { Text("+5s") },
                modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
            )
        }
    }
}

private val SKIP_BUTTON_PRESETS = listOf(30, 60, 85, 90, 120)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadQualitySetting(
    selected: DownloadQuality,
    onSelect: (DownloadQuality) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            "Default download resolution",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Limits the saved HLS rendition; direct video files keep their source resolution",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            DownloadQuality.entries.forEach { quality ->
                FilterChip(
                    selected = selected == quality,
                    onClick = { onSelect(quality) },
                    label = { Text(quality.label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadDestinationSetting(
    selected: DownloadDestination,
    deviceDownloadsSupported: Boolean,
    onSelect: (DownloadDestination) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            "Default download destination",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (deviceDownloadsSupported) {
                "Device Downloads rewraps each episode into one MP4 under Downloads/Anilili and " +
                    "drops the cached copy. Both keeps the cached copy as well, at roughly double " +
                    "the storage."
            } else {
                "Public device downloads require Android 10 or newer, so episodes stay in Anilili."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            // The stored default is Device Downloads, which is unreachable before Android 10.
            // Without this the old device shows every chip unselected, since the stored value maps
            // to a disabled chip — downloads still behave (the watch screen forces the library),
            // but the control looks broken.
            val effectiveSelection = if (deviceDownloadsSupported) selected else DownloadDestination.APP_ONLY
            DownloadDestination.entries.forEach { destination ->
                val enabled = deviceDownloadsSupported || destination == DownloadDestination.APP_ONLY
                FilterChip(
                    selected = effectiveSelection == destination && enabled,
                    onClick = { onSelect(destination) },
                    enabled = enabled,
                    label = { Text(destination.label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@Composable
private fun MenuLanguageSetting(
    selected: MenuLanguage,
    onSelect: (MenuLanguage) -> Unit,
) {
    val spanish = selected.usesSpanish()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            if (spanish) "Idioma del menú" else "Menu language",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            if (spanish) "Cambia las etiquetas de navegación principales" else "Changes the main navigation labels",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            MenuLanguage.entries.forEach { language ->
                val label = when (language) {
                    MenuLanguage.SYSTEM -> if (spanish) "Sistema" else "System"
                    MenuLanguage.ENGLISH -> if (spanish) "Inglés" else "English"
                    MenuLanguage.SPANISH -> "Español"
                }
                FilterChip(
                    selected = selected == language,
                    onClick = { onSelect(language) },
                    label = { Text(label) },
                    modifier = Modifier.focusHighlight(RoundedCornerShape(20.dp)),
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Black,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
    )
}

/**
 * A collapsible settings group: a focusable header that toggles, and rows emitted only while open.
 *
 * The rows stay [LazyListScope] items rather than moving inside a Column, so a long section still
 * only composes what is on screen. Collapsing simply stops emitting them, which also means a
 * D-pad user never tabs through rows they cannot see.
 */
private fun LazyListScope.settingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    item(key = "section-$title") { SettingsSectionHeader(title, expanded, onToggle) }
    if (expanded) content()
    item(key = "divider-$title") { SectionDivider() }
}

@Composable
private fun SettingsSectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "section-chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // No scale on focus: these rows span the full width, and TV's 6% zoom pushed the
            // title off the left edge and the chevron off the right. The border alone is a clear
            // enough affordance for a row this size.
            .focusHighlight(RoundedCornerShape(10.dp), focusedScale = 1f)
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation),
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange, Modifier.focusProperties { canFocus = false })
    }
}

@Composable
private fun SettingsAction(
    title: String,
    description: String? = null,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().focusHighlight(RoundedCornerShape(8.dp)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        icon()
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Text(title)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Context.malImportReportedSize(uri: Uri): Long? =
    runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column).takeIf { it >= 0L }
        }
    }.getOrNull()

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 10.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = .7f),
    )
}
