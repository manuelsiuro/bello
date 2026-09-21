package com.bello.assistant.assistant

/**
 * What the owner can switch off in the settings, one switch per thing Bello does (docs/features.md).
 * [label] is shown on the settings screen and spoken when the feature is asked for while off.
 */
enum class Feature(val key: String, val label: String) {
    CHAT("chat", "la discussion"),
    TV("tv", "la télévision"),
    TIMERS("timers", "les minuteurs et les alarmes"),
    MEMORY("memory", "la mémoire"),
    WEATHER("weather", "la météo"),
    NEWS("news", "les infos"),
    FUEL("fuel", "le carburant"),
    WIKIPEDIA("wikipedia", "Wikipédia"),
    JOKES("jokes", "les blagues"),
    HOLIDAYS("holidays", "les jours fériés et les vacances");

    companion object {
        fun byKey(key: String): Feature? = values().firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * Two rules about an intent, kept pure so they are tested: which switch it depends on, and whether
 * it is a command. A command ends the turn — "mets la 2" is done once the channel changes, and an
 * open microphone after it would only pick up the television.
 */
object Features {

    /** Null for what cannot be switched off (the clock, stop) and for [Intent.None], which is the chat. */
    fun featureOf(intent: Intent): Feature? = when (intent) {
        Intent.None, Intent.Time, Intent.Day, Intent.Stop -> null
        is Intent.TimerSet, Intent.TimerList, is Intent.TimerCancel,
        is Intent.AlarmSet, Intent.AlarmList, is Intent.AlarmCancel -> Feature.TIMERS
        is Intent.Weather -> Feature.WEATHER
        Intent.News -> Feature.NEWS
        is Intent.PublicHolidays, is Intent.SchoolHolidays -> Feature.HOLIDAYS
        is Intent.Fuel -> Feature.FUEL
        Intent.Joke -> Feature.JOKES
        is Intent.Encyclopedia, is Intent.OnThisDay -> Feature.WIKIPEDIA
        is Intent.Remember, is Intent.Forget, Intent.ListMemories -> Feature.MEMORY
        is Intent.TvPower, is Intent.TvChannel, is Intent.TvChannelStep, is Intent.TvVolume,
        is Intent.TvMute, is Intent.TvKey, Intent.TvStatus -> Feature.TV
    }

    /** Something done rather than something told: no follow-up after it (docs/features.md). */
    fun isCommand(intent: Intent): Boolean = when (intent) {
        is Intent.TimerSet, is Intent.TimerCancel, is Intent.AlarmSet, is Intent.AlarmCancel,
        is Intent.Remember, is Intent.Forget,
        is Intent.TvPower, is Intent.TvChannel, is Intent.TvChannelStep, is Intent.TvVolume,
        is Intent.TvMute, is Intent.TvKey -> true
        else -> false
    }

    /** "weather,tv" → the set of switched-off features; unknown keys are ignored. */
    fun parse(stored: String): Set<Feature> =
        stored.split(',').mapNotNull { if (it.isBlank()) null else Feature.byKey(it) }.toSet()

    fun format(disabled: Set<Feature>): String =
        Feature.values().filter { it in disabled }.joinToString(",") { it.key }
}
