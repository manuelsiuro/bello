package com.bello.assistant.tools

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import com.bello.assistant.core.FileLog

/**
 * The sound a timer or an alarm makes (FR-TOOL-04). It stops on a tap, on "stop", or by itself
 * after a minute — an always-on device in a living room must never ring forever.
 */
class Ringer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val autoStop = Runnable { stop("timeout") }

    val isRinging get() = player != null

    fun start() {
        stop("restart")
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return FileLog.w(TAG, "no ringtone on this device")
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = true
                prepare()
                start()
            }
        }.getOrElse {
            FileLog.w(TAG, "cannot play the alarm sound", it)
            null
        }
        main.postDelayed(autoStop, MAX_RING_MS)
    }

    fun stop(reason: String = "asked") {
        main.removeCallbacks(autoStop)
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
            FileLog.i(TAG, "ringing stopped ($reason)")
        }
        player = null
    }

    private companion object {
        const val TAG = "ringer"
        const val MAX_RING_MS = 60_000L
    }
}
