package com.bello.spikes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logs spike output to logcat (tag BelloSpike) and to files/results/<spike>.log for adb pull. */
object Report {
    const val TAG = "BelloSpike"
    private lateinit var dir: File
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        dir = File(ctx.getExternalFilesDir(null), "results").apply { mkdirs() }
    }

    @Synchronized
    fun log(spike: String, msg: String) {
        Log.i(TAG, "[$spike] $msg")
        runCatching { File(dir, "$spike.log").appendText("${fmt.format(Date())} $msg\n") }
    }
}

/** Samples process CPU, system CPU, battery temperature and PSS every [periodMs]. */
class Metrics(private val ctx: Context, private val spike: String, private val periodMs: Long = 5000) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTotal = 0L
    private var lastIdle = 0L
    private var lastProc = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            handler.postDelayed(this, periodMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        readCpu()
        handler.postDelayed(tick, periodMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun readCpu(): Triple<Long, Long, Long> {
        val cpu = RandomAccessFile("/proc/stat", "r").use { it.readLine() }
            .trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        val idle = cpu[3] + cpu.getOrElse(4) { 0 }
        val total = cpu.sum()
        val self = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(" ")
        val proc = self[11].toLong() + self[12].toLong() // utime + stime
        val r = Triple(total - lastTotal, idle - lastIdle, proc - lastProc)
        lastTotal = total; lastIdle = idle; lastProc = proc
        return r
    }

    private fun sample() {
        val (dTotal, dIdle, dProc) = runCatching { readCpu() }.getOrElse { Triple(1L, 1L, 0L) }
        val sysPct = if (dTotal > 0) 100.0 * (dTotal - dIdle) / dTotal else 0.0
        val procPct = if (dTotal > 0) 100.0 * dProc / dTotal else 0.0
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val pssMb = Debug.getPss() / 1024
        Report.log(spike, "METRIC cpuProc=%.1f%% cpuSys=%.1f%% tempC=%.1f pssMB=%d".format(procPct, sysPct, tempC, pssMb))
    }
}
