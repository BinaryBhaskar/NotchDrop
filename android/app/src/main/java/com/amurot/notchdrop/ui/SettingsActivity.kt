package com.amurot.notchdrop.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurot.notchdrop.BuildConfig
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.NodeService
import com.amurot.notchdrop.service.Updates
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private var batteryOk by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotchTheme {
                SystemBars(this)
                SettingsScreen(
                    batteryOk = batteryOk,
                    onBack = ::finish,
                    onBattery = {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
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
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Node.uiVisible = true
        batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
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
    onReceiveToggle: (Boolean) -> Unit,
    onUnpair: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
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
                NdRow("Save location", description = "Downloads/From <Mac name>", icon = NdIcons.Folder, tint = Palette.dim)
            }
        }

        item {
            NdGroup("Battery") {
                AnimatedContent(batteryOk, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "battery") { ok ->
                    NdRow(
                        "Background activity",
                        description = if (ok) "Unrestricted — your Mac can always reach this phone"
                        else "Battery saving may pause NotchDrop, so the Mac can't reach it",
                        icon = NdIcons.Battery,
                        tint = if (ok) Palette.ok else Palette.warn,
                        onClick = if (ok) null else onBattery,
                    ) { if (!ok) NdButton("Allow", onBattery, style = NdButtonStyle.Tonal) }
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
