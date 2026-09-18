package com.bello.assistant

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import com.bello.assistant.core.FileLog
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import com.bello.assistant.llm.LlmFactory
import com.bello.assistant.llm.LlmGateway
import com.bello.assistant.net.HttpClients
import com.bello.assistant.ui.MainActivity
import kotlin.system.exitProcess

class BelloApp : Application() {

    /**
     * The answer source lives with the process, not with the activity: the face can be recreated
     * (it is the home screen) and provider counters, cooldowns and the hidden web page must survive.
     */
    @Volatile private var gatewayOrNull: LlmGateway? = null

    @Synchronized
    fun gateway(): LlmGateway = gatewayOrNull ?: LlmFactory.build(this).also { gatewayOrNull = it }

    /** Rebuilds from the config file after `scripts/push-config.sh`. */
    @Synchronized
    fun reloadGateway(): LlmGateway {
        gatewayOrNull?.close()
        return LlmFactory.build(this).also { gatewayOrNull = it }
    }

    override fun onCreate() {
        super.onCreate()
        FileLog.init(this)
        installCrashLogger()
        FileLog.i("app", "start version=${packageManager.getPackageInfo(packageName, 0).versionName}")
        HttpClients.installProvider()
        // Debug builds: inspect the face WebView from the Mac (adb forward + DevTools protocol).
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
    }

    /**
     * On a crash: log it, schedule a restart, then end the process ourselves. Letting the system
     * handle it would leave "Unfortunately, Bello has stopped" on screen until someone taps it,
     * which breaks an always-on device.
     */
    private fun installCrashLogger() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { FileLog.e("crash", "uncaught exception on ${thread.name}", error) }
            runCatching { scheduleRestart() }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    private fun scheduleRestart() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainActivity.EXTRA_LAUNCH_REASON, "crash-restart")
        val pending = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT)
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending)
        FileLog.w("crash", "restart scheduled in ${RESTART_DELAY_MS}ms")
    }

    private companion object { const val RESTART_DELAY_MS = 2_000L }
}
