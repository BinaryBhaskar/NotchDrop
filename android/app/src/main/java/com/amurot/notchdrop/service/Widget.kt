package com.amurot.notchdrop.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.widget.RemoteViews
import com.amurot.notchdrop.R
import com.amurot.notchdrop.ui.MainActivity
import com.amurot.notchdrop.ui.ShareActivity

/** Home-screen widget: connection status, send the clipboard, open the last received file. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        Node.init(ctx)
        manager.updateAppWidget(ids, views(ctx, Node.state.value))
    }

    companion object {
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(ctx, Widget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(ctx, Node.state.value))
        }

        private fun views(ctx: Context, state: NodeState): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val mac = state.peer?.displayName
            v.setTextViewText(R.id.widget_title, mac ?: "NotchDrop")
            v.setTextViewText(
                R.id.widget_status,
                when {
                    mac == null -> "Open to pair with your Mac"
                    state.reachable -> if (state.route == Route.HOTSPOT) "Connected over hotspot" else "Connected over Wi-Fi"
                    else -> "Mac not found on this network"
                },
            )
            val dot = when {
                mac == null -> 0x8CFFFFFF.toInt()
                state.reachable -> 0xFF4DD97A.toInt()
                else -> 0xFFFF9E40.toInt()
            }
            v.setColorStateList(R.id.widget_dot, "setImageTintList", ColorStateList.valueOf(dot))

            val app = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, app)

            val clip = Intent(ctx, ShareActivity::class.java).setAction(ShareActivity.ACTION_SEND_CLIPBOARD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(
                R.id.widget_clipboard,
                if (mac != null) PendingIntent.getActivity(ctx, 1, clip, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else app,
            )

            val last = state.history.firstOrNull { it.incoming && it.uri != null }
            if (last != null) {
                v.setTextViewText(R.id.widget_last_label, last.title)
                val uri = Uri.parse(last.uri)
                val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, last.mime ?: Places.mimeOf(ctx, uri, last.title))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                v.setOnClickPendingIntent(
                    R.id.widget_last,
                    PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                v.setTextViewText(R.id.widget_last_label, ctx.getString(R.string.widget_last))
                v.setOnClickPendingIntent(R.id.widget_last, app)
            }
            return v
        }
    }
}
