package com.bello.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.bello.assistant.core.FileLog

/** Starts the service and shows the face after the tablet boots (FR-ON-03). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        FileLog.i("boot", "BOOT_COMPLETED uptime=${SystemClock.elapsedRealtime() / 1000}s")
        AssistantService.start(context, "boot")
        AssistantService.launchFace(context, "boot")
    }
}
