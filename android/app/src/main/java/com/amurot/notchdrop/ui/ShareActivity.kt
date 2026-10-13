package com.amurot.notchdrop.ui

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.amurot.notchdrop.core.OutgoingItem
import com.amurot.notchdrop.service.Activity
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.NodeState
import com.amurot.notchdrop.service.Outgoing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Phase {
    data object Preparing : Phase
    data object Working : Phase
    data class Done(val summary: String) : Phase
    data class Failed(val message: String) : Phase
    data object NotPaired : Phase
}

/** Share-sheet target (and the clipboard tile): a small sheet that sends straight to the paired Mac. */
class ShareActivity : ComponentActivity() {
    companion object {
        const val ACTION_SEND_CLIPBOARD = "com.amurot.notchdrop.SEND_CLIPBOARD"
    }

    private var phase by mutableStateOf<Phase>(Phase.Preparing)
    private var items: List<OutgoingItem> = emptyList()
    private var clipboardPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotchTheme {
                val state by Node.state.collectAsStateWithLifecycle()
                Sheet(state, phase, onDismiss = ::dismiss, onRetry = ::start, onOpenApp = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                })
            }
        }
        if (Node.store.peer == null) {
            phase = Phase.NotPaired
            return
        }
        if (intent.action == ACTION_SEND_CLIPBOARD) {
            clipboardPending = true // Clipboard can only be read once the window has focus.
        } else {
            lifecycleScope.launch {
                items = withContext(Dispatchers.IO) { runCatching { collect(intent) }.getOrDefault(emptyList()) }
                if (items.isEmpty()) phase = Phase.Failed("Nothing to send") else start()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && clipboardPending) {
            clipboardPending = false
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            lifecycleScope.launch {
                items = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            item?.uri != null && item.text == null -> Outgoing.fromUris(this@ShareActivity, listOf(item.uri))
                            else -> item?.coerceToText(this@ShareActivity)?.toString()?.takeIf { it.isNotBlank() }
                                ?.let { listOf(OutgoingItem.Text(it)) }.orEmpty()
                        }
                    }.getOrDefault(emptyList())
                }
                if (items.isEmpty()) phase = Phase.Failed("Clipboard is empty") else start()
            }
        }
    }

    private fun collect(intent: Intent): List<OutgoingItem> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) return Outgoing.fromUris(this, uris)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(OutgoingItem.Text(text))
    }

    private fun start() {
        if (items.isEmpty()) return
        phase = Phase.Working
        val summary = Outgoing.summary(items)
        lifecycleScope.launch {
            // Runs in Node's scope; this activity stays alive (backgrounded if dismissed) so shared URI grants hold.
            val result = Node.scope.async { runCatching { Node.sendNow(items, summary) } }.await()
            val a = Node.state.value.activity
            phase = when {
                a is Activity.Failed -> Phase.Failed(a.message)
                result.isFailure -> Phase.Failed(result.exceptionOrNull()?.message ?: "Send failed")
                else -> Phase.Done(summary)
            }
            if (phase is Phase.Done) {
                delay(1100)
                finish()
            }
        }
    }

    private fun dismiss() {
        if (phase == Phase.Working) moveTaskToBack(true) else finish()
    }
}

@Composable
private fun Sheet(state: NodeState, phase: Phase, onDismiss: () -> Unit, onRetry: () -> Unit, onOpenApp: () -> Unit) {
    val mac = state.peer?.displayName ?: "Mac"
    val back = rememberPredictiveBack(onBack = onDismiss)
    // Rises into place on open; follows the back gesture down and in before dismissing.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, NdSprings.snappy()) }
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp).navigationBarsPadding()
                .graphicsLayer {
                    val p = back.progress.value
                    translationY = (1f - enter.value) * size.height + p * 48.dp.toPx()
                    scaleX = 1f - 0.08f * p
                    scaleY = 1f - 0.08f * p
                    alpha = enter.value.coerceIn(0f, 1f)
                }
                .clip(NdShapes.sheet).background(Palette.surface).border(1.dp, Palette.line, NdShapes.sheet)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val busy = phase == Phase.Working || phase == Phase.Preparing
            Box(
                Modifier.fillMaxWidth().height(120.dp)
                    .dashedOutline(Palette.line, active = busy, radius = 22.dp, phase = marchingPhase()),
                contentAlignment = Alignment.Center,
            ) {
                val (icon, tint) = when (phase) {
                    is Phase.Done -> NdIcons.Check to Palette.ok
                    is Phase.Failed, Phase.NotPaired -> NdIcons.Warning to Palette.bad
                    else -> NdIcons.Laptop to Palette.accent
                }
                AnimatedContent(icon to tint, transitionSpec = {
                    scaleIn(NdSprings.snappy(), initialScale = 0.6f) + fadeIn() togetherWith scaleOut() + fadeOut()
                }, label = "phaseIcon") { (i, t) -> NdIconTile(i, t, size = 64.dp) }
            }
            Spacer(Modifier.height(16.dp))
            val a = state.activity
            when (phase) {
                Phase.NotPaired -> {
                    Title("Not paired yet")
                    Sub("Pair NotchDrop with your Mac first.")
                    Spacer(Modifier.height(16.dp))
                    NdButton("Open NotchDrop", onOpenApp, icon = NdIcons.Open)
                }
                is Phase.Failed -> {
                    Title("Couldn't send to $mac")
                    Sub(phase.message)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NdButton("Close", onDismiss, style = NdButtonStyle.Text)
                        NdButton("Retry", onRetry, icon = NdIcons.Refresh)
                    }
                }
                is Phase.Done -> {
                    Title("Sent to $mac")
                    Sub(phase.summary)
                }
                else -> {
                    Title(if (a is Activity.Sending) "Sending to $mac" else "Connecting to $mac…")
                    Sub(if (a is Activity.Sending) "${a.name} · ${formatBytes(a.done)} of ${formatBytes(a.total)}" else "Same Wi-Fi or hotspot")
                    Spacer(Modifier.height(14.dp))
                    ProgressBar(if (a is Activity.Sending && a.total > 0) a.done.toFloat() / a.total else null)
                }
            }
        }
    }
}

@Composable
private fun Title(s: String) = Text(s, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Sub(s: String) = Text(s, color = Palette.dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
