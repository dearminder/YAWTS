package com.dearminder.yawts

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.UUID

// ---------- Persisted settings (shared by the UI and the server service) ----------

object Prefs {
    const val NAME = "yawts_prefs"
    const val PORT = "port"
    const val PASSWORD = "password"
    const val HTTPS = "https"
    const val THEME_MODE = "theme_mode"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val ALLOW_WEB_DELETE = "allow_web_delete"
    const val WEBDAV_ENABLED = "webdav_enabled"
    const val DATE_FORMAT = "date_format"
    const val LANGUAGE_TAG = "language_tag"
    const val CERT_PASSWORD = "custom_cert_password"
    const val DEVICE_NAME = "device_name"
    const val DEVICE_ID = "device_uuid"
    const val DISCOVERY = "discovery_enabled"
    const val THUMBNAILS = "thumbnails_enabled"
    const val DEV_OPTIONS = "dev_options_unlocked"
    const val CRASH_REPORTS = "dev_crash_reports"
    const val SERVER_LOGS = "dev_server_logs"
    const val CHECK_UPDATES = "check_updates"

    const val DEFAULT_PORT = 8080
    const val DEFAULT_DATE_FORMAT = "dd-MM-yyyy HH:mm"
    const val DEFAULT_DEVICE_NAME = "%name%"
}

fun Context.appPrefs(): SharedPreferences = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val port: String,
    val password: String,
    val https: Boolean,
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val allowWebDelete: Boolean,
    val webDavEnabled: Boolean,
    val dateFormat: String,
    val deviceName: String,
    val discovery: Boolean,
    val thumbnails: Boolean,
    val devOptions: Boolean,
    val crashReports: Boolean,
    val serverLogs: Boolean,
    val checkUpdates: Boolean
) {
    val portNumber: Int? get() = port.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }

    fun save(context: Context) {
        context.appPrefs().edit()
            .putString(Prefs.PORT, port)
            .putString(Prefs.PASSWORD, password)
            .putBoolean(Prefs.HTTPS, https)
            .putInt(Prefs.THEME_MODE, themeMode.ordinal)
            .putBoolean(Prefs.DYNAMIC_COLOR, dynamicColor)
            .putBoolean(Prefs.ALLOW_WEB_DELETE, allowWebDelete)
            .putBoolean(Prefs.WEBDAV_ENABLED, webDavEnabled)
            .putString(Prefs.DATE_FORMAT, dateFormat)
            .putString(Prefs.DEVICE_NAME, deviceName)
            .putBoolean(Prefs.DISCOVERY, discovery)
            .putBoolean(Prefs.THUMBNAILS, thumbnails)
            .putBoolean(Prefs.DEV_OPTIONS, devOptions)
            .putBoolean(Prefs.CRASH_REPORTS, crashReports)
            .putBoolean(Prefs.SERVER_LOGS, serverLogs)
            .putBoolean(Prefs.CHECK_UPDATES, checkUpdates)
            .apply()
    }

    companion object {
        // Ports below 1024 can't be opened by an app on a non-rooted phone.
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        fun load(context: Context): AppSettings {
            val p = context.appPrefs()
            return AppSettings(
                port = p.getString(Prefs.PORT, null) ?: Prefs.DEFAULT_PORT.toString(),
                password = p.getString(Prefs.PASSWORD, null) ?: "",
                https = p.getBoolean(Prefs.HTTPS, false),
                themeMode = ThemeMode.entries.getOrElse(p.getInt(Prefs.THEME_MODE, 0)) { ThemeMode.SYSTEM },
                dynamicColor = p.getBoolean(Prefs.DYNAMIC_COLOR, true),
                allowWebDelete = p.getBoolean(Prefs.ALLOW_WEB_DELETE, false),
                webDavEnabled = p.getBoolean(Prefs.WEBDAV_ENABLED, false),
                dateFormat = p.getString(Prefs.DATE_FORMAT, null) ?: Prefs.DEFAULT_DATE_FORMAT,
                deviceName = p.getString(Prefs.DEVICE_NAME, null) ?: Prefs.DEFAULT_DEVICE_NAME,
                discovery = p.getBoolean(Prefs.DISCOVERY, true),
                thumbnails = p.getBoolean(Prefs.THUMBNAILS, true),
                devOptions = p.getBoolean(Prefs.DEV_OPTIONS, false),
                crashReports = p.getBoolean(Prefs.CRASH_REPORTS, false),
                serverLogs = p.getBoolean(Prefs.SERVER_LOGS, false),
                checkUpdates = p.getBoolean(Prefs.CHECK_UPDATES, true)
            )
        }
    }
}

// ---------- Device name ----------

/** Placeholders usable in the device name setting, in the order the settings screen lists them. */
val DEVICE_NAME_PLACEHOLDERS = listOf("%name%", "%model%", "%brand%", "%manufacturer%", "%device%")

private val PLACEHOLDER = Regex("%[a-z]+%", RegexOption.IGNORE_CASE)

/** The name the user gave the phone in system Settings (Android 7.1+), else its model. */
fun systemDeviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

/** Fills in the placeholders of a device name template; a blank result falls back to the system name. */
fun resolveDeviceName(context: Context, template: String): String {
    fun cap(s: String) = s.replaceFirstChar { it.titlecase(Locale.ROOT) }
    val values = mapOf(
        "%name%" to systemDeviceName(context),
        "%model%" to Build.MODEL,
        "%brand%" to cap(Build.BRAND),
        "%manufacturer%" to cap(Build.MANUFACTURER),
        "%device%" to Build.DEVICE
    )
    val resolved = PLACEHOLDER.replace(template) { m ->
        values[m.value.lowercase(Locale.ROOT)] ?: m.value
    }.trim()
    return resolved.ifEmpty { systemDeviceName(context) }
}

/** The name shown on the web page, in the network neighbourhood and over DNS-SD. */
fun deviceName(context: Context): String =
    resolveDeviceName(context, context.appPrefs().getString(Prefs.DEVICE_NAME, null) ?: Prefs.DEFAULT_DEVICE_NAME)

/** A stable UUID identifying this phone to UPnP clients (generated once). */
fun deviceId(context: Context): String {
    val p = context.appPrefs()
    p.getString(Prefs.DEVICE_ID, null)?.let { return it }
    return UUID.randomUUID().toString().also { p.edit().putString(Prefs.DEVICE_ID, it).apply() }
}

// ---------- Own HTTPS certificate ----------

/** The user's PKCS#12 certificate, kept in app-private storage; without it HTTPS is self-signed. */
object CustomCertificate {
    private const val FILE_NAME = "https_certificate.p12"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun password(context: Context): String = context.appPrefs().getString(Prefs.CERT_PASSWORD, null) ?: ""

    /** The stored certificate, or null if there's none (or it somehow no longer opens). */
    fun load(context: Context): java.security.KeyStore? {
        val f = file(context)
        if (!f.exists()) return null
        return try { Certificates.loadPkcs12(f.readBytes(), password(context)) } catch (e: Exception) { null }
    }

    fun info(context: Context): Certificates.Info? = load(context)?.let { Certificates.describe(it) }

    /** Validates first - throws (and keeps the previous certificate) if it can't be used. */
    fun save(context: Context, bytes: ByteArray, password: String): Certificates.Info? {
        val keyStore = Certificates.loadPkcs12(bytes, password)
        file(context).writeBytes(bytes)
        context.appPrefs().edit().putString(Prefs.CERT_PASSWORD, password).apply()
        return Certificates.describe(keyStore)
    }

    fun remove(context: Context) {
        file(context).delete()
        context.appPrefs().edit().remove(Prefs.CERT_PASSWORD).apply()
    }

    /** Changes whenever the certificate does - lets the UI tell that a restart is needed. */
    fun stamp(context: Context): Long = file(context).lastModified()
}

// ---------- App language ----------

/** Languages offered in Settings (BCP-47 tags). The picker shows each in its own language. */
val SUPPORTED_LANGUAGES = listOf("en", "pl")

/** Patterns offered for dates on the web page (only yyyy MM dd HH mm ss - the page formats them itself). */
val DATE_FORMATS = listOf(
    "dd-MM-yyyy HH:mm", "dd.MM.yyyy HH:mm", "dd/MM/yyyy HH:mm", "yyyy-MM-dd HH:mm", "MM/dd/yyyy HH:mm",
    "dd.MM.yyyy HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd"
)

/**
 * The language picked in Settings, or null to follow the system. Android 13+ stores per-app
 * languages itself (and offers them in system Settings too); older versions use our own pref.
 */
fun appLanguage(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else locales[0].language
    }
    return context.appPrefs().getString(Prefs.LANGUAGE_TAG, null)
}

/** Applies a new language. On Android 13+ the system recreates the Activity by itself. */
fun setAppLanguage(activity: android.app.Activity, tag: String?) {
    activity.appPrefs().edit().putString(Prefs.LANGUAGE_TAG, tag).apply()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    } else {
        activity.recreate()
    }
}

/** The language actually in effect (the chosen one, else the system's) - used for the web page. */
fun effectiveLanguage(context: Context): String =
    appLanguage(context) ?: Resources.getSystem().configuration.locales[0].language

/**
 * A Context whose resources use the chosen language. Only needed below Android 13, where nothing
 * applies the choice automatically - Android 13+ already does it for every Context of the app.
 */
fun Context.withAppLanguage(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val tag = appPrefs().getString(Prefs.LANGUAGE_TAG, null) ?: return this
    val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    return createConfigurationContext(config)
}

fun languageDisplayName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

// ---------- Network ----------

/**
 * The phone's address on the local network. Wi-Fi and hotspot interfaces win over everything
 * else, so a phone with mobile data on doesn't advertise its (unreachable) carrier address.
 */
fun localIpAddress(): String? = try {
    val candidates = Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { nif ->
            Collections.list(nif.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { nif.name to it.hostAddress }
        }
    fun rank(name: String) = when {
        name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> 0
        name.startsWith("eth") || name.startsWith("rndis") || name.startsWith("usb") -> 1
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") -> 3
        else -> 2
    }
    candidates.minByOrNull { rank(it.first) }?.second
} catch (e: Exception) {
    null
}
