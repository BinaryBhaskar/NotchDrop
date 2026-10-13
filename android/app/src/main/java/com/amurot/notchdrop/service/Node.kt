package com.amurot.notchdrop.service

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import android.util.Patterns
import androidx.annotation.RequiresApi
import com.amurot.notchdrop.core.NDException
import com.amurot.notchdrop.core.OutgoingItem
import com.amurot.notchdrop.core.PeerLink
import com.amurot.notchdrop.core.ProgressThrottle
import com.amurot.notchdrop.core.ProtocolServer
import com.amurot.notchdrop.core.ReceiveSink
import com.amurot.notchdrop.core.ReceivedBatch
import com.amurot.notchdrop.core.SessionDelegate
import com.amurot.notchdrop.core.Transfer
import com.amurot.notchdrop.core.Wire
import com.amurot.notchdrop.ui.ThemeMode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class Route { WIFI, HOTSPOT }

sealed interface Activity {
    data object Idle : Activity
    data object Connecting : Activity
    data class Sending(val done: Long, val total: Long, val name: String) : Activity
    data class Sent(val summary: String) : Activity
    data class Receiving(val done: Long, val total: Long, val name: String) : Activity
    data class Received(val batch: ReceivedBatch) : Activity
    data class Failed(val message: String) : Activity
}

data class NodeState(
    val peer: PairedMac? = null,
    val reachable: Boolean = false,
    val route: Route? = null,
    val activity: Activity = Activity.Idle,
    val listening: Boolean = false,
    val deviceName: String = "",
    val history: List<HistoryEntry> = emptyList(),
) {
    val busy: Boolean get() = activity is Activity.Connecting || activity is Activity.Sending || activity is Activity.Receiving
}

/** Process-wide engine: protocol server, NSD, pairing and sending. NodeService keeps the process alive for receiving. */
@SuppressLint("StaticFieldLeak") // Holds only the application context.
object Node : SessionDelegate {
    private const val TAG = "NotchDrop"

    private lateinit var ctx: Context
    lateinit var store: PeerStore
        private set
    private lateinit var cm: ConnectivityManager
    private lateinit var nsd: NsdManager

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(NodeState())
    val state: StateFlow<NodeState> = _state.asStateFlow()
    private val _themeMode = MutableStateFlow(ThemeMode.System)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** True while one of our activities is on screen; Android only lets us open links then. */
    @Volatile var uiVisible = false

    private var server: ProtocolServer? = null
    private val sendLock = Mutex()
    private var resetJob: Job? = null
    private var refreshJob: Job? = null

    /** Mac id -> addresses seen via NSD. */
    private val nsdAddrs = ConcurrentHashMap<String, List<String>>()
    /** ServiceInfoCallbacks by service name (API 34+; older versions resolve once per sighting). */
    private val nsdCallbacks = ConcurrentHashMap<String, Any>()
    private val nsdExecutor = Executors.newSingleThreadExecutor()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        store = PeerStore(ctx)
        cm = ctx.getSystemService(ConnectivityManager::class.java)
        nsd = ctx.getSystemService(NsdManager::class.java)
        _state.value = NodeState(peer = store.peer, deviceName = store.deviceName, history = store.history)
        _themeMode.value = store.theme

        // Wi-Fi/hotspot changes: tell the Mac our new addresses.
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRefresh(1500)
            override fun onLost(network: Network) = scheduleRefresh(1500)
        })
    }

    // MARK: Lifecycle (driven by NodeService)

    fun startListening() {
        if (server == null) {
            val s = ProtocolServer(Wire.PHONE_PORT, this)
            try {
                s.start()
                server = s
            } catch (e: Exception) {
                Log.w(TAG, "server start failed", e)
                return
            }
        }
        startNsd()
        _state.update { it.copy(listening = true) }
        scheduleRefresh(0)
    }

    fun stopListening() {
        server?.stop()
        server = null
        stopNsd()
        _state.update { it.copy(listening = false) }
    }

    // MARK: SessionDelegate (incoming connections from the Mac)

    override val myId: String get() = store.deviceId

    override fun keyFor(mode: String, from: String): ByteArray? {
        val p = store.peer ?: return null
        return if (mode == "session" && from == p.id) p.key else null
    }

    override fun myHello(): JSONObject = JSONObject()
        .put("t", "hello").put("id", store.deviceId).put("name", store.deviceName).put("platform", "android")
        .put("addrs", JSONArray(Net.localAddrs())).put("port", Wire.PHONE_PORT)

    override fun didReceiveHello(hello: JSONObject, mode: String, remoteHost: String?) {
        learn(hello, remoteHost)
    }

    override fun sink(): ReceiveSink = MediaStoreSink(ctx, store.peer?.displayName ?: "Mac")

    override fun incomingProgress(done: Long, total: Long, name: String) {
        setActivity(Activity.Receiving(done, total, name))
    }

    override fun incomingFinished(batch: ReceivedBatch) {
        val mac = store.peer?.displayName ?: "Mac"
        setActivity(Activity.Received(batch))
        val entries = batch.files.map { HistoryEntry(true, it.name, "From $mac", System.currentTimeMillis(), uri = it.uri) } +
            batch.texts.map { HistoryEntry(true, it.take(120), "Text from $mac", System.currentTimeMillis(), text = it) }
        addHistory(entries)
        Notifier.received(ctx, batch, mac)
        batch.texts.lastOrNull()?.takeIf { store.autoCopyText }?.let { text ->
            runCatching { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From $mac", text)) }
        }
        if (store.openLinks && uiVisible) {
            batch.texts.map { it.trim() }.filter { Patterns.WEB_URL.matcher(it).matches() }.forEach { link ->
                val url = if ("://" in link) link else "https://$link"
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    fun setTheme(mode: ThemeMode) {
        store.theme = mode
        _themeMode.value = mode
    }

    override fun incomingFailed(error: Exception) {
        setActivity(Activity.Failed(error.message ?: "Receive failed"))
    }

    // MARK: Pairing

    /** Parses the Mac's QR (`notchdrop://pair?...`) and completes the handshake in pair mode. */
    suspend fun pair(raw: String): PairedMac {
        val uri = Uri.parse(raw)
        if (uri.scheme != "notchdrop" || uri.host != "pair") throw NDException("Not a NotchDrop code")
        val macId = uri.getQueryParameter("id") ?: throw NDException("Code is missing the Mac id")
        val key = runCatching { Base64.getUrlDecoder().decode(uri.getQueryParameter("k")) }.getOrNull()
        if (key == null || key.size != 32) throw NDException("Code has no valid key")
        val port = uri.getQueryParameter("p")?.toIntOrNull() ?: Wire.MAC_PORT
        val addrs = uri.getQueryParameter("a").orEmpty().split(',').filter { it.isNotBlank() }
        val name = uri.getQueryParameter("n") ?: "Mac"

        val candidates = addrs.distinct().map { InetSocketAddress(it, port) }
        val c = PeerLink.connect(candidates, "pair", key, store.deviceId, myHello(), socketFor = { Net.socketFor(cm, it) })
        try {
            if (c.hello.optString("id") != macId) throw NDException("Unexpected device answered")
            val peer = PairedMac(
                id = macId,
                name = c.hello.optString("name", name),
                key = key,
                addrs = jsonStrings(c.hello.optJSONArray("addrs")).ifEmpty { addrs },
                port = c.hello.optInt("port", port),
            )
            store.peer = peer
            _state.update { it.copy(peer = peer, reachable = true, route = routeFor(c.host)) }
            runCatching { c.channel.sendJson(JSONObject().put("t", "bye")) }
            return peer
        } finally {
            c.channel.close()
        }
    }

    /** Renames this phone as the Mac sees it; null or blank restores the system name. */
    fun renameThisDevice(name: String?) {
        store.customName = name
        _state.update { it.copy(deviceName = store.deviceName) }
        // Re-advertise under the new name; the Mac also picks it up from the next hello.
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        if (server != null) startNsd()
        scheduleRefresh(0)
    }

    /** Sets a local nickname for the paired Mac; null or blank goes back to the Mac's own name. */
    fun renamePeer(alias: String?) {
        val p = store.peer ?: return
        val updated = p.copy(alias = alias?.trim()?.takeIf { it.isNotEmpty() })
        store.peer = updated
        _state.update { it.copy(peer = updated) }
    }

    fun unpair() {
        store.peer = null
        nsdAddrs.clear()
        _state.update { it.copy(peer = null, reachable = false, route = null) }
    }

    // MARK: Presence

    fun scheduleRefresh(delayMs: Long) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(delayMs)
            refresh()
        }
    }

    /** Connects to the Mac just to exchange hellos, which also tells it our current addresses. */
    suspend fun refresh(): Boolean {
        val peer = store.peer ?: return false
        if (_state.value.busy) return true
        return try {
            val c = connect(peer, timeoutMs = 2500)
            runCatching { c.channel.sendJson(JSONObject().put("t", "bye")) }
            c.channel.close()
            true
        } catch (e: Exception) {
            _state.update { it.copy(reachable = false, route = null) }
            false
        }
    }

    // MARK: Sending

    fun send(items: List<OutgoingItem>, summary: String): Job = scope.launch {
        runCatching { sendNow(items, summary) }
    }

    suspend fun sendNow(items: List<OutgoingItem>, summary: String) = sendLock.withLock {
        val peer = store.peer ?: run {
            setActivity(Activity.Failed("Not paired with a Mac"))
            throw NDException("Not paired with a Mac")
        }
        setActivity(Activity.Connecting)
        try {
            val c = connect(peer)
            try {
                Transfer.send(items, c.channel, ProgressThrottle { done, total, name ->
                    setActivity(Activity.Sending(done, total, name))
                })
                runCatching { c.channel.sendJson(JSONObject().put("t", "bye")) }
            } finally {
                c.channel.close()
            }
            setActivity(Activity.Sent(summary))
            addHistory(listOf(HistoryEntry(false, summary, "To ${peer.displayName}", System.currentTimeMillis())))
        } catch (e: Exception) {
            setActivity(Activity.Failed(e.message ?: "Send failed"))
            throw e
        }
    }

    private suspend fun connect(peer: PairedMac, timeoutMs: Int = 4000): PeerLink.Connected {
        val hosts = (nsdAddrs[peer.id].orEmpty() + peer.addrs).distinct()
        val c = PeerLink.connect(
            hosts.map { InetSocketAddress(it, peer.port) }, "session", peer.key, store.deviceId, myHello(),
            timeoutMs = timeoutMs, socketFor = { Net.socketFor(cm, it) },
        )
        learn(c.hello, c.host)
        return c
    }

    private fun learn(hello: JSONObject, remoteHost: String?) {
        val p = store.peer ?: return
        if (hello.optString("id") != p.id) return
        val addrs = jsonStrings(hello.optJSONArray("addrs"))
        val updated = p.copy(
            name = hello.optString("name", p.name),
            addrs = (listOfNotNull(remoteHost) + addrs).distinct().ifEmpty { p.addrs },
            port = hello.optInt("port", p.port).takeIf { it > 0 } ?: p.port,
        )
        if (updated != p) store.peer = updated
        _state.update { it.copy(peer = updated, reachable = true, route = routeFor(remoteHost)) }
    }

    private fun routeFor(host: String?): Route? {
        val addr = host?.let { runCatching { InetAddress.getByName(it) }.getOrNull() } ?: return null
        return if (Net.onWifiSubnet(cm, addr)) Route.WIFI else Route.HOTSPOT
    }

    // MARK: State helpers

    fun setActivity(a: Activity) {
        _state.update { it.copy(activity = a) }
        resetJob?.cancel()
        val hold = when (a) {
            is Activity.Sent -> 4000L
            is Activity.Received -> 8000L
            is Activity.Failed -> 6000L
            else -> return
        }
        resetJob = scope.launch {
            delay(hold)
            _state.update { if (it.activity == a) it.copy(activity = Activity.Idle) else it }
        }
    }

    fun dismissActivity() {
        if (!_state.value.busy) _state.update { it.copy(activity = Activity.Idle) }
    }

    private fun addHistory(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        val list = (entries.reversed() + store.history).take(40)
        store.history = list
        _state.update { it.copy(history = list) }
    }

    fun clearHistory() {
        store.history = emptyList()
        _state.update { it.copy(history = emptyList()) }
    }

    private fun jsonStrings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }

    // MARK: NSD

    private fun startNsd() {
        if (registration == null) {
            val info = NsdServiceInfo().apply {
                serviceName = "NotchDrop ${store.deviceName}"
                serviceType = Wire.SERVICE_TYPE
                port = Wire.PHONE_PORT
                setAttribute("id", store.deviceId)
                setAttribute("role", "phone")
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "NSD register failed $code") }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
            }
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { registration = l }
        }
        if (discovery == null) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "NSD discovery failed $code") }
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) = watch(info)
                override fun onServiceLost(info: NsdServiceInfo) {
                    nsdCallbacks.remove(info.serviceName)?.let(::unwatch)
                }
            }
            runCatching { nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { discovery = l }
        }
    }

    private fun watch(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) watchUpdates(info) else resolveOnce(info)
    }

    private fun macSeen(attrs: Map<String, ByteArray>, addrs: List<String>) {
        if (attrs["role"]?.decodeToString() != "mac") return
        val id = attrs["id"]?.decodeToString() ?: return
        if (addrs.isEmpty()) return
        val changed = nsdAddrs.put(id, addrs) != addrs
        if (changed && id == store.peer?.id && !_state.value.reachable) scheduleRefresh(300)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun watchUpdates(info: NsdServiceInfo) {
        if (nsdCallbacks.containsKey(info.serviceName)) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(code: Int) { nsdCallbacks.remove(info.serviceName) }
            override fun onServiceUpdated(s: NsdServiceInfo) =
                macSeen(s.attributes, s.hostAddresses.mapNotNull { a -> a.hostAddress?.takeIf { '.' in it } })
            override fun onServiceLost() {}
            override fun onServiceInfoCallbackUnregistered() {}
        }
        try {
            nsd.registerServiceInfoCallback(info, nsdExecutor, cb)
            nsdCallbacks[info.serviceName] = cb
        } catch (_: Exception) {
        }
    }

    /** Android 10–13: one-shot resolve. Only one may run at a time, so retry when busy. */
    @Suppress("DEPRECATION")
    private fun resolveOnce(info: NsdServiceInfo, attempt: Int = 0) {
        val l = object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
                if (code == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 6) {
                    scope.launch { delay(300L * (attempt + 1)); resolveOnce(info, attempt + 1) }
                }
            }
            override fun onServiceResolved(s: NsdServiceInfo) =
                macSeen(s.attributes, listOfNotNull(s.host?.hostAddress?.takeIf { '.' in it }))
        }
        runCatching { nsd.resolveService(info, l) }
    }

    private fun unwatch(cb: Any) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { nsd.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
        }
    }

    private fun stopNsd() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        nsdCallbacks.values.forEach(::unwatch)
        nsdCallbacks.clear()
    }
}
