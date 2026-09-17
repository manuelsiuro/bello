package com.bello.assistant

import android.app.Application
import com.bello.assistant.core.FileLog
import com.bello.assistant.net.HttpClients

class BelloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLog.init(this)
        installCrashLogger()
        FileLog.i("app", "start version=${packageManager.getPackageInfo(packageName, 0).versionName}")
        HttpClients.installProvider()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            FileLog.e("crash", "uncaught exception on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
