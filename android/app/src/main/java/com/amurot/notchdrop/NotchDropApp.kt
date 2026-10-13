package com.amurot.notchdrop

import android.app.Application
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.Notifier

class NotchDropApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Node.init(this)
    }
}
