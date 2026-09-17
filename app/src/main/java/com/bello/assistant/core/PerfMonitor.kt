package com.bello.assistant.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import java.io.File

/** CPU accounting parsed from /proc. Pure functions, unit tested. */
object ProcStat {
    data class Cpu(val total: Long, val idle: Long)

    /** First line of /proc/stat: "cpu  user nice system idle iowait irq softirq ..." */
    fun parseCpuLine(line: String): Cpu {
        val v = line.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
        return Cpu(total = v.sum(), idle = v[3] + v.getOrElse(4) { 0 })
    }

    /** /proc/self/stat: utime + stime in clock ticks (fields 14 and 15; comm may contain spaces). */
    fun parseProcessTicks(stat: String): Long {
        val fields = stat.substringAfterLast(')').trim().split(" ")
        return fields[11].toLong() + fields[12].toLong()
    }

    /** Share of all cores, in percent. */
    fun percent(deltaPart: Long, deltaTotal: Long): Double =
        if (deltaTotal <= 0) 0.0 else 100.0 * deltaPart / deltaTotal
}

/**
 * Logs process CPU (share of all cores), system CPU, battery temperature and PSS every [periodMs].
 * Lines look like: `PERF cpuProc=4.1 cpuSys=12.3 tempC=31.2 pssMB=98`.
 */
class PerfMonitor(private val context: Context, private val periodMs: Long) {
    private val handler = Handler(Looper.getMainLooper())
    private var last: ProcStat.Cpu? = null
    private var lastProc = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            Thread({ sample() }, "perf").start()
            handler.postDelayed(this, periodMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(tick, periodMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    @Synchronized
    private fun sample() = runCatching {
        val cpu = ProcStat.parseCpuLine(File("/proc/stat").useLines { it.first() })
        val proc = ProcStat.parseProcessTicks(File("/proc/self/stat").readText())
        val prev = last
        last = cpu
        val prevProc = lastProc
        lastProc = proc
        if (prev == null) return@runCatching
        val dTotal = cpu.total - prev.total
        val procPct = ProcStat.percent(proc - prevProc, dTotal)
        val sysPct = ProcStat.percent(dTotal - (cpu.idle - prev.idle), dTotal)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        FileLog.i("perf", String.format(java.util.Locale.US, "PERF cpuProc=%.1f cpuSys=%.1f tempC=%.1f pssMB=%d",
            procPct, sysPct, tempC, Debug.getPss() / 1024))
    }
}
