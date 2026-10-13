package app.notchdrop.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.notchdrop.R
import app.notchdrop.ui.ShareActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that keeps the phone reachable so the Mac can send to it at any time. */
class NodeService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        Node.init(this)
        startForeground(Notifier.SERVICE_ID, Notifier.service(this, Node.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Node.startListening()
        val nm = getSystemService(NotificationManager::class.java)
        lifecycleScope.launch {
            Node.state
                .map { Triple(it.activity.coarse(), it.reachable to it.route, it.peer?.displayName) }
                .distinctUntilChanged()
                .collect { nm.notify(Notifier.SERVICE_ID, Notifier.service(this@NodeService, Node.state.value)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        Node.stopListening()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            Node.init(ctx)
            if (Node.store.receiveEnabled && Node.store.peer != null) {
                runCatching { ctx.startForegroundService(Intent(ctx, NodeService::class.java)) }
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NodeService::class.java))
        }
    }
}

/** Progress at 5% granularity so the notification isn't rebuilt for every chunk. */
private fun Activity.coarse(): Any = when (this) {
    is Activity.Sending -> "s" to (if (total > 0) done * 20 / total else 0)
    is Activity.Receiving -> "r" to (if (total > 0) done * 20 / total else 0)
    else -> this::class
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NodeService.start(ctx)
        }
    }
}

/** Quick Settings tile: sends the clipboard to the Mac. Reading the clipboard needs a focused activity. */
class ClipboardTile : TileService() {
    override fun onStartListening() {
        Node.init(this)
        qsTile?.apply {
            state = if (Node.store.peer != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            subtitle = Node.store.peer?.displayName
            icon = Icon.createWithResource(this@ClipboardTile, R.drawable.ic_tile)
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, ShareActivity::class.java)
            .setAction(ShareActivity.ACTION_SEND_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(pi)
    }
}
