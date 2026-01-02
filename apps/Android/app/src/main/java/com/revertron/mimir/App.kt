package com.revertron.mimir

import android.app.Application
import android.os.Handler
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.storage.SqlStorage
import java.util.concurrent.atomic.AtomicLong


class App: Application() {

    companion object {
        lateinit var app: App
    }

    var mediatorManager: MediatorManager? = null
    var online: Boolean = false
    lateinit var storage: SqlStorage
    lateinit var callback: NetState

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        NotificationHelper.createCallChannels(this)

        storage = SqlStorage(this)
        storage.cleanUp()
        storage.cleanupOldDrafts(7) // Clean up drafts older than 7 days
        storage.updateUnreadCountsForGroups()
        app = this
        callback = NetState(this)
        val handler = Handler(mainLooper)
        handler.postDelayed({
            callback.register()
        }, 500)
    }

    override fun onTerminate() {
        callback.unregister()
        super.onTerminate()
    }
}