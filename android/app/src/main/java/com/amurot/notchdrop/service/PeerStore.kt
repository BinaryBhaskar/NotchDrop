package com.amurot.notchdrop.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.amurot.notchdrop.ui.ThemeMode
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PairedMac(
    val id: String,
    val name: String,
    val key: ByteArray,
    val addrs: List<String>,
    val port: Int,
    /** Nickname chosen on this phone; survives the Mac renaming itself. */
    val alias: String? = null,
) {
    /** The nickname, or the Mac's own name minus an owner prefix ("Alex's MacBook Air" -> "MacBook Air"). */
    val displayName: String get() = alias ?: name.substringAfter("’s ").substringAfter("'s ").ifBlank { name }
}

data class HistoryEntry(
    val incoming: Boolean,
    val title: String,
    val detail: String,
    val time: Long,
    val uri: String? = null,
    val text: String? = null,
    val mime: String? = null,
    /** Stable key for lists and removal. */
    val id: String = UUID.randomUUID().toString(),
)

/** App-private prefs. allowBackup=false keeps the pairing key on this device. */
class PeerStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("notchdrop", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    /** The name the Mac sees: a custom one if set, otherwise the system device name. */
    val deviceName: String get() = customName ?: systemName

    val systemName: String
        get() = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    var customName: String?
        get() = prefs.getString("customName", null)
        set(v) = prefs.edit().putString("customName", v?.trim()?.takeIf { it.isNotEmpty() }).apply()

    var receiveEnabled: Boolean
        get() = prefs.getBoolean("receive", true)
        set(v) = prefs.edit().putBoolean("receive", v).apply()

    /** Put received text straight on the clipboard. */
    var autoCopyText: Boolean
        get() = prefs.getBoolean("autoCopyText", true)
        set(v) = prefs.edit().putBoolean("autoCopyText", v).apply()

    /** Open received links in the browser (only possible while the app is on screen). */
    var openLinks: Boolean
        get() = prefs.getBoolean("openLinks", false)
        set(v) = prefs.edit().putBoolean("openLinks", v).apply()

    var theme: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.System)
        set(v) = prefs.edit().putString("theme", v.name).apply()

    /** Opt-in daily check of GitHub Releases; [updatePromptShown] records that we asked once. */
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean("autoUpdateCheck", false)
        set(v) = prefs.edit().putBoolean("autoUpdateCheck", v).apply()

    var updatePromptShown: Boolean
        get() = prefs.getBoolean("updatePromptShown", false)
        set(v) = prefs.edit().putBoolean("updatePromptShown", v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0)
        set(v) = prefs.edit().putLong("lastUpdateCheck", v).apply()

    var skippedVersion: String?
        get() = prefs.getString("skippedVersion", null)
        set(v) = prefs.edit().putString("skippedVersion", v).apply()

    /** The folder picked for received files (a SAF tree URI), or null for Downloads/From <Mac>. */
    var saveTree: String?
        get() = prefs.getString("saveTree", null)
        set(v) = prefs.edit().putString("saveTree", v).apply()

    var batteryPromptDismissed: Boolean
        get() = prefs.getBoolean("batteryPromptDismissed", false)
        set(v) = prefs.edit().putBoolean("batteryPromptDismissed", v).apply()

    var peer: PairedMac?
        get() = prefs.getString("peer", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                val a = o.getJSONArray("addrs")
                PairedMac(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    key = Base64.getDecoder().decode(o.getString("key")),
                    addrs = List(a.length()) { a.getString(it) },
                    port = o.getInt("port"),
                    alias = o.optString("alias").ifEmpty { null },
                )
            }.getOrNull()
        }
        set(p) {
            if (p == null) {
                prefs.edit().remove("peer").apply()
                return
            }
            val o = JSONObject()
                .put("id", p.id).put("name", p.name)
                .put("key", Base64.getEncoder().encodeToString(p.key))
                .put("addrs", JSONArray(p.addrs)).put("port", p.port)
                .put("alias", p.alias ?: "")
            prefs.edit().putString("peer", o.toString()).apply()
        }

    var history: List<HistoryEntry>
        get() = runCatching {
            val a = JSONArray(prefs.getString("history", "[]"))
            List(a.length()) {
                val o = a.getJSONObject(it)
                HistoryEntry(
                    incoming = o.getBoolean("in"),
                    title = o.getString("title"),
                    detail = o.optString("detail"),
                    time = o.getLong("time"),
                    uri = o.optString("uri").ifEmpty { null },
                    text = o.optString("text").ifEmpty { null },
                    mime = o.optString("mime").ifEmpty { null },
                    id = o.optString("id").ifEmpty { "${o.getLong("time")}-$it" },
                )
            }
        }.getOrDefault(emptyList())
        set(list) {
            val a = JSONArray()
            list.take(40).forEach {
                a.put(
                    JSONObject().put("in", it.incoming).put("title", it.title).put("detail", it.detail)
                        .put("time", it.time).put("uri", it.uri ?: "").put("text", it.text ?: "")
                        .put("mime", it.mime ?: "").put("id", it.id)
                )
            }
            prefs.edit().putString("history", a.toString()).apply()
        }
}
