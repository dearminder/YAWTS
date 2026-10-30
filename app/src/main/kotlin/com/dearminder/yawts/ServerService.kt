package com.dearminder.yawts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

/** What the UI needs to know about a running server. */
data class RunningServer(
    val port: Int,
    val secure: Boolean,
    val httpsRequested: Boolean,
    val certStamp: Long,
    val discovery: Boolean,
    val name: String
)

/**
 * Foreground service that owns the server. All settings are read from SharedPreferences (not
 * Intent extras), so a restart by the system after the process was killed comes back with the
 * same password - and password/delete/WebDAV/date settings are re-read live on every request.
 */
class ServerService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "server_channel"
        private const val ACTION_START = "START"
        private const val ACTION_STOP = "STOP"
        private const val CONFIG_MAX_AGE_MS = 5_000L

        // Single source of truth for the UI, which survives Activity recreation and also
        // updates when the server is stopped from the notification.
        private val _running = MutableStateFlow<RunningServer?>(null)
        val running: StateFlow<RunningServer?> = _running.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        fun start(context: Context) {
            _error.value = null
            context.startForegroundService(Intent(context, ServerService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }
    }

    private var http: HttpServer? = null
    private var nsdManager: NsdManager? = null
    private val nsdListeners = ArrayList<NsdManager.RegistrationListener>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var ssdp: SsdpServer? = null

    // The config is asked for on every request, and building it costs a system call (the app language
    // on Android 13+). It's rebuilt after any settings change, and at least every few seconds for what
    // can change outside the app (the phone's name, the system language).
    private class CachedConfig(val version: Int, val time: Long, val config: ServerConfig)
    @Volatile private var cachedConfig: CachedConfig? = null
    @Volatile private var prefsVersion = 0 // only written on the main thread
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> prefsVersion++ }

    /** Strings in the app's chosen language (a Service doesn't get it applied below Android 13). */
    private fun text(resId: Int, vararg args: Any): String = withAppLanguage().getString(resId, *args)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // A null intent means the system restarted us after killing the process - start again.
        createNotificationChannel()
        startForegroundCompat(buildNotification(text(R.string.notif_starting)))
        if (http == null && !startServer()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startServer(): Boolean {
        val settings = AppSettings.load(this)
        val port = settings.portNumber ?: Prefs.DEFAULT_PORT
        val ssl = if (settings.https) {
            try {
                CustomCertificate.load(this)?.let { Certificates.sslFactory(it, CustomCertificate.password(this).toCharArray()) }
                    ?: selfSignedSslFactory()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else null

        // Held in a field: SharedPreferences only keeps a weak reference to its listeners.
        appPrefs().registerOnSharedPreferenceChangeListener(prefsListener)
        val files = FileServer(Environment.getExternalStorageDirectory(), ::currentConfig, ::readAsset, Thumbnails()::get)
        val server = HttpServer(port, ssl, files::handle, DevTools.serverLog(this))
        try {
            server.start()
        } catch (e: Exception) {
            e.printStackTrace()
            DevTools.log(this, "Could not start on port $port: $e")
            _error.value = text(R.string.error_port_unavailable, port)
            return false
        }
        DevTools.log(this, "Server started on port $port (${if (ssl != null) "HTTPS" else "HTTP"})")

        http = server
        _running.value = RunningServer(
            port, secure = ssl != null, httpsRequested = settings.https, certStamp = CustomCertificate.stamp(this),
            discovery = settings.discovery, name = deviceName(this)
        )
        _error.value = null
        acquireLocks()

        val ip = localIpAddress()
        val scheme = if (ssl != null) "https" else "http"
        if (settings.discovery) {
            registerNsd(port, settings.webDavEnabled)
            if (ip != null) startSsdp(ip, "$scheme://$ip:$port")
        }
        val content = if (ip != null) text(R.string.notif_open_url, "$scheme://$ip:$port") else text(R.string.notif_no_ip)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
        return true
    }

    private fun currentConfig(): ServerConfig {
        // The version is read before the settings, so a change made meanwhile still invalidates the result.
        val version = prefsVersion
        val now = SystemClock.elapsedRealtime()
        cachedConfig?.let { if (it.version == version && now - it.time < CONFIG_MAX_AGE_MS) return it.config }
        return readConfig().also { cachedConfig = CachedConfig(version, now, it) }
    }

    private fun readConfig(): ServerConfig {
        val p = appPrefs()
        return ServerConfig(
            password = p.getString(Prefs.PASSWORD, null) ?: "",
            allowDelete = p.getBoolean(Prefs.ALLOW_WEB_DELETE, false),
            webDav = p.getBoolean(Prefs.WEBDAV_ENABLED, false),
            thumbnails = p.getBoolean(Prefs.THUMBNAILS, true),
            dateFormat = p.getString(Prefs.DATE_FORMAT, null) ?: Prefs.DEFAULT_DATE_FORMAT,
            language = effectiveLanguage(this),
            deviceName = deviceName(this),
            deviceId = deviceId(this),
            themeCss = webThemeCss(this, p.getBoolean(Prefs.DYNAMIC_COLOR, true))
        )
    }

    private fun readAsset(name: String): ByteArray? = try {
        assets.open("web/$name").use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun shutdown() {
        if (http != null) DevTools.log(this, "Server stopped")
        http?.stop()
        http = null
        appPrefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        cachedConfig = null
        _running.value = null
        nsdListeners.forEach { listener ->
            try { nsdManager?.unregisterService(listener) } catch (e: Exception) { /* never registered */ }
        }
        nsdListeners.clear()
        ssdp?.let { s -> Thread { s.stop() }.start() } // sends byebye - no network on the main thread
        ssdp = null
        multicastLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keeps transfers going at full speed while the screen is off - only while the server runs. */
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YAWTS::server")
            .apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YAWTS::server")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    /** Lets the server be found by name on the network (e.g. in file managers that browse DNS-SD). */
    private fun registerNsd(port: Int, webDav: Boolean) {
        val manager = getSystemService(NsdManager::class.java) ?: return
        nsdManager = manager
        val types = if (webDav) listOf("_http._tcp.", "_webdav._tcp.") else listOf("_http._tcp.")
        for (type in types) {
            val info = NsdServiceInfo().apply {
                serviceName = deviceName(this@ServerService)
                serviceType = type
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            try {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                nsdListeners += listener
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * UPnP discovery: the phone appears under its name in Windows Explorer's "Network" and a
     * double-click opens the WebDAV share (or the web page, with WebDAV off). Wi-Fi drops multicast
     * without the lock (to save battery).
     */
    private fun startSsdp(ip: String, baseUrl: String) {
        multicastLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("YAWTS::ssdp")
            ?.apply { setReferenceCounted(false); acquire() }
        val server = SsdpServer(ip, "$baseUrl/.yawts/description.xml", deviceId(this))
        ssdp = server
        Thread {
            try {
                server.start()
            } catch (e: Exception) {
                e.printStackTrace() // port 1900 taken by another app - discovery just stays off
            }
        }.start()
    }

    // ---------- Notification ----------

    /** The "dataSync" foreground service type is required from Android 10 (API 29) upward. */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(text(R.string.notif_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, text(R.string.notif_stop_action), stop)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, text(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ---------- HTTPS ----------

    /** A self-signed certificate whose key lives in the Android Keystore (generated once). */
    private fun selfSignedSslFactory(): SSLServerSocketFactory {
        val alias = "yawts_https_self_signed"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (!keyStore.containsAlias(alias)) {
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=YAWTS"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Calendar.getInstance().time)
                .setCertificateNotAfter(notAfter.time)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(spec)
                generateKeyPair()
            }
        }

        return Certificates.sslFactory(keyStore, null)
    }
}
