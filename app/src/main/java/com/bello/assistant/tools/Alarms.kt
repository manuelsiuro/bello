package com.bello.assistant.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bello.assistant.core.FileLog
import com.bello.assistant.memory.BelloDb
import com.bello.assistant.ui.MainActivity

/**
 * Timers and alarms (FR-TOOL-02/03): stored on disk, handed to `AlarmManager`, and put back after
 * a reboot. Ringing itself happens in the activity, which owns the face and the voice.
 */
class Alarms(private val context: Context) {

    private val store = ScheduleStore(BelloDb(context.applicationContext))
    private val manager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun setTimer(durationMs: Long, label: String?, now: Long = System.currentTimeMillis()): Schedule {
        val schedule = store.add(Schedule.Kind.TIMER, now + durationMs, label, durationMs, now)
        arm(schedule)
        FileLog.i(TAG, "timer #${schedule.id} in ${durationMs / 1000}s label=${label ?: "-"}")
        return schedule
    }

    fun setAlarm(hour: Int, minute: Int, label: String?, now: Long = System.currentTimeMillis()): Schedule {
        val schedule = store.add(Schedule.Kind.ALARM, NextOccurrence.after(now, hour, minute), label, 0, now)
        arm(schedule)
        FileLog.i(TAG, "alarm #${schedule.id} at $hour:$minute label=${label ?: "-"}")
        return schedule
    }

    fun list(kind: Schedule.Kind? = null): List<Schedule> =
        if (kind == null) store.all() else store.of(kind)

    fun cancel(kind: Schedule.Kind?, all: Boolean, now: Long = System.currentTimeMillis()): List<Schedule> {
        val doomed = when {
            all || kind == null -> store.clear(kind)
            else -> store.of(kind).minByOrNull { it.remainingMs(now) }?.let { store.delete(it.id); listOf(it) }
                ?: emptyList()
        }
        doomed.forEach { manager.cancel(pending(it.id, Intent.FLAG_ACTIVITY_NEW_TASK)) }
        FileLog.i(TAG, "cancelled ${doomed.size} schedule(s)")
        return doomed
    }

    /** Called when one rings: it is done, so it leaves the store. */
    fun consume(id: Long): Schedule? = store.get(id)?.also { store.delete(id) }

    /** After a reboot, `AlarmManager` has forgotten everything (FR-TOOL-03). */
    fun rescheduleAll(now: Long = System.currentTimeMillis()) {
        val all = store.all()
        val (late, upcoming) = all.partition { it.dueAt <= now }
        // Timers that expired while the tablet was off are pointless; a missed alarm rings now.
        late.forEach { missed ->
            if (missed.kind == Schedule.Kind.ALARM && now - missed.dueAt < LATE_ALARM_GRACE_MS) {
                arm(missed.copy(dueAt = now + 5_000))
            } else {
                store.delete(missed.id)
            }
        }
        upcoming.forEach { arm(it) }
        FileLog.i(TAG, "rescheduled ${upcoming.size}, dropped ${late.size} stale")
    }

    private fun arm(schedule: Schedule) {
        manager.setExact(AlarmManager.RTC_WAKEUP, schedule.dueAt, pending(schedule.id, 0))
    }

    private fun pending(id: Long, flags: Int): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction("$ACTION_RING.$id")
            .putExtra(EXTRA_ID, id)
            .addFlags(flags)
        return PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val TAG = "alarms"
        const val ACTION_RING = "com.bello.assistant.RING"
        const val EXTRA_ID = "scheduleId"
        private const val LATE_ALARM_GRACE_MS = 60 * 60_000L
    }
}

/** Wakes the face up so it can ring (FR-TOOL-04). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Alarms.EXTRA_ID, -1)
        FileLog.i("alarms", "ring #$id")
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_RING, id)
        )
    }
}
