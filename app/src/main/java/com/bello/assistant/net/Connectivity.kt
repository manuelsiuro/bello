package com.bello.assistant.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import com.bello.assistant.core.FileLog

/**
 * Whether there is a network at all (NFR-REL-02).
 *
 * Knowing this before a question is asked turns a twenty-second wait for three providers to time
 * out into an immediate, truthful answer — and lets the face say so quietly while it lasts. The
 * clock, the timers and the alarms never needed the network in the first place.
 */
@Suppress("DEPRECATION")
class Connectivity(private val context: Context, private val onChanged: (Boolean) -> Unit) {

    private var registered = false
    private var lastKnown: Boolean? = null

    fun isOnline(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // getActiveNetworkInfo is the API 21 way; NetworkCapabilities arrives in 23.
        return manager?.activeNetworkInfo?.isConnected == true
    }

    fun start() {
        if (registered) return
        context.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        registered = true
        report(isOnline())
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun report(online: Boolean) {
        if (lastKnown == online) return
        lastKnown = online
        FileLog.i(TAG, if (online) "NETWORK_BACK" else "NETWORK_LOST")
        onChanged(online)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = report(isOnline())
    }

    private companion object { const val TAG = "net" }
}
