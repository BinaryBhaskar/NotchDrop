package app.notchdrop

import android.app.Application
import app.notchdrop.service.Node
import app.notchdrop.service.Notifier

class NotchDropApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Node.init(this)
    }
}
