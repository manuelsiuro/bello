package com.bello.assistant

import com.bello.assistant.core.ProcStat
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcStatTest {
    @Test
    fun parsesAggregateCpuLine() {
        val cpu = ProcStat.parseCpuLine("cpu  100 5 50 800 20 1 2 0 0 0")
        assertEquals(978L, cpu.total)
        assertEquals(820L, cpu.idle)
    }

    @Test
    fun parsesProcessTicksWithSpacesInCommand() {
        // pid (comm) state ppid ... utime(14) stime(15)
        val stat = "1234 (com.bello assistant) S 1 1 0 0 -1 4194624 100 0 0 0 250 75 0 0 20 0 30 0 5000"
        assertEquals(325L, ProcStat.parseProcessTicks(stat))
    }

    @Test
    fun percentOfTotal() {
        assertEquals(25.0, ProcStat.percent(50, 200), 0.001)
        assertEquals(0.0, ProcStat.percent(50, 0), 0.001)
    }
}
