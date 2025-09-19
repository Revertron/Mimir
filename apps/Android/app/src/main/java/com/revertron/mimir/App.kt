package com.revertron.mimir

import android.app.Application
import android.os.Handler
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import com.revertron.mimir.storage.SqlStorage
import java.util.concurrent.atomic.AtomicLong


class App: Application() {

    companion object {
        lateinit var app: App
    }

    var online: Boolean = false
    private var networkChangedTime = AtomicLong(0L)
    lateinit var storage: SqlStorage
    lateinit var callback: NetworkStateCallback

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

        NotificationManager.createCallsNotificationChannel(this)

        storage = SqlStorage(this)
        storage.cleanUp()
        app = this
        callback = NetworkStateCallback(this)
        val handler = Handler(mainLooper)
        handler.postDelayed({
            callback.register()
        }, 15000)
    }

    override fun onTerminate() {
        callback.unregister()
        super.onTerminate()
    }

    fun networkChanged() {
        networkChangedTime.set(System.currentTimeMillis())
    }

    fun networkChangedRecently(): Boolean {
        return System.currentTimeMillis() - networkChangedTime.get() < 15000
    }
}