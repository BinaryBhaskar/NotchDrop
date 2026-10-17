package com.amurot.notchdrop

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.Notifier
import com.amurot.notchdrop.ui.MainActivity
import com.amurot.notchdrop.ui.ShareActivity

class NotchDropApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Node.init(this)
        runCatching { publishShortcuts() }
    }

    /** Long-press shortcuts on the launcher icon. Built in code so the dev build's package name is right. */
    private fun publishShortcuts() {
        fun shortcut(id: String, label: Int, icon: Int, intent: Intent) = ShortcutInfo.Builder(this, id)
            .setShortLabel(getString(label))
            .setIcon(Icon.createWithResource(this, icon))
            .setIntent(intent)
            .build()
        fun main(action: String) = Intent(this, MainActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        getSystemService(ShortcutManager::class.java).dynamicShortcuts = listOf(
            shortcut("clipboard", R.string.shortcut_clipboard, R.drawable.shortcut_clipboard,
                Intent(this, ShareActivity::class.java).setAction(ShareActivity.ACTION_SEND_CLIPBOARD)),
            shortcut("files", R.string.shortcut_files, R.drawable.shortcut_files, main(MainActivity.ACTION_PICK_FILES)),
            shortcut("received", R.string.shortcut_folder, R.drawable.shortcut_received, main(MainActivity.ACTION_OPEN_FOLDER)),
            shortcut("scan", R.string.shortcut_scan, R.drawable.shortcut_scan, main(MainActivity.ACTION_SCAN)),
        )
    }
}
