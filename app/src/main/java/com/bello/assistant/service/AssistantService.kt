package com.bello.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.bello.assistant.R
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.PerfMonitor
import com.bello.assistant.core.Prefs
import com.bello.assistant.ui.MainActivity

/**
 * Long-lived foreground service: keeps the process alive, holds a partial wake lock, logs
 * performance, and (kiosk mode) brings the face back if it has not been visible for a minute.
 * Voice, wake word and tools will run here from Phase 2 onwards.
 */
class AssistantService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var perf: PerfMonitor
    private var wakeLock: PowerManager.WakeLock? = null

    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (KioskPolicy.shouldRelaunch(prefs.kioskEnabled, FaceVisibility.visible, FaceVisibility.lastVisibleAtMs, now)) {
                FileLog.w(TAG, "face not visible for ${(now - FaceVisibility.lastVisibleAtMs) / 1000}s, relaunching")
                launchFace(this@AssistantService, "watchdog")
            }
            handler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startForeground(NOTIFICATION_ID, notification())
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bello:service").apply { acquire() }
        perf = PerfMonitor(this, prefs.perfIntervalSec * 1000L).also { it.start() }
        // Give a freshly started process time to show the face before the watchdog judges it.
        if (!FaceVisibility.visible) FaceVisibility.onHidden(SystemClock.elapsedRealtime())
        handler.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
        FileLog.i(TAG, "service created kiosk=${prefs.kioskEnabled} perfInterval=${prefs.perfIntervalSec}s")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means Android restarted the sticky service after the process died.
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "sticky-restart"
        FileLog.i(TAG, "start command reason=$reason")
        if (intent == null && prefs.kioskEnabled) launchFace(this, reason)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        perf.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        FileLog.w(TAG, "service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), 0)
        return Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "service"
        private const val NOTIFICATION_ID = 1
        private const val WATCHDOG_PERIOD_MS = 15_000L
        private const val EXTRA_REASON = "reason"

        fun start(context: Context, reason: String) {
            context.startService(Intent(context, AssistantService::class.java).putExtra(EXTRA_REASON, reason))
        }

        fun launchFace(context: Context, reason: String) {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_LAUNCH_REASON, reason))
        }
    }
}
