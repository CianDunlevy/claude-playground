package io.github.ciandunlevy.catchup

import android.app.Application
import io.github.ciandunlevy.catchup.data.Prefs
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.notify.Notifier
import io.github.ciandunlevy.catchup.work.Work

class CatchUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        Notifier.createChannels(this)
        Work.scheduleAll(this)
    }
}
