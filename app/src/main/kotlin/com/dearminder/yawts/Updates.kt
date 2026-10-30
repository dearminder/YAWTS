package com.dearminder.yawts

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val GITHUB_REPO = "dearminder/YAWTS"
const val GITHUB_URL = "https://github.com/$GITHUB_REPO"

/** A GitHub release; [apkUrl] is null when it has no APK attached (then only its page can be opened). */
data class Release(val version: String, val pageUrl: String, val apkUrl: String?, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val release: Release, val progress: Float?) : UpdateState
    data class DownloadFailed(val release: Release) : UpdateState
}

/**
 * Looks for a newer release on GitHub (published by .github/workflows/release.yml) and installs
 * its APK through Android's package installer. Pre-releases are skipped: "releases/latest" never
 * returns them. Work runs in the process's own scope, so a download survives the activity being
 * recreated (a rotation, a theme change).
 */
object Updates {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The startup check runs once per process, not on every screen rotation. */
    private var checkedAtStartup = false

    private val busy get() = _state.value.let { it is UpdateState.Checking || it is UpdateState.Downloading }

    fun checkAtStartup(context: Context) {
        if (checkedAtStartup) return
        checkedAtStartup = true
        check(context)
    }

    fun check(context: Context) {
        if (busy) return
        val app = context.applicationContext
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = try {
                val release = withContext(Dispatchers.IO) { fetchLatest() }
                if (isNewerVersion(release.version, appVersion(app))) UpdateState.Available(release) else UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.CheckFailed
            }
        }
    }

    /** Downloads the APK into the cache and hands it to the package installer. */
    fun install(context: Context, release: Release) {
        val apkUrl = release.apkUrl ?: return
        if (busy) return
        val app = context.applicationContext
        _state.value = UpdateState.Downloading(release, null)
        scope.launch {
            val apk = try {
                withContext(Dispatchers.IO) { download(app, apkUrl, release) }
            } catch (e: Exception) {
                _state.value = UpdateState.DownloadFailed(release)
                return@launch
            }
            // Stays offered: if the installer is cancelled, the dialog is still there to try again.
            _state.value = UpdateState.Available(release)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** "Later" in the update dialog; the next check offers the update again. */
    fun dismiss() {
        if (_state.value is UpdateState.Available || _state.value is UpdateState.DownloadFailed) _state.value = UpdateState.Idle
    }

    private fun fetchLatest(): Release {
        val json = JSONObject(open("https://api.github.com/repos/$GITHUB_REPO/releases/latest").use { c ->
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().readText()
        })
        val assets = json.optJSONArray("assets")
        val apkUrl = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?.optString("browser_download_url")
        return Release(
            version = json.getString("tag_name").removePrefix("v"),
            pageUrl = json.optString("html_url", "$GITHUB_URL/releases"),
            apkUrl = apkUrl,
            notes = json.optString("body").trim()
        )
    }

    private fun download(context: Context, url: String, release: Release): File {
        val dir = File(context.cacheDir, "updates")
        dir.deleteRecursively() // drops APKs of earlier updates
        dir.mkdirs()
        val target = File(dir, "YAWTS-${release.version}.apk")
        open(url).use { c ->
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            var lastShown = -1
            c.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastShown) {
                            lastShown = percent
                            _state.value = UpdateState.Downloading(release, if (total > 0) done.toFloat() / total else null)
                        }
                    }
                }
            }
        }
        return target
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "YAWTS") // GitHub's API rejects requests without one
        }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private const val APK_TYPE = "application/vnd.android.package-archive"
    private const val TIMEOUT_MS = 15_000
}

fun appVersion(context: Context): String =
    try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }

/**
 * Whether [remote] ("1.2.0", "1.2.0-beta1") is newer than [current]. Numbers are compared part by
 * part; on equal numbers a release beats a pre-release of the same version.
 */
fun isNewerVersion(remote: String, current: String): Boolean {
    fun numbers(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val r = numbers(remote)
    val c = numbers(current)
    for (i in 0 until maxOf(r.size, c.size)) {
        val diff = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
        if (diff != 0) return diff > 0
    }
    return '-' !in remote && '-' in current
}
