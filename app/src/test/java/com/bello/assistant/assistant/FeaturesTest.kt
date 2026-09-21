package com.bello.assistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which switch an intent depends on, and which intents end the turn (docs/features.md). */
class FeaturesTest {

    private val commands = listOf(
        Intent.TimerSet(60_000, null), Intent.TimerCancel(all = false),
        Intent.AlarmSet(7, 0, null), Intent.AlarmCancel(all = true),
        Intent.Remember("mon café est serré"), Intent.Forget(null),
        Intent.TvPower(on = true), Intent.TvChannel(2, null), Intent.TvChannelStep(up = true),
        Intent.TvVolume(up = true, steps = 3), Intent.TvMute(silence = true), Intent.TvKey("ok"),
    )

    private val questions = listOf(
        Intent.None, Intent.Time, Intent.Day, Intent.Stop, Intent.TimerList, Intent.AlarmList,
        Intent.Weather(null, tomorrow = false), Intent.News, Intent.PublicHolidays(null),
        Intent.SchoolHolidays(null), Intent.Fuel(null, null), Intent.Joke,
        Intent.Encyclopedia("Marie Curie"), Intent.OnThisDay(null, null), Intent.ListMemories,
        Intent.TvStatus,
    )

    @Test fun `commands end the turn, questions keep the follow-up`() {
        commands.forEach { assertTrue("$it is a command", Features.isCommand(it)) }
        questions.forEach { assertFalse("$it is a question", Features.isCommand(it)) }
    }

    @Test fun `the clock, stop and the chat itself have no tool switch`() {
        listOf(Intent.None, Intent.Time, Intent.Day, Intent.Stop).forEach { assertNull(Features.featureOf(it)) }
    }

    @Test fun `every tool intent belongs to one switch`() {
        assertEquals(Feature.TV, Features.featureOf(Intent.TvChannel(2, null)))
        assertEquals(Feature.TV, Features.featureOf(Intent.TvStatus))
        assertEquals(Feature.TIMERS, Features.featureOf(Intent.AlarmList))
        assertEquals(Feature.MEMORY, Features.featureOf(Intent.Forget("tout")))
        assertEquals(Feature.WIKIPEDIA, Features.featureOf(Intent.OnThisDay(7, 14)))
        assertEquals(Feature.HOLIDAYS, Features.featureOf(Intent.SchoolHolidays("noel")))
        (commands + questions).filter { it !in listOf(Intent.None, Intent.Time, Intent.Day, Intent.Stop) }
            .forEach { assertTrue("$it has a switch", Features.featureOf(it) != null) }
    }

    @Test fun `the stored list round-trips and ignores what it does not know`() {
        val off = setOf(Feature.WEATHER, Feature.CHAT)
        assertEquals(off, Features.parse(Features.format(off)))
        assertEquals(setOf(Feature.TV), Features.parse(" tv ,, teleportation"))
        assertEquals(emptySet<Feature>(), Features.parse(""))
    }
}
