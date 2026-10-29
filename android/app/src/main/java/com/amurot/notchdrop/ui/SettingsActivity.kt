package com.amurot.notchdrop.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurot.notchdrop.BuildConfig
import com.amurot.notchdrop.control.PhoneControlService
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.NodeService
import com.amurot.notchdrop.service.Updates
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private var batteryOk by mutableStateOf(true)
    private var controlEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotchTheme {
                SystemBars(this)
                SettingsScreen(
                    batteryOk = batteryOk,
                    onBack = ::finish,
                    onBattery = ::openBatterySettings,
                    onPickFolder = { tree ->
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        runCatching { contentResolver.takePersistableUriPermission(tree, flags) }
                            .onSuccess { Node.setSaveTree(tree) }
                            .onFailure { Toast.makeText(this, "Couldn't use that folder", Toast.LENGTH_SHORT).show() }
                    },
                    onReceiveToggle = { on ->
                        Node.store.receiveEnabled = on
                        if (on) NodeService.start(this) else NodeService.stop(this)
                    },
                    onUnpair = {
                        Node.unpair()
                        NodeService.stop(this)
                    },
                    onOpenUrl = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                    controlEnabled = controlEnabled,
                    onControlSettings = { PhoneControlService.openSettings(this) },
                    onAppInfo = { PhoneControlService.openAppInfo(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Node.uiVisible = true
        batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        controlEnabled = PhoneControlService.isEnabled(this)
    }

    override fun onPause() {
        super.onPause()
        Node.uiVisible = false
    }
}

@Composable
private fun SettingsScreen(
    batteryOk: Boolean,
    onBack: () -> Unit,
    onBattery: () -> Unit,
    onPickFolder: (Uri) -> Unit,
    onReceiveToggle: (Boolean) -> Unit,
    onUnpair: () -> Unit,
    onOpenUrl: (String) -> Unit,
    controlEnabled: Boolean,
    onControlSettings: () -> Unit,
    onAppInfo: () -> Unit,
) {
    val controlOn by PhoneControlService.running.collectAsStateWithLifecycle()
    val state by Node.state.collectAsStateWithLifecycle()
    val theme by Node.themeMode.collectAsStateWithLifecycle()
    val store = Node.store
    var receive by remember { mutableStateOf(store.receiveEnabled) }
    var autoCopy by remember { mutableStateOf(store.autoCopyText) }
    var openLinks by remember { mutableStateOf(store.openLinks) }
    var renaming by remember { mutableStateOf<RenameTarget?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var autoUpdate by remember { mutableStateOf(store.autoUpdateCheck) }
    var update by remember { mutableStateOf<Updates.Release?>(null) }
    val updateStatus by Updates.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(onPickFolder) }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NdIconButton(NdIcons.Back, "Back", onBack)
                Spacer(Modifier.width(14.dp))
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            }
        }

        item {
            NdGroup("Names") {
                NdRow("This phone", description = state.deviceName, icon = NdIcons.Phone, onClick = { renaming = RenameTarget.Device }) {
                    NdIcon(NdIcons.Edit, size = 18.dp, tint = Palette.dim)
                }
                state.peer?.let {
                    NdDivider()
                    NdRow("Paired Mac", description = it.displayName, icon = NdIcons.Laptop, tint = Palette.accent2, onClick = { renaming = RenameTarget.Peer }) {
                        NdIcon(NdIcons.Edit, size = 18.dp, tint = Palette.dim)
                    }
                }
            }
        }

        item {
            NdGroup("Receiving") {
                ToggleRow("Receive in the background", "Keeps a small notification so your Mac can send anytime", NdIcons.Notification, receive) {
                    receive = it; onReceiveToggle(it)
                }
                NdDivider()
                ToggleRow("Copy received text", "Text from the Mac goes straight to the clipboard", NdIcons.Copy, autoCopy) {
                    autoCopy = it; store.autoCopyText = it
                }
                NdDivider()
                ToggleRow("Open received links", "Opens in the browser while NotchDrop is on screen; otherwise tap the notification", NdIcons.Link, openLinks) {
                    openLinks = it; store.openLinks = it
                }
                NdDivider()
                NdRow(
                    "Save location",
                    description = state.saveFolder ?: "Download/From ${state.peer?.displayName ?: "<Mac name>"}",
                    icon = NdIcons.Folder,
                    tint = Palette.accent2,
                    onClick = { pickFolder.launch(null) },
                ) {
                    AnimatedVisibility(state.saveFolder != null, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        NdButton("Reset", { Node.setSaveTree(null) }, style = NdButtonStyle.Text)
                    }
                }
            }
        }

        item {
            NdGroup("Battery") {
                AnimatedContent(batteryOk, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "battery") { ok ->
                    NdRow(
                        "Background activity",
                        description = if (ok) "Unrestricted — your Mac can always reach this phone"
                        else "Battery saving may pause NotchDrop. Set battery use to Unrestricted.",
                        icon = NdIcons.Battery,
                        tint = if (ok) Palette.ok else Palette.warn,
                        onClick = if (ok) null else onBattery,
                    ) { if (!ok) NdButton("Open", onBattery, style = NdButtonStyle.Tonal) }
                }
            }
        }

        item {
            NdGroup("Phone control") {
                AnimatedContent(controlOn to controlEnabled, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "control") { (on, enabled) ->
                    NdRow(
                        "Control from Mac",
                        description = when {
                            on -> "On — your Mac can tap and type here from the mirror window, or by moving its pointer off the screen edge"
                            enabled -> "Switched on, starting… If it stays here, turn it off and on again in Accessibility settings."
                            else -> "Off. Turn on NotchDrop under Accessibility › Installed apps to use your Mac's mouse and keyboard here."
                        },
                        icon = NdIcons.Pointer,
                        tint = if (on) Palette.ok else Palette.warn,
                        onClick = onControlSettings,
                    ) { NdButton(if (on) "Settings" else "Turn on", onControlSettings, style = NdButtonStyle.Tonal) }
                }
                AnimatedVisibility(!controlOn, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        NdDivider()
                        NdRow(
                            "Greyed out or “Restricted setting”?",
                            description = "Android blocks this for apps installed outside the Play Store. Open App info, tap ⋮ in the corner, choose “Allow restricted settings”, then turn it on again.",
                            icon = NdIcons.Shield,
                            tint = Palette.accent2,
                            onClick = onAppInfo,
                        ) { NdButton("App info", onAppInfo, style = NdButtonStyle.Text) }
                    }
                }
            }
        }

        item {
            NdGroup("Setup guide") {
                GUIDE.forEachIndexed { i, g ->
                    if (i > 0) NdDivider()
                    GuideRow(g)
                }
            }
        }

        item {
            NdGroup("Appearance") {
                Row(Modifier.padding(16.dp)) {
                    NdSegmented(
                        options = listOf("System" to NdIcons.Auto, "Dark" to NdIcons.Moon, "Light" to NdIcons.Sun),
                        selected = ThemeMode.entries.indexOf(theme),
                        onSelect = { Node.setTheme(ThemeMode.entries[it]) },
                    )
                }
            }
        }

        item {
            NdGroup("About") {
                NdRow("Version", icon = NdIcons.Info) { Text(BuildConfig.VERSION_NAME, color = Palette.dim, fontSize = 14.sp) }
                NdDivider()
                ToggleRow("Auto-check for updates", "Once a day, asks GitHub for the latest release", NdIcons.Refresh, autoUpdate) {
                    autoUpdate = it; store.autoUpdateCheck = it
                }
                NdDivider()
                NdRow(
                    "Check now",
                    description = when (val st = updateStatus) {
                        Updates.Status.Idle -> "Look for a newer version on GitHub"
                        Updates.Status.Checking -> "Checking…"
                        Updates.Status.UpToDate -> "You're on the latest version"
                        is Updates.Status.Available -> "Version ${st.release.version} is available"
                        Updates.Status.Failed -> "Couldn't reach GitHub"
                    },
                    icon = NdIcons.Down,
                    tint = if (updateStatus is Updates.Status.Available) Palette.ok else Palette.accent,
                    onClick = { scope.launch { Updates.check(store, manual = true)?.let { update = it } } },
                )
                NdDivider()
                NdRow("Source code", description = "github.com/BinaryBhaskar/NotchDrop", icon = NdIcons.Code, tint = Palette.accent2,
                    onClick = { onOpenUrl("https://github.com/BinaryBhaskar/NotchDrop") }) {
                    NdIcon(NdIcons.Open, size = 18.dp, tint = Palette.dim)
                }
                NdDivider()
                NdRow("Created by Amurot", description = "amurot.com", icon = NdIcons.Amurot, tint = Palette.text,
                    onClick = { onOpenUrl("https://amurot.com") }) {
                    NdIcon(NdIcons.Open, size = 18.dp, tint = Palette.dim)
                }
                NdDivider()
                NdRow("Private by design", description = "Everything stays on your local network and is end-to-end encrypted. MIT License.",
                    icon = NdIcons.Shield, tint = Palette.ok)
            }
        }

        state.peer?.let { peer ->
            item {
                NdGroup(null) {
                    NdRow("Unpair ${peer.displayName}", icon = NdIcons.Delete, tint = Palette.bad, titleColor = Palette.bad,
                        onClick = { confirmUnpair = true })
                }
            }
        }
    }

    when (renaming) {
        RenameTarget.Device -> RenameDialog(
            title = "Name this phone",
            icon = NdIcons.Phone,
            hint = "Shown on your Mac. Leave empty to use “${store.systemName}”.",
            initial = store.customName.orEmpty(),
            onDismiss = { renaming = null },
            onSave = { renaming = null; Node.renameThisDevice(it) },
        )
        RenameTarget.Peer -> RenameDialog(
            title = "Nickname for your Mac",
            icon = NdIcons.Laptop,
            hint = "Only used on this phone. Leave empty to use “${state.peer?.name.orEmpty()}”.",
            initial = state.peer?.alias.orEmpty(),
            onDismiss = { renaming = null },
            onSave = { renaming = null; Node.renamePeer(it) },
        )
        null -> {}
    }

    update?.let { r ->
        UpdateDialog(
            r,
            onDownload = { update = null; onOpenUrl(r.apk ?: r.page) },
            onLater = { update = null },
            onSkip = { update = null; store.skippedVersion = r.version },
        )
    }

    if (confirmUnpair) {
        NdDialog(
            onDismiss = { confirmUnpair = false },
            title = "Unpair?",
            icon = NdIcons.Delete,
            tint = Palette.bad,
            description = "You'll need to scan the QR code on your Mac again to reconnect.",
        ) {
            NdButton("Cancel", { confirmUnpair = false }, style = NdButtonStyle.Text)
            NdButton("Unpair", { confirmUnpair = false; onUnpair() }, style = NdButtonStyle.Danger)
        }
    }
}

private enum class RenameTarget { Device, Peer }

@Composable
private fun ToggleRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    NdRow(title, description = subtitle, icon = icon, onClick = { onChange(!checked) }) {
        NdSwitch(checked, onChange)
    }
}

@Composable
private fun RenameDialog(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    hint: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val save = { onSave(text.trim().ifEmpty { null }) }
    NdDialog(
        onDismiss = onDismiss,
        title = title,
        icon = icon,
        content = {
            NdTextField(text, { text = it.take(40) }, placeholder = "Name", onDone = save)
            Spacer(Modifier.height(8.dp))
            Text(hint, color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
        },
    ) {
        NdButton("Cancel", onDismiss, style = NdButtonStyle.Text)
        NdButton("Save", save)
    }
}

private class Guide(val title: String, val summary: String, val steps: List<String>, val icon: @Composable () -> ImageVector)

private val GUIDE = listOf(
    Guide("Pair with your Mac", "One QR scan", listOf(
        "Install NotchDrop on the Mac (the DMG from GitHub Releases) and open it.",
        "On the Mac, choose Pair Phone… in the menu bar.",
        "Here, tap Scan to pair and point the camera at the QR code.",
        "Keep both on the same Wi-Fi, or join the Mac to this phone's hotspot.",
    )) { NdIcons.QrScan },
    Guide("Send and receive", "Share sheet, clipboard, background receiving", listOf(
        "To send: Share → NotchDrop from any app, or tap Clipboard on the main screen or the widget.",
        "Add the Quick Settings tile to send the clipboard from anywhere.",
        "Turn on Receive in the background above so the Mac can send any time, and set Battery to Unrestricted.",
        "On the Mac, drag onto the notch, or use Share → NotchDrop.",
    )) { NdIcons.Up },
    Guide("Camera on the Mac", "Live camera, up to 4K", listOf(
        "Tap Camera to Mac on the main screen, or choose Phone Camera in the Mac's menu bar.",
        "Allow the camera the first time. A notification shows while it's live; tap Stop there to end it.",
        "Switch lens, zoom, torch and quality from the Mac window. The shutter saves a full-size photo to the Mac.",
        "For Zoom, FaceTime or Chrome: install OBS on the Mac, click OBS in the camera window and follow its three steps, then pick “OBS Virtual Camera” in the call.",
    )) { NdIcons.Camera },
    Guide("Mirror this screen", "Show and control the phone on the Mac", listOf(
        "Tap Mirror screen, or choose Phone Screen on the Mac.",
        "Android asks every time: pick “Entire screen” and tap Start.",
        "To click and type in the Mac window, turn on Phone control above.",
    )) { NdIcons.Cast },
    Guide("Mac mouse and keyboard", "Move the pointer off the Mac's edge", listOf(
        "Turn on Phone control above. If it's greyed out, use App info › ⋮ › Allow restricted settings first.",
        "On the Mac: Settings › Phone › Move the pointer to the phone, then drag the phone in Arrangement to where it sits next to the screen.",
        "Allow NotchDrop under Accessibility on the Mac when asked.",
        "Push the pointer into the phone to use it here. Move back out, or press ⌘ Esc, to return. Right-click is Back. If Wi-Fi drops, the Mac gets its pointer back by itself.",
        "Drag files into the phone on the Mac to send them here.",
    )) { NdIcons.Pointer },
    Guide("Desktop mode (DeX-style)", "A desktop-sized screen in a Mac window", listOf(
        "Turn on Developer options (Settings › About phone › Software information › tap Build number 7 times).",
        "In Developer options, turn on Wireless debugging.",
        "On the Mac, install scrcpy (brew install scrcpy) and choose Phone Desktop… in the menu bar.",
        "Tap Pair device with pairing code here, and type the IP:port and code into the Mac window.",
        "Click Find, then Connect, then Open Desktop. Samsung phones on One UI 7 show a DeX-style taskbar there.",
    )) { NdIcons.Laptop },
)

@Composable
private fun GuideRow(g: Guide) {
    var open by remember { mutableStateOf(false) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Column {
        NdRow(g.title, description = g.summary, icon = g.icon(), onClick = { open = !open }) {
            NdIcon(NdIcons.ChevronRight, Modifier.rotate(turn), size = 18.dp, tint = Palette.dim)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                g.steps.forEachIndexed { i, step ->
                    Row {
                        Text("${i + 1}", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(18.dp))
                        Text(step, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
