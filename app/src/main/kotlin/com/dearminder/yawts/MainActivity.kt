package com.dearminder.yawts

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Permissions ----------

/** All-files access on Android 11+; the classic storage permission before that. */
private fun isStorageGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** Notifications need an explicit runtime permission from Android 13 onward. */
private fun isNotificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openNotificationSettings(context: Context) {
    try {
        context.startActivity(Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName))
    } catch (e: Exception) {
        context.startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

private enum class Screen { STORAGE, NOTIFICATIONS, MAIN, SETTINGS }

class MainActivity : ComponentActivity() {

    // Bumped on every resume (and permission result), so permission state is re-checked after
    // the user comes back from a system settings screen or dialog.
    private val resumeTick = mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick.intValue++ }
    private val legacyStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumeTick.intValue++ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(
                resumeTick = resumeTick.intValue,
                requestStorage = ::requestStorage,
                requestNotifications = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(SystemSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }
}

@Composable
private fun App(resumeTick: Int, requestStorage: () -> Unit, requestNotifications: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(AppSettings.load(context)) }
    val update: (AppSettings) -> Unit = { settings = it; it.save(context) }

    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Remembered: settings change on every keystroke in a text field, and a new scheme would redraw the whole UI.
    val colorScheme = remember(dark, settings.dynamicColor) { appColorScheme(context, dark, settings.dynamicColor) }

    // Status/navigation bar icons follow the app's theme, not just the system's.
    LaunchedEffect(dark) {
        val window = (context as ComponentActivity).window
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    val storageGranted = remember(resumeTick) { isStorageGranted(context) }
    val notificationsGranted = remember(resumeTick) { isNotificationsGranted(context) }
    var screen by rememberSaveable {
        mutableStateOf(
            when {
                !storageGranted -> Screen.STORAGE
                !notificationsGranted -> Screen.NOTIFICATIONS
                else -> Screen.MAIN
            }
        )
    }
    // Onboarding moves on by itself as soon as a permission is granted.
    LaunchedEffect(storageGranted, notificationsGranted, screen) {
        if (screen == Screen.STORAGE && storageGranted) screen = if (notificationsGranted) Screen.MAIN else Screen.NOTIFICATIONS
        if (screen == Screen.NOTIFICATIONS && notificationsGranted) screen = Screen.MAIN
    }
    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.MAIN }
    LaunchedEffect(Unit) { if (settings.checkUpdates) Updates.checkAtStartup(context) }
    val updateState by Updates.state.collectAsState()

    MaterialTheme(colorScheme = colorScheme) {
        // Going deeper (onboarding → main → settings) slides in from the right, going back from the left.
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(SCREEN_ANIMATION_MS)) { width -> direction * width / 3 } + fadeIn(tween(SCREEN_ANIMATION_MS))) togetherWith
                    (slideOutHorizontally(tween(SCREEN_ANIMATION_MS)) { width -> -direction * width / 3 } + fadeOut(tween(SCREEN_ANIMATION_MS / 2)))
            },
            label = "screen"
        ) { target ->
        when (target) {
            Screen.STORAGE -> OnboardingScreen(
                title = stringResource(R.string.onboarding_storage_title),
                description = stringResource(R.string.onboarding_storage_description),
                buttonLabel = stringResource(R.string.onboarding_grant_access),
                onButtonClick = requestStorage
            )
            Screen.NOTIFICATIONS -> OnboardingScreen(
                title = stringResource(R.string.onboarding_notifications_title),
                description = stringResource(R.string.onboarding_notifications_description),
                buttonLabel = stringResource(R.string.onboarding_enable_notifications),
                onButtonClick = requestNotifications,
                // Without this, someone who declines (or can no longer be asked) would be stuck here.
                onSkip = { screen = Screen.MAIN }
            )
            Screen.MAIN -> MainScreen(settings, resumeTick, onOpenSettings = { screen = Screen.SETTINGS })
            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                onChange = update,
                dynamicColorAvailable = dynamicColorAvailable,
                notificationsGranted = notificationsGranted,
                onBack = { screen = Screen.MAIN }
            )
        }
        }
        when (val s = updateState) {
            is UpdateState.Available -> UpdateDialog(s.release)
            is UpdateState.Downloading -> UpdateDialog(s.release, downloading = true, progress = s.progress)
            is UpdateState.DownloadFailed -> UpdateDialog(s.release, failed = true)
            else -> {}
        }
    }
}

private const val SCREEN_ANIMATION_MS = 300

// ---------- Onboarding ----------

@Composable
private fun OnboardingScreen(
    title: String,
    description: String,
    buttonLabel: String,
    onButtonClick: () -> Unit,
    onSkip: (() -> Unit)? = null
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onButtonClick, modifier = Modifier.fillMaxWidth()) { Text(buttonLabel) }
            if (onSkip != null) {
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_skip)) }
            }
        }
    }
}

// ---------- Main screen ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(settings: AppSettings, resumeTick: Int, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val running by ServerService.running.collectAsState()
    val error by ServerService.error.collectAsState()

    // Re-read on start/stop and whenever the app comes back to the foreground (network may have changed).
    var ip by remember { mutableStateOf<String?>(null) }
    var ipChecked by remember { mutableStateOf(false) }
    LaunchedEffect(running, resumeTick) {
        ip = if (running != null) withContext(Dispatchers.IO) { localIpAddress() } else null
        ipChecked = true
    }

    val server = running
    val serverUrl = if (server != null && ip != null) "${if (server.secure) "https" else "http"}://$ip:${server.port}" else null
    // A Windows UNC path needs a share name after the host, so the drive is also served under /webdav.
    val windowsPath = if (server != null && ip != null && settings.webDavEnabled) {
        "\\\\$ip@${if (server.secure) "SSL@" else ""}${server.port}\\webdav"
    } else null

    val certStamp = remember(resumeTick) { CustomCertificate.stamp(context) }
    val name = remember(settings.deviceName) { resolveDeviceName(context, settings.deviceName) }
    val needsRestart = server != null && (
        settings.portNumber != server.port || settings.https != server.httpsRequested ||
            (settings.https && certStamp != server.certStamp) ||
            settings.discovery != server.discovery ||
            (settings.discovery && name != server.name)
        )
    val shareTitle = stringResource(R.string.share_chooser_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (serverUrl != null) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, serverUrl).setType("text/plain")
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        }) {
                            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.cd_share), Modifier.size(26.dp))
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.cd_settings), Modifier.size(26.dp))
                    }
                }
            )
        }
    ) { padding ->
        val details: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                error?.let { StatusText(it, isError = true) }
                if (server != null) {
                    if (serverUrl != null) {
                        LinkCard(serverUrl, stringResource(R.string.server_link_label))
                        windowsPath?.let { LinkCard(it, stringResource(R.string.webdav_link_label)) }
                    } else if (ipChecked) {
                        StatusText(stringResource(R.string.error_no_ip), isError = true)
                    }
                    if (server.httpsRequested && !server.secure) StatusText(stringResource(R.string.error_https_failed), isError = true)
                    if (needsRestart) StatusText(stringResource(R.string.restart_needed), isError = false)
                } else if (error == null) {
                    StatusText(stringResource(R.string.main_hint), isError = false)
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            val width = maxWidth
            val height = maxHeight
            if (width > height) {
                // Landscape: button on the left, addresses on the right.
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        PowerButton(server != null, minOf(height * 0.8f, width * 0.4f))
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(modifier = Modifier.weight(1f)) { details() }
                }
            } else {
                // The bottom area is reserved up front, so the button stays put when the
                // address cards appear under it (previously it jumped up once the IP was known).
                val buttonArea = (height - DETAILS_AREA).coerceAtLeast(0.dp)
                Box(modifier = Modifier.fillMaxWidth().height(buttonArea), contentAlignment = Alignment.Center) {
                    PowerButton(server != null, minOf(width * 0.75f, buttonArea * 0.85f))
                }
                Box(modifier = Modifier.align(Alignment.BottomCenter)) { details() }
            }
        }
    }
}

private val DETAILS_AREA = 200.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerButton(isRunning: Boolean, size: Dp) {
    val context = LocalContext.current
    Surface(
        onClick = { if (isRunning) ServerService.stop(context) else ServerService.start(context) },
        shape = CircleShape,
        color = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (isRunning) R.drawable.ic_stop else R.drawable.ic_play_arrow),
                contentDescription = stringResource(if (isRunning) R.string.cd_stop_server else R.string.cd_start_server),
                tint = if (isRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.48f)
            )
        }
    }
}

@Composable
private fun StatusText(text: String, isError: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkCard(url: String, label: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copied = stringResource(R.string.toast_copied)
    Surface(
        onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, url))) }
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(painterResource(R.drawable.ic_copy), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(url, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

// ---------- Settings ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    dynamicColorAvailable: Boolean,
    notificationsGranted: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---------- Language ----------
            SectionHeader(stringResource(R.string.settings_section_language), painterResource(R.drawable.ic_language))
            val systemLabel = stringResource(R.string.language_system)
            DropdownSetting(
                label = stringResource(R.string.settings_section_language),
                options = listOf<String?>(null) + SUPPORTED_LANGUAGES,
                selected = remember { appLanguage(context) },
                optionLabel = { tag -> if (tag == null) systemLabel else languageDisplayName(tag) },
                onSelect = { tag -> setAppLanguage(activity, tag) }
            )

            // ---------- Notifications ----------
            SectionHeader(stringResource(R.string.settings_section_notifications), painterResource(R.drawable.ic_notifications))
            Text(
                stringResource(if (notificationsGranted) R.string.notifications_granted else R.string.notifications_not_granted),
                style = MaterialTheme.typography.bodyMedium,
                color = if (notificationsGranted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openNotificationSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.notifications_open_settings))
            }

            // ---------- Appearance ----------
            SectionHeader(stringResource(R.string.settings_section_appearance), painterResource(R.drawable.ic_palette))
            val themeLabels = mapOf(
                ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                ThemeMode.LIGHT to stringResource(R.string.theme_light),
                ThemeMode.DARK to stringResource(R.string.theme_dark)
            )
            DropdownSetting(
                label = stringResource(R.string.settings_theme_label),
                options = ThemeMode.entries,
                selected = settings.themeMode,
                optionLabel = { themeLabels.getValue(it) },
                onSelect = { onChange(settings.copy(themeMode = it)) }
            )
            if (dynamicColorAvailable) {
                SwitchRow(
                    label = stringResource(R.string.settings_dynamic_color_label),
                    helper = stringResource(R.string.settings_dynamic_color_helper),
                    checked = settings.dynamicColor,
                    onCheckedChange = { onChange(settings.copy(dynamicColor = it)) }
                )
            }

            // ---------- Date format ----------
            SectionHeader(stringResource(R.string.settings_section_date), painterResource(R.drawable.ic_date_range))
            val now = remember { Date() }
            val formats = remember(settings.dateFormat) {
                if (settings.dateFormat in DATE_FORMATS) DATE_FORMATS else listOf(settings.dateFormat) + DATE_FORMATS
            }
            DropdownSetting(
                label = stringResource(R.string.settings_date_format_label),
                options = formats,
                selected = settings.dateFormat,
                optionLabel = { pattern ->
                    try { SimpleDateFormat(pattern, Locale.getDefault()).format(now) } catch (e: Exception) { pattern }
                },
                onSelect = { onChange(settings.copy(dateFormat = it)) }
            )

            // ---------- Server ----------
            SectionHeader(stringResource(R.string.settings_section_server), painterResource(R.drawable.ic_dns))
            DeviceNameSetting(settings.deviceName) { onChange(settings.copy(deviceName = it)) }
            val portValid = settings.portNumber != null
            OutlinedTextField(
                value = settings.port,
                onValueChange = { new -> onChange(settings.copy(port = new.filter { it.isDigit() }.take(5))) },
                label = { Text(stringResource(R.string.port_label)) },
                isError = !portValid,
                supportingText = {
                    Text(if (portValid) stringResource(R.string.port_helper) else stringResource(R.string.port_invalid, AppSettings.MIN_PORT, AppSettings.MAX_PORT))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = settings.password,
                onValueChange = { onChange(settings.copy(password = it)) },
                label = { Text(stringResource(R.string.password_label)) },
                supportingText = { Text(stringResource(R.string.password_helper)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(stringResource(if (passwordVisible) R.string.password_hide else R.string.password_show))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            SwitchRow(
                label = stringResource(R.string.https_switch_label),
                helper = stringResource(R.string.https_helper),
                checked = settings.https,
                onCheckedChange = { onChange(settings.copy(https = it)) }
            )
            if (settings.https) CertificateSetting()
            SwitchRow(
                label = stringResource(R.string.thumbnails_label),
                helper = stringResource(R.string.thumbnails_helper),
                checked = settings.thumbnails,
                onCheckedChange = { onChange(settings.copy(thumbnails = it)) }
            )

            // ---------- WebDAV ----------
            SectionHeader(stringResource(R.string.settings_section_webdav), painterResource(R.drawable.ic_language))
            SwitchRow(
                label = stringResource(R.string.settings_webdav_enable_label),
                helper = null,
                checked = settings.webDavEnabled,
                onCheckedChange = { onChange(settings.copy(webDavEnabled = it)) }
            )
            SwitchRow(
                label = stringResource(R.string.discovery_label),
                helper = stringResource(R.string.discovery_helper),
                checked = settings.discovery,
                onCheckedChange = { onChange(settings.copy(discovery = it)) }
            )

            // ---------- Security ----------
            SectionHeader(stringResource(R.string.settings_section_security), painterResource(R.drawable.ic_lock))
            SwitchRow(
                label = stringResource(R.string.settings_allow_delete_label),
                helper = stringResource(R.string.settings_allow_delete_helper),
                checked = settings.allowWebDelete,
                onCheckedChange = { onChange(settings.copy(allowWebDelete = it)) }
            )

            // ---------- Updates ----------
            SectionHeader(stringResource(R.string.settings_section_updates), painterResource(R.drawable.ic_system_update))
            SwitchRow(
                label = stringResource(R.string.update_auto_label),
                helper = stringResource(R.string.update_auto_helper),
                checked = settings.checkUpdates,
                onCheckedChange = { onChange(settings.copy(checkUpdates = it)) }
            )
            UpdateCheckRow()

            // ---------- Developer options (unlocked from the panel below) ----------
            if (settings.devOptions) {
                SectionHeader(stringResource(R.string.settings_section_developer), painterResource(R.drawable.ic_code))
                SwitchRow(
                    label = stringResource(R.string.dev_crash_reports_label),
                    helper = stringResource(R.string.dev_crash_reports_helper),
                    checked = settings.crashReports,
                    onCheckedChange = { onChange(settings.copy(crashReports = it)) }
                )
                SwitchRow(
                    label = stringResource(R.string.dev_server_logs_label),
                    helper = stringResource(R.string.dev_server_logs_helper),
                    checked = settings.serverLogs,
                    onCheckedChange = { onChange(settings.copy(serverLogs = it)) }
                )
                Text(
                    stringResource(R.string.dev_logs_location, DevTools.logDir.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { onChange(settings.copy(devOptions = false, crashReports = false, serverLogs = false)) }) {
                    Text(stringResource(R.string.dev_hide))
                }
            }

            Spacer(Modifier.height(24.dp))
            AboutPanel(unlocked = settings.devOptions, onUnlock = { onChange(settings.copy(devOptions = true)) })
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Logo and version. Tapping it [DEV_TAPS] times unlocks the developer options, like Android's build number. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutPanel(unlocked: Boolean, onUnlock: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    // The launcher icon is an adaptive icon, which painterResource can't draw - render it once to a bitmap.
    val logo = remember {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(LOGO_PX, LOGO_PX).asImageBitmap()
    }
    var taps by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    fun show(text: String) {
        toast?.cancel() // rapid taps would otherwise queue a toast each
        toast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    Surface(
        onClick = {
            val left = DEV_TAPS - (taps + 1)
            when {
                unlocked -> show(context.getString(R.string.dev_already_unlocked))
                left <= 0 -> { taps = 0; onUnlock(); show(context.getString(R.string.dev_unlocked)) }
                else -> {
                    taps++
                    if (left <= DEV_TAPS_HINT_FROM) show(context.resources.getQuantityString(R.plurals.dev_taps_left, left, left))
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Image(logo, contentDescription = null, modifier = Modifier.size(56.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.about_version, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { openUrl(context, GITHUB_URL) }) {
                Icon(painterResource(R.drawable.ic_github), contentDescription = stringResource(R.string.cd_github))
            }
        }
    }
}

/** Where the last update check got to, and a button to check again. */
@Composable
private fun UpdateCheckRow() {
    val context = LocalContext.current
    val state by Updates.state.collectAsState()
    val version = remember { appVersion(context) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val (status, isError) = when (val s = state) {
            UpdateState.Idle -> stringResource(R.string.update_installed, version) to false
            UpdateState.Checking -> stringResource(R.string.update_checking) to false
            UpdateState.UpToDate -> stringResource(R.string.update_up_to_date) to false
            UpdateState.CheckFailed -> stringResource(R.string.update_check_failed) to true
            is UpdateState.Available -> stringResource(R.string.update_available_short, s.release.version) to false
            is UpdateState.Downloading -> stringResource(R.string.update_downloading) to false
            is UpdateState.DownloadFailed -> stringResource(R.string.update_download_failed) to true
        }
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        FilledTonalButton(
            onClick = { Updates.check(context) },
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading
        ) {
            Text(stringResource(R.string.update_check))
        }
    }
}

/** Offers a newer release: its notes, then download and install - or its page, if it has no APK. */
@Composable
private fun UpdateDialog(release: Release, downloading: Boolean = false, progress: Float? = null, failed: Boolean = false) {
    val context = LocalContext.current
    val current = remember { appVersion(context) }
    AlertDialog(
        onDismissRequest = { if (!downloading) Updates.dismiss() },
        icon = { Icon(painterResource(R.drawable.ic_system_update), contentDescription = null) },
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.update_available_text, release.version, current))
                if (release.notes.isNotEmpty()) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                    )
                }
                if (downloading) {
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (failed) Text(stringResource(R.string.update_download_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (release.apkUrl != null) {
                TextButton(onClick = { Updates.install(context, release) }, enabled = !downloading) {
                    Text(stringResource(if (downloading) R.string.update_downloading else R.string.update_install))
                }
            } else {
                TextButton(onClick = { openUrl(context, release.pageUrl); Updates.dismiss() }) {
                    Text(stringResource(R.string.update_open_page))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { Updates.dismiss() }, enabled = !downloading) { Text(stringResource(R.string.update_later)) }
        }
    )
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

private const val DEV_TAPS = 8
private const val DEV_TAPS_HINT_FROM = 4
private const val LOGO_PX = 192

/** Shows which certificate HTTPS uses and lets the user load their own .p12/.pfx. */
@Composable
private fun CertificateSetting() {
    val context = LocalContext.current
    var info by remember { mutableStateOf(CustomCertificate.info(context)) }
    var pendingFile by remember { mutableStateOf<ByteArray?>(null) }
    val readFailed = stringResource(R.string.cert_read_failed)
    val loaded = stringResource(R.string.cert_loaded)

    // "*/*" because file managers label .p12/.pfx files inconsistently; the content is validated anyway.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input -> input.readBytes().takeIf { it.size <= MAX_CERT_BYTES } }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) Toast.makeText(context, readFailed, Toast.LENGTH_LONG).show() else pendingFile = bytes
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(R.string.cert_label))
        val current = info
        Text(
            if (current == null) stringResource(R.string.cert_generated)
            else stringResource(R.string.cert_custom, current.subject, DateFormat.getDateInstance(DateFormat.MEDIUM).format(current.notAfter)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.cert_load)) }
            if (current != null) {
                TextButton(onClick = { CustomCertificate.remove(context); info = null }) { Text(stringResource(R.string.cert_remove)) }
            }
        }
        Text(stringResource(R.string.cert_helper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    pendingFile?.let { bytes ->
        CertificatePasswordDialog(
            onDismiss = { pendingFile = null },
            onConfirm = { password ->
                try {
                    info = CustomCertificate.save(context, bytes, password)
                    pendingFile = null
                    Toast.makeText(context, loaded, Toast.LENGTH_LONG).show()
                    true
                } catch (e: Exception) {
                    false
                }
            }
        )
    }
}

private const val MAX_CERT_BYTES = 1 shl 20

/** Asks for the .p12 password; [onConfirm] returns false if the certificate didn't open. */
@Composable
private fun CertificatePasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Boolean) {
    var password by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cert_password_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; failed = false },
                label = { Text(stringResource(R.string.password_label)) },
                isError = failed,
                supportingText = if (failed) { { Text(stringResource(R.string.cert_invalid)) } } else null,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { failed = !onConfirm(password) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun SectionHeader(title: String, icon: Painter, topPadding: Dp = 24.dp) {
    Spacer(Modifier.height(topPadding))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(8.dp))
}

/** Free text with placeholders; the helper lists them with what each one stands for on this phone. */
@Composable
private fun DeviceNameSetting(template: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val resolved = remember(template) { resolveDeviceName(context, template) }
    val placeholders = remember { DEVICE_NAME_PLACEHOLDERS.joinToString("\n") { "$it → ${resolveDeviceName(context, it)}" } }
    OutlinedTextField(
        value = template,
        onValueChange = { onChange(it.take(MAX_DEVICE_NAME)) },
        label = { Text(stringResource(R.string.device_name_label)) },
        supportingText = { Text(stringResource(R.string.device_name_preview, resolved)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        stringResource(R.string.device_name_helper) + "\n" + placeholders,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 8.dp)
    )
}

private const val MAX_DEVICE_NAME = 60

@Composable
private fun SwitchRow(label: String, helper: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.padding(start = 8.dp))
        }
        if (helper != null) {
            Text(helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A read-only field that opens a dropdown list of [options] when tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownSetting(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { fieldWidth = it.width }) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, modifier = Modifier.rotate(if (expanded) 180f else 0f))
            },
            modifier = Modifier.fillMaxWidth()
        )
        // A text field consumes taps for focus handling, so a transparent layer on top opens the menu.
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(with(density) { fieldWidth.toDp() })
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            optionLabel(option),
                            fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        expanded = false
                        if (option != selected) onSelect(option)
                    }
                )
            }
        }
    }
}
