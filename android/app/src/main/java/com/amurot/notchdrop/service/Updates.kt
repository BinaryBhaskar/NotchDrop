package com.amurot.notchdrop.service

import com.amurot.notchdrop.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Looks for a newer NotchDrop on GitHub Releases. Automatic checks are opt-in; manual ones always work. */
object Updates {
    private const val LATEST = "https://api.github.com/repos/BinaryBhaskar/NotchDrop/releases/latest"
    private const val INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class Release(val version: String, val page: String, val apk: String?, val notes: String)

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Available(val release: Release) : Status
        data object Failed : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    val current: String get() = BuildConfig.VERSION_NAME

    /**
     * Checks GitHub unless an automatic check already ran today. Returns a release to prompt about:
     * any newer one for a manual check, or one the user hasn't skipped for an automatic check.
     */
    suspend fun check(store: PeerStore, manual: Boolean): Release? {
        if (!manual && (!store.autoUpdateCheck || System.currentTimeMillis() - store.lastUpdateCheck < INTERVAL_MS)) return null
        if (_status.value == Status.Checking) return null
        _status.value = Status.Checking
        val release = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        store.lastUpdateCheck = System.currentTimeMillis()
        val r = release.getOrElse { _status.value = Status.Failed; return null }
        if (!isNewer(r.version, current)) {
            _status.value = Status.UpToDate
            return null
        }
        _status.value = Status.Available(r)
        return r.takeIf { manual || store.skippedVersion != r.version }
    }

    private fun fetchLatest(): Release {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            val apk = assets?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
            }
            return Release(normalize(o.getString("tag_name")), o.getString("html_url"), apk, o.optString("body"))
        } finally {
            c.disconnect()
        }
    }

    fun normalize(tag: String) = tag.removePrefix("v").removePrefix("V")

    /** Numeric dotted comparison: "1.10.0" > "1.9.2", "1.2" == "1.2.0". */
    fun isNewer(a: String, than: String): Boolean {
        fun parts(s: String) = normalize(s).substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(than)
        for (i in 0 until maxOf(x.size, y.size)) {
            val l = x.getOrElse(i) { 0 }
            val r = y.getOrElse(i) { 0 }
            if (l != r) return l > r
        }
        return false
    }
}
