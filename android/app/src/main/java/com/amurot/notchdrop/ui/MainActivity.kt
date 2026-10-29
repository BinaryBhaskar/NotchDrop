package com.amurot.notchdrop.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import com.amurot.notchdrop.BuildConfig
import com.amurot.notchdrop.service.Places
import com.amurot.notchdrop.stream.StreamActivity
import com.amurot.notchdrop.stream.StreamService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.amurot.notchdrop.core.OutgoingItem
import com.amurot.notchdrop.service.Activity
import com.amurot.notchdrop.service.HistoryEntry
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.NodeService
import com.amurot.notchdrop.service.NodeState
import com.amurot.notchdrop.service.Outgoing
import com.amurot.notchdrop.service.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_PICK_FILES = "com.amurot.notchdrop.PICK_FILES"
        const val ACTION_OPEN_FOLDER = "com.amurot.notchdrop.OPEN_FOLDER"
        const val ACTION_SCAN = "com.amurot.notchdrop.SCAN"
    }

    private var batteryOk by mutableStateOf(true)
    private var askUpdates by mutableStateOf(false)
    private var update by mutableStateOf<Updates.Release?>(null)
    /** Set by app shortcuts that need the screen (the file picker). */
    private var launchAction by mutableStateOf<String?>(null)
    private var undo by mutableStateOf<Undo?>(null)
    private var undoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) handleShortcut(intent)
        setContent {
            NotchTheme {
                SystemBars(this)
                val state by Node.state.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    batteryOk = batteryOk,
                    launchAction = launchAction,
                    onLaunchHandled = { launchAction = null },
                    undo = undo,
                    onScan = { startActivity(Intent(this, ScanActivity::class.java)) },
                    onSendUris = ::sendUris,
                    onSendClipboard = ::sendClipboard,
                    onRefresh = { Node.scheduleRefresh(0) },
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onBattery = ::openBatterySettings,
                    onDismissBattery = {
                        Node.store.batteryPromptDismissed = true
                        batteryOk = true
                    },
                    onAction = ::act,
                    onClearRecents = {
                        val all = state.history
                        Node.clearHistory()
                        offerUndo("Cleared ${all.size} recent ${if (all.size == 1) "item" else "items"}", all)
                    },
                    onDismissActivity = Node::dismissActivity,
                    onCancelTransfer = Node::cancelTransfer,
                )
                if (askUpdates) {
                    UpdateOptInDialog { on ->
                        askUpdates = false
                        Node.store.autoUpdateCheck = on
                        if (on) checkForUpdate()
                    }
                }
                update?.let { r ->
                    UpdateDialog(
                        r,
                        onDownload = { update = null; openUrl(r.apk ?: r.page) },
                        onLater = { update = null },
                        onSkip = { update = null; Node.store.skippedVersion = r.version },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    private fun handleShortcut(intent: Intent?) {
        when (intent?.action) {
            ACTION_SCAN -> startActivity(Intent(this, ScanActivity::class.java))
            ACTION_OPEN_FOLDER -> Places.openFolder(this)
            ACTION_PICK_FILES -> launchAction = ACTION_PICK_FILES
        }
    }

    override fun onResume() {
        super.onResume()
        Node.uiVisible = true
        NodeService.start(this)
        Node.scheduleRefresh(0)
        Node.pruneHistory()
        batteryOk = Node.store.batteryPromptDismissed ||
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        // Ask about automatic update checks once, after pairing so it doesn't crowd the first run.
        if (!Node.store.updatePromptShown && Node.store.peer != null) {
            Node.store.updatePromptShown = true
            askUpdates = true
        }
        checkForUpdate()
    }

    private fun checkForUpdate() {
        lifecycleScope.launch { Updates.check(Node.store, manual = false)?.let { update = it } }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun onPause() {
        super.onPause()
        Node.uiVisible = false
    }

    private fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { Outgoing.fromUris(this@MainActivity, uris) } }
            items.onSuccess { Node.send(it, Outgoing.summary(it)) }
                .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun sendClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.coerceToText(this)?.toString()
        when {
            item?.uri != null && item.text == null -> sendUris(listOf(item.uri))
            !text.isNullOrBlank() -> {
                val items = listOf(OutgoingItem.Text(text))
                Node.send(items, Outgoing.summary(items))
            }
            else -> Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun act(e: HistoryEntry, action: RecentAction) {
        val uri = e.uri?.let(Uri::parse)
        when (action) {
            RecentAction.Open -> when {
                uri != null -> runCatching { Places.open(this, uri, e.mime ?: Places.mimeOf(this, uri, e.title)) }
                    .onFailure { toast("No app can open this file") }
                e.text != null && Patterns.WEB_URL.matcher(e.text.trim()).matches() ->
                    openUrl(e.text.trim().let { if ("://" in it) it else "https://$it" })
                e.text != null -> copy(e.text)
                else -> toast("Sent files stay where they were on this phone")
            }
            RecentAction.ShowInFiles -> Places.openFolder(this)
            RecentAction.Share -> runCatching { Places.share(this, uri, e.mime, e.text) }
            RecentAction.Copy -> e.text?.let(::copy)
            RecentAction.Resend -> if (Node.resend(e) == null) toast("This file is no longer there")
            RecentAction.Remove -> {
                Node.removeHistory(e.id)
                offerUndo("Removed from Recent", listOf(e))
            }
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NotchDrop", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Copied")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun offerUndo(message: String, entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        val u = Undo(message, entries) { Node.restoreHistory(entries); undo = null }
        undo = u
        undoJob?.cancel()
        undoJob = lifecycleScope.launch {
            delay(5000)
            if (undo === u) undo = null
        }
    }
}

/** Something just removed, with the way to put it back. */
class Undo(val message: String, val entries: List<HistoryEntry>, val restore: () -> Unit)

/**
 * Opens this app's battery page. Apps outside Play's allowed categories may not ask for the exemption directly,
 * so the user picks "Unrestricted" there.
 */
fun ComponentActivity.openBatterySettings() {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    runCatching { startActivity(details) }
        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

@Composable
private fun MainScreen(
    state: NodeState,
    batteryOk: Boolean,
    launchAction: String?,
    onLaunchHandled: () -> Unit,
    undo: Undo?,
    onScan: () -> Unit,
    onSendUris: (List<Uri>) -> Unit,
    onSendClipboard: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onBattery: () -> Unit,
    onDismissBattery: () -> Unit,
    onAction: (HistoryEntry, RecentAction) -> Unit,
    onClearRecents: () -> Unit,
    onDismissActivity: () -> Unit,
    onCancelTransfer: () -> Unit,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onSendUris(it) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onSendUris(it) }
    var menuFor by remember { mutableStateOf<HistoryEntry?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(launchAction) {
        if (launchAction == MainActivity.ACTION_PICK_FILES && state.peer != null) pick.launch(arrayOf("*/*"))
        if (launchAction != null) onLaunchHandled()
    }

    val history = state.history
    val shown = remember(history, query) {
        val q = query.trim()
        if (q.isEmpty()) history else history.filter { it.title.contains(q, true) || it.text?.contains(q, true) == true }
    }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "top") {
                Row(Modifier.entrance(0).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("NotchDrop", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    Spacer(Modifier.weight(1f))
                    AnimatedVisibility(state.peer != null, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        NdIconButton(NdIcons.Refresh, "Refresh", onRefresh)
                    }
                    Spacer(Modifier.width(8.dp))
                    NdIconButton(NdIcons.Settings, "Settings", onSettings)
                }
            }

            item(key = "mac") { MacCard(state, onScan, Modifier.animateItem().entrance(1)) }

            item(key = "activity") {
                AnimatedVisibility(
                    state.activity != Activity.Idle,
                    enter = expandVertically(NdSprings.smooth()) + fadeIn(),
                    exit = shrinkVertically(NdSprings.smooth()) + fadeOut(),
                ) { ActivityCard(state, onDismissActivity, onCancelTransfer) }
            }

            if (state.peer != null) {
                item(key = "actions") {
                    Row(Modifier.animateItem(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionTile(NdIcons.Image, "Photos", Palette.accent, Modifier.weight(1f).entrance(2), enabled = !state.busy) {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        ActionTile(NdIcons.Folder, "Files", Palette.accent2, Modifier.weight(1f).entrance(3), enabled = !state.busy) {
                            pick.launch(arrayOf("*/*"))
                        }
                        ActionTile(NdIcons.Paste, "Clipboard", Palette.ok, Modifier.weight(1f).entrance(4), enabled = !state.busy, onClick = onSendClipboard)
                    }
                }
                item(key = "streams") {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val live by StreamService.active.collectAsStateWithLifecycle()
                    Row(Modifier.animateItem(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((kind, icon, label) in listOf(
                            Triple(StreamService.KIND_CAMERA, NdIcons.Camera, "Camera to Mac"),
                            Triple(StreamService.KIND_SCREEN, NdIcons.Cast, "Mirror screen"),
                        )) {
                            val on = kind in live
                            ActionTile(
                                icon, if (on) "Live · Stop" else label, if (on) Palette.bad else Palette.accent2,
                                Modifier.weight(1f).entrance(5), enabled = true,
                            ) {
                                if (on) StreamService.stop(ctx, kind) else ctx.startActivity(StreamActivity.intent(ctx, kind))
                            }
                        }
                    }
                }
            }

            if (!batteryOk && state.peer != null) {
                item(key = "battery") {
                    NdCard(Modifier.animateItem()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NdIconTile(NdIcons.Battery, Palette.warn, size = 40.dp)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text("Allow background receiving", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Battery saving may pause NotchDrop, so the Mac can't reach it. Set battery use to Unrestricted.",
                                    color = Palette.dim, fontSize = 13.sp, lineHeight = 17.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                            NdButton("Not now", onDismissBattery, style = NdButtonStyle.Text)
                            NdButton("Open settings", onBattery, style = NdButtonStyle.Tonal)
                        }
                    }
                }
            }

            if (state.peer != null && history.isEmpty()) {
                item(key = "tip") {
                    Column(
                        Modifier.animateItem().fillMaxWidth().padding(top = 28.dp).entrance(5),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NdIcon(NdIcons.Share, size = 28.dp, tint = Palette.dim)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Nothing here yet. In any app, tap Share → NotchDrop.\nOn the Mac, drag anything onto the notch.",
                            color = Palette.dim, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            if (history.isNotEmpty()) {
                item(key = "recentHeader") {
                    Row(Modifier.animateItem().padding(top = 10.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("RECENT", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        Spacer(Modifier.width(8.dp))
                        AnimatedContent(history.size, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "count") { n ->
                            Text("$n", color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.ndClickable(pressedScale = 0.92f, shape = NdShapes.control, rippleColor = Palette.bad, onClick = onClearRecents)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NdIcon(NdIcons.Sweep, size = 16.dp, tint = Palette.dim)
                            Spacer(Modifier.width(6.dp))
                            Text("Clear", color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (history.size > 8) {
                    item(key = "search") {
                        NdTextField(
                            query, { query = it.take(60) }, Modifier.animateItem(),
                            placeholder = "Search recent", leading = NdIcons.Search, imeAction = ImeAction.Search,
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "noMatch") {
                        Text(
                            "No matches for “${query.trim()}”", color = Palette.dim, fontSize = 13.sp,
                            modifier = Modifier.animateItem().fillMaxWidth().padding(vertical = 18.dp), textAlign = TextAlign.Center,
                        )
                    }
                }
                items(shown, key = { it.id }) { e ->
                    RecentRow(
                        e,
                        onAction = { onAction(e, it) },
                        onMenu = { menuFor = e },
                        modifier = Modifier.animateItem(fadeInSpec = tween(220), placementSpec = NdSprings.smooth(), fadeOutSpec = tween(160)),
                    )
                }
            }
        }

        AnimatedVisibility(
            undo != null,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            enter = slideInVertically(NdSprings.snappy()) { it * 2 } + fadeIn(),
            exit = slideOutVertically(NdSprings.smooth()) { it * 2 } + fadeOut(),
        ) {
            // Keep the last message while sliding out.
            var last by remember { mutableStateOf(undo) }
            if (undo != null) last = undo
            last?.let { UndoBar(it.message, it.restore) }
        }
    }

    menuFor?.let { e -> RecentMenu(e, onDismiss = { menuFor = null }, onAction = { onAction(e, it) }) }
}

/** Fades and lifts content in once, staggered by [index], the first time it's shown. */
@Composable
private fun Modifier.entrance(index: Int): Modifier {
    val shown = rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (shown.value) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!shown.value) {
            delay(40L * index)
            progress.animateTo(1f, NdSprings.smooth())
            shown.value = true
        }
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}

@Composable
private fun MacCard(state: NodeState, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val peer = state.peer
    val phase = marchingPhase()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Palette.surface)
            .dashedOutline(if (peer == null) Palette.accent.copy(alpha = 0.6f) else Palette.line, active = state.busy, radius = 26.dp, phase = phase)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // A slow breathing halo while the Mac is reachable.
            if (peer != null && state.reachable) {
                val breath by rememberInfiniteTransition(label = "breath").animateFloat(
                    0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breath",
                )
                Box(
                    Modifier.size(68.dp).scale(1.1f + 0.25f * breath)
                        .graphicsLayer { alpha = 0.28f * (1f - breath) + 0.06f }
                        .background(Palette.ok, CircleShape)
                )
            }
            NdIconTile(NdIcons.Laptop, Palette.accent, size = 68.dp)
        }
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            peer,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() using SizeTransform(clip = false) },
            label = "mac",
        ) { p ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (p == null) {
                    Text("Pair with your Mac", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "On the Mac, choose Pair Phone… from the NotchDrop menu-bar icon, then scan the code. " +
                            "Use the same Wi-Fi, or put the Mac on this phone's hotspot.",
                        color = Palette.dim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    NdButton("Scan to pair", onScan, icon = NdIcons.QrScan)
                } else {
                    Text(p.displayName, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    StatusChip(state)
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(state: NodeState, onDismiss: () -> Unit, onCancel: () -> Unit) {
    val a = state.activity
    val mac = state.peer?.displayName ?: "Mac"
    val (title, sub, fraction, color) = when (a) {
        is Activity.Connecting -> Quad("Connecting to $mac…", "", null, Palette.accent)
        is Activity.Sending -> Quad("Sending to $mac", "${a.name} · ${formatBytes(a.done)} of ${formatBytes(a.total)}", frac(a.done, a.total), Palette.accent)
        is Activity.Receiving -> Quad("Receiving from $mac", "${a.name} · ${formatBytes(a.done)} of ${formatBytes(a.total)}", frac(a.done, a.total), Palette.accent2)
        is Activity.Sent -> Quad("Sent to $mac", a.summary, 1f, Palette.ok)
        is Activity.Received -> Quad(
            "Received from $mac",
            (a.batch.files.map { it.name } + a.batch.texts.map { "“${it.take(40)}”" }).joinToString(),
            1f, Palette.ok,
        )
        is Activity.Failed -> Quad("Couldn't complete", a.message, 0f, Palette.bad)
        is Activity.Cancelled -> Quad("Transfer cancelled", a.message, 0f, Palette.dim)
        Activity.Idle -> Quad("", "", 0f, Palette.dim)
    }
    val icon = when (a) {
        is Activity.Sending, is Activity.Connecting -> NdIcons.Up
        is Activity.Receiving -> NdIcons.Down
        is Activity.Failed -> NdIcons.Warning
        is Activity.Cancelled -> NdIcons.Close
        else -> NdIcons.Check
    }
    NdCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(icon to color, transitionSpec = { scaleIn(NdSprings.snappy()) togetherWith scaleOut() }, label = "actIcon") { (i, c) ->
                NdIconTile(i, c, size = 40.dp)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (sub.isNotEmpty()) Text(sub, color = Palette.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val resumable = state.resumable.takeIf { a is Activity.Failed }
            when {
                state.busy -> NdButton("Cancel", onCancel, style = NdButtonStyle.Text)
                resumable != null -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NdButton("OK", onDismiss, style = NdButtonStyle.Text)
                    NdButton(if (resumable.sentAny) "Resume" else "Retry", { Node.resumeSend() }, style = NdButtonStyle.Text)
                }
                else -> NdButton("OK", onDismiss, style = NdButtonStyle.Text)
            }
        }
        AnimatedVisibility(state.busy) {
            Column {
                Spacer(Modifier.height(14.dp))
                ProgressBar(fraction)
            }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: Float?, val d: Color)

private fun frac(done: Long, total: Long): Float = if (total <= 0) 1f else done.toFloat() / total

@Composable
private fun ActionTile(icon: ImageVector, label: String, tint: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier
            .ndClickable(enabled, pressedScale = 0.92f, shape = NdShapes.card, rippleColor = tint, onClick = onClick)
            .background(Palette.surface)
            .border(1.dp, Palette.line, NdShapes.card)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NdIconTile(icon, if (enabled) tint else Palette.dim, size = 44.dp)
        Spacer(Modifier.height(10.dp))
        Text(label, color = if (enabled) Palette.text else Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
