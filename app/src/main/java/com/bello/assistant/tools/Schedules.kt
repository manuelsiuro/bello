package com.bello.assistant.tools

import com.bello.assistant.memory.BelloDb
import java.util.Calendar

/** A timer or an alarm waiting to ring. */
data class Schedule(
    val id: Long,
    val kind: Kind,
    val dueAt: Long,
    val label: String?,
    val durationMs: Long = 0,
) {
    enum class Kind { TIMER, ALARM }

    fun remainingMs(now: Long) = (dueAt - now).coerceAtLeast(0)
}

/** Timers and alarms on disk, so they survive a restart or a reboot (FR-TOOL-03). */
class ScheduleStore(private val db: BelloDb) {

    fun add(kind: Schedule.Kind, dueAt: Long, label: String?, durationMs: Long, now: Long): Schedule {
        val id = db.writableDatabase.insert("schedules", null, db.values(
            "kind" to kind.name, "due_at" to dueAt, "label" to label,
            "duration_ms" to durationMs, "created_at" to now,
        ))
        return Schedule(id, kind, dueAt, label, durationMs)
    }

    fun all(): List<Schedule> = db.readableDatabase.query(
        "schedules", arrayOf("id", "kind", "due_at", "label", "duration_ms"),
        null, null, null, null, "due_at ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                Schedule(
                    id = cursor.getLong(0),
                    kind = Schedule.Kind.valueOf(cursor.getString(1)),
                    dueAt = cursor.getLong(2),
                    label = if (cursor.isNull(3)) null else cursor.getString(3),
                    durationMs = cursor.getLong(4),
                )
            )
        }
    }

    fun of(kind: Schedule.Kind) = all().filter { it.kind == kind }

    fun get(id: Long): Schedule? = all().firstOrNull { it.id == id }

    fun delete(id: Long): Boolean =
        db.writableDatabase.delete("schedules", "id = ?", arrayOf(id.toString())) > 0

    fun clear(kind: Schedule.Kind?): List<Schedule> {
        val doomed = if (kind == null) all() else of(kind)
        doomed.forEach { delete(it.id) }
        return doomed
    }
}

/** When an alarm set for a time of day should next ring. Pure, unit tested. */
object NextOccurrence {
    fun after(now: Long, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }
}
