package com.amurot.notchdrop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Patterns
import com.amurot.notchdrop.R
import com.amurot.notchdrop.core.ReceivedBatch
import com.amurot.notchdrop.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

object Notifier {
    const val CH_SERVICE = "service"
    const val CH_RECEIVED = "received"
    const val SERVICE_ID = 1
    private val ids = AtomicInteger(100)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Ready to receive", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps NotchDrop listening for your Mac"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RECEIVED, "Received from Mac", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Files, links and text sent from your Mac"
            }
        )
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun service(ctx: Context, state: NodeState): Notification {
        val mac = state.peer?.displayName
        val (title, text, progress) = when (val a = state.activity) {
            is Activity.Receiving -> Triple("Receiving from ${mac ?: "Mac"}", a.name, pct(a.done, a.total))
            is Activity.Sending -> Triple("Sending to ${mac ?: "Mac"}", a.name, pct(a.done, a.total))
            is Activity.Connecting -> Triple("Connecting to ${mac ?: "Mac"}…", null, -1)
            else -> when {
                mac == null -> Triple("Not paired", "Open NotchDrop to pair with your Mac", null)
                state.reachable -> Triple("Ready · $mac", if (state.route == Route.HOTSPOT) "Connected over hotspot" else "Connected over Wi-Fi", null)
                else -> Triple("Ready · $mac", "Waiting for the Mac on this network", null)
            }
        }
        return Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .apply { if (progress != null) setProgress(100, progress.coerceAtLeast(0), progress < 0) }
            .apply { if (state.busy) addAction(cancelAction(ctx)) }
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun cancelAction(ctx: Context) = Notification.Action.Builder(
        null, "Cancel",
        PendingIntent.getBroadcast(ctx, 1, Intent(ctx, CancelReceiver::class.java), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    private fun pct(done: Long, total: Long) = if (total <= 0) 100 else (done * 100 / total).toInt()

    fun received(ctx: Context, batch: ReceivedBatch, mac: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (batch.files.isNotEmpty()) {
            val one = batch.files.singleOrNull()
            val open = if (one != null) {
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(one.uri), one.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Places.folderIntent(ctx)
            }
            val id = ids.incrementAndGet()
            val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CH_RECEIVED)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(one?.name ?: "${batch.files.size} files from $mac")
                .setContentText(if (one != null) "From $mac · ${Node.saveLabel}" else batch.files.joinToString { it.name })
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        }
        for (text in batch.texts) {
            val id = ids.incrementAndGet()
            val isLink = Patterns.WEB_URL.matcher(text.trim()).matches()
            val copy = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, CopyReceiver::class.java).putExtra("text", text).putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val b = Notification.Builder(ctx, CH_RECEIVED)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(
                    when {
                        isLink -> "Link from $mac"
                        Node.store.autoCopyText && text == batch.texts.last() -> "Copied text from $mac"
                        else -> "Text from $mac"
                    }
                )
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Copy", copy).build())
            if (isLink) {
                val url = text.trim().let { if (it.contains("://")) it else "https://$it" }
                val open = PendingIntent.getActivity(
                    ctx, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                b.setContentIntent(open).addAction(Notification.Action.Builder(null, "Open", open).build())
            } else {
                b.setContentIntent(copy)
            }
            nm.notify(id, b.build())
        }
    }
}

/** The "Cancel" action on the progress notification. */
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Node.init(ctx)
        Node.cancelTransfer()
    }
}

class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From Mac", text))
        ctx.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("id", 0))
        // Android 13+ shows its own clipboard confirmation, so no toast.
    }
}
