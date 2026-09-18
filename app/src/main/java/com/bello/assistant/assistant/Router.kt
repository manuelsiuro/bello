package com.bello.assistant.assistant

import android.content.Context
import com.bello.assistant.core.AppConfig
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.FrenchDates
import com.bello.assistant.llm.AskOptions
import com.bello.assistant.llm.LlmGateway
import com.bello.assistant.memory.BelloDb
import com.bello.assistant.memory.FactStore
import com.bello.assistant.memory.SessionMemory
import com.bello.assistant.tools.Alarms
import com.bello.assistant.tools.Fuel
import com.bello.assistant.tools.FuelData
import com.bello.assistant.tools.HolidayData
import com.bello.assistant.tools.Holidays
import com.bello.assistant.tools.Jokes
import com.bello.assistant.tools.News
import com.bello.assistant.tools.PagePublisher
import com.bello.assistant.tools.Schedule
import com.bello.assistant.tools.TvBox
import com.bello.assistant.tools.TvBoxProtocol
import com.bello.assistant.tools.Weather
import com.bello.assistant.tools.Wikipedia
import com.bello.assistant.ui.FaceState
import com.bello.assistant.voice.SpeechText

/**
 * Decides who answers (FR-TOOL-08): Bello itself for the clock, timers, alarms, the weather, the
 * holidays, the news, the television, the price of fuel and its own memory — a provider for everything else, with the conversation
 * so far and the remembered facts attached (FR-MEM-01/03). When an answer is really a page, it offers the
 * details on the phone and waits for a yes (FR-PAGE-01/02).
 */
class Router(
    context: Context,
    private val gateway: LlmGateway,
    private val config: AppConfig,
    private val listener: Listener,
    private val pages: PagePublisher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Asked before anything that needs the network, so an outage answers instantly (NFR-REL-02). */
    private val isOnline: () -> Boolean = { true },
    /** The owner can turn the offers off in the settings (FR-PAGE-01). */
    private val offersEnabled: () -> Boolean = { true },
) : Responder {

    /** What the conversation has to do on the side: stop ringing, refresh the countdown, show a page. */
    interface Listener {
        fun onSchedulesChanged()
        fun onStopRequested()
        /** A page is ready for the phone (FR-PAGE-05). Called on the page writer's thread. */
        fun onPageReady(url: String, qrRows: List<String>, caption: String, spoken: String)
        /** The page could not be written; [spoken] says so. Called on the page writer's thread. */
        fun onPageFailed(spoken: String)
    }

    private val app = context.applicationContext
    private val db = BelloDb(app)
    val facts = FactStore(db)
    val alarms = Alarms(app)
    private val session = SessionMemory(config.maxTurns, config.sessionIdleMs)
    private val weather = Weather(app)
    private val news = News(app)
    private val holidays = Holidays(app)
    private val fuel = Fuel(app)
    private val jokes = Jokes(app)
    private val wikipedia = Wikipedia(app)
    private val tv: TvBox? = config.tvBox?.let { TvBox(app, it.host, it.port) }

    /** The offer the next utterance may be answering. Lost with the Router on a config reload. */
    @Volatile private var offer: PageOffer.Offer? = null

    override fun answer(question: String): Responder.Answer {
        val now = clock()
        val forIntent = SpeechText.forIntent(question)
        val flat = Intents.deaccent(forIntent)
        pendingOffer(now)?.let { pending ->
            offer = null
            when (YesNo.parse(flat)) {
                YesNo.Reply.YES -> return accept(pending)
                YesNo.Reply.NO -> {
                    FileLog.i(TAG, "PAGE_DECLINED")
                    return say(ToolReplies.pageDeclined())
                }
                YesNo.Reply.OTHER -> FileLog.i(TAG, "PAGE_DROPPED reason=other")
            }
        }
        val intent = Intents.match(forIntent, config.tvBox?.channels ?: emptyMap(), raw = question)
        if (intent is Intent.Encyclopedia) {
            FileLog.i(TAG, "intent=Encyclopedia")
            // Nothing in the encyclopedia is not an answer: the question goes on to a provider.
            encyclopedia(intent)?.let { return it }
            FileLog.i(TAG, "nothing on Wikipedia, asking a provider")
        } else if (intent != Intent.None) {
            FileLog.i(TAG, "intent=${intent.javaClass.simpleName}")
            return handle(intent, now)
        }
        if (!isOnline()) {
            FileLog.i(TAG, "no network; answering locally")
            return offline(ToolReplies.offline())
        }
        if (session.expireIfIdle(now)) FileLog.i(TAG, "session expired, starting fresh")
        val answer = gateway.ask(question, session.history(now), facts.promptBlock())
        if (answer.isError) return answer
        // The offer is spoken, not remembered: the model must not learn to ask it itself.
        session.add(question, answer.text, now)
        val by = when {
            !offersEnabled() -> return answer
            answer.offersPage -> "tag"
            PageOffer.wantsPage(flat) -> "question"
            else -> return answer
        }
        offer = PageOffer.Offer(question, answer.text, now)
        FileLog.i(TAG, "PAGE_OFFERED by=$by")
        return answer.copy(text = answer.text + " " + ToolReplies.pageOffer(), followUpMs = OFFER_FOLLOW_UP_MS)
    }

    /** A ring or a cancel: "oui" would be answering something else now. */
    override fun reset() {
        if (offer != null) FileLog.i(TAG, "PAGE_DROPPED reason=interrupted")
        offer = null
    }

    private fun pendingOffer(now: Long): PageOffer.Offer? {
        val current = offer ?: return null
        if (PageOffer.stillValid(current, now)) return current
        offer = null
        FileLog.i(TAG, "PAGE_DROPPED reason=expired")
        return null
    }

    /**
     * "Oui": say so at once and write the page in the background; the listener shows it when it
     * is ready (FR-PAGE-02/03). No follow-up window after the answer, so Bello is free to
     * announce the page when it comes.
     */
    private fun accept(pending: PageOffer.Offer): Responder.Answer {
        FileLog.i(TAG, "PAGE_ACCEPTED")
        if (!isOnline()) return offline(ToolReplies.offline())
        val host = pages.address()
        if (host == null) {
            FileLog.w(TAG, "PAGE_DROPPED reason=no-wifi")
            return offline(ToolReplies.pageNotOnWifi())
        }
        Thread({ write(pending, host) }, "page-writer").start()
        return say(ToolReplies.pagePreparing()).copy(followUpMs = 0)
    }

    private fun write(pending: PageOffer.Offer, host: String) {
        try {
            FileLog.i(TAG, "PAGE_WRITING")
            // No history and no remembered facts: nothing personal goes on a page served to the network.
            val full = gateway.ask(
                ToolReplies.pagePrompt(pending.question, pending.spokenAnswer), emptyList(), "",
                AskOptions(
                    system = ToolReplies.PAGE_SYSTEM, maxTokens = PAGE_MAX_TOKENS,
                    timeoutMs = PAGE_TIMEOUT_MS, budgetMs = PAGE_BUDGET_MS,
                ),
            )
            if (full.isError || full.text.isBlank()) {
                FileLog.w(TAG, "PAGE_FAILED reason=llm")
                listener.onPageFailed(ToolReplies.pageFailed())
                return
            }
            val page = pages.publish(full.text, host, full.truncated)
            if (page == null) {
                FileLog.w(TAG, "PAGE_FAILED reason=publish")
                listener.onPageFailed(ToolReplies.pageFailed())
                return
            }
            listener.onPageReady(page.url, page.qrRows, ToolReplies.qrCaption(page.title), ToolReplies.pageReady(page.title))
        } catch (t: Throwable) {
            FileLog.w(TAG, "PAGE_FAILED reason=exception", t)
            runCatching { listener.onPageFailed(ToolReplies.pageFailed()) }
        }
    }

    /** Ringing was triggered by the alarm receiver, not by a question. */
    fun ringingText(id: Long): String = ToolReplies.ringing(alarms.consume(id)).also {
        listener.onSchedulesChanged()
    }

    fun schedules(): List<Schedule> = alarms.list()

    /** What the settings screen shows about the memory, and how it is emptied (FR-MEM-04). */
    fun memoryLine(): String {
        val remembered = facts.all()
        if (remembered.isEmpty()) return "Rien en mémoire pour l'instant."
        return "${remembered.size} chose(s) en mémoire : " +
            remembered.take(3).joinToString("; ") { it.text } + (if (remembered.size > 3) "…" else "")
    }

    fun forgetEverything() {
        val remembered = facts.all()
        remembered.forEach { facts.delete(it.id) }
        FileLog.i(TAG, "forgot everything (${remembered.size})")
    }

    private fun handle(intent: Intent, now: Long): Responder.Answer = when (intent) {
        is Intent.Time -> say(ToolReplies.time(now))
        is Intent.Day -> say(ToolReplies.day(now))

        is Intent.Stop -> {
            listener.onStopRequested()
            Responder.Answer("", isError = false, source = "local")
        }

        is Intent.TimerSet -> {
            alarms.setTimer(intent.durationMs, intent.label, now)
            listener.onSchedulesChanged()
            say(ToolReplies.timerSet(intent.durationMs, intent.label), FaceState.HAPPY)
        }
        is Intent.TimerList -> say(ToolReplies.timerList(alarms.list(Schedule.Kind.TIMER), now))
        is Intent.TimerCancel -> {
            val cancelled = alarms.cancel(Schedule.Kind.TIMER, intent.all, now)
            listener.onSchedulesChanged()
            say(ToolReplies.timerCancelled(cancelled))
        }

        is Intent.AlarmSet -> {
            val alarm = alarms.setAlarm(intent.hour, intent.minute, intent.label, now)
            listener.onSchedulesChanged()
            say(ToolReplies.alarmSet(intent.hour, intent.minute, intent.label, alarm.dueAt, now), FaceState.HAPPY)
        }
        is Intent.AlarmList -> say(ToolReplies.alarmList(alarms.list(Schedule.Kind.ALARM), now))
        is Intent.AlarmCancel -> {
            val cancelled = alarms.cancel(Schedule.Kind.ALARM, intent.all, now)
            listener.onSchedulesChanged()
            say(ToolReplies.alarmCancelled(cancelled))
        }

        is Intent.Weather -> {
            val report = if (isOnline()) weather.report(intent.city, intent.tomorrow, config.city) else null
            if (report == null) offline("Je n'arrive pas à consulter la météo pour le moment.")
            else say(report)
        }

        is Intent.News -> news()

        is Intent.PublicHolidays -> publicHolidays(intent, now)
        is Intent.SchoolHolidays -> schoolHolidays(intent, now)

        is Intent.Joke -> say(jokes.tell(isOnline()), FaceState.HAPPY)
        is Intent.Fuel -> fuel(intent, now)
        is Intent.OnThisDay -> onThisDay(intent, now)
        is Intent.Encyclopedia -> encyclopedia(intent) ?: offline(ToolReplies.offline())

        is Intent.Remember -> {
            facts.add(intent.fact, now)
            say(ToolReplies.remembered(intent.fact), FaceState.HAPPY)
        }
        is Intent.Forget -> {
            val forgotten = if (intent.what == null) {
                facts.all().also { facts.clear() }
            } else {
                facts.forget(intent.what)
            }
            say(ToolReplies.forgotten(forgotten, all = intent.what == null))
        }
        is Intent.ListMemories -> say(ToolReplies.memories(facts.all()))

        is Intent.TvPower, is Intent.TvChannel, is Intent.TvChannelStep, is Intent.TvVolume,
        is Intent.TvMute, is Intent.TvKey, Intent.TvStatus -> television(intent)

        is Intent.None -> say("")
    }

    /**
     * The decoder is on the LAN, so "offline" is not the question here; "the box did not answer"
     * is. Every reply is spoken short: the television is already making the noise.
     */
    private fun television(intent: Intent): Responder.Answer {
        val box = tv ?: return offline(ToolReplies.tvNotConfigured())
        val keys = config.tvBox ?: return offline(ToolReplies.tvNotConfigured())
        val done: String? = when (intent) {
            is Intent.TvStatus -> box.isOn()?.let { ToolReplies.tvStatus(it) }
            is Intent.TvPower -> box.switchPower(intent.on)?.let {
                ToolReplies.tvPower(intent.on, already = it == TvBox.Switch.ALREADY)
            }
            is Intent.TvChannel -> {
                val digits = TvBoxProtocol.digits(intent.number) + (if (keys.okAfterDigits) listOf(TvBoxProtocol.OK) else emptyList())
                if (box.press(digits)) ToolReplies.tvChannel(intent.number, intent.name) else null
            }
            is Intent.TvChannelStep ->
                if (box.press(if (intent.up) TvBoxProtocol.CHANNEL_UP else TvBoxProtocol.CHANNEL_DOWN)) ToolReplies.tvChannelStep(intent.up) else null
            is Intent.TvVolume -> {
                val key = if (intent.up) TvBoxProtocol.VOL_UP else TvBoxProtocol.VOL_DOWN
                if (box.press(List(intent.steps) { key })) ToolReplies.tvVolume(intent.up) else null
            }
            is Intent.TvMute -> if (box.press(TvBoxProtocol.MUTE)) ToolReplies.tvMute(intent.silence) else null
            is Intent.TvKey -> if (box.press(intent.key)) ToolReplies.tvKey(intent.key) else null
            else -> null
        }
        return if (done == null) offline(ToolReplies.tvUnreachable()) else say(done, FaceState.HAPPY)
    }

    /**
     * Jours fériés and school breaks. Both are kept on the tablet once fetched, so an outage is
     * not a reason to refuse: the network is offered to the tool, never demanded.
     */
    private fun publicHolidays(intent: Intent.PublicHolidays, now: Long): Responder.Answer {
        val online = isOnline()
        val offset = intent.offsetDays
        if (offset == null) {
            val next = holidays.nextPublicHoliday(now, config.holidayZone, online)
                ?: return offline(ToolReplies.publicHolidaysUnknown())
            return say(ToolReplies.publicHolidayNext(next, now))
        }
        val day = FrenchDates.addDays(now, offset)
        val on = holidays.publicHolidayOn(day, config.holidayZone, online)
        val next = if (on == null) holidays.nextPublicHoliday(day, config.holidayZone, online) else null
        if (on == null && next == null) return offline(ToolReplies.publicHolidaysUnknown())
        return say(ToolReplies.publicHolidayOn(offset, on, next, now), if (on != null) FaceState.HAPPY else null)
    }

    private fun schoolHolidays(intent: Intent.SchoolHolidays, now: Long): Responder.Answer {
        val all = holidays.schoolBreaks(now, config.schoolZone, config.schoolAcademy, isOnline())
        val wanted = intent.named?.let { name ->
            all.filter { Intents.deaccent(it.description.lowercase()).contains(name) }
        } ?: all
        val current = HolidayData.currentBreak(wanted, now)
        val next = HolidayData.nextBreak(wanted, now)
        if (current == null && next == null) return offline(ToolReplies.schoolBreaksUnknown())
        return say(ToolReplies.schoolBreak(current, next, now, intent.askingNow), FaceState.HAPPY)
    }

    /** The cheapest station around the house, or around the town the question named. */
    private fun fuel(intent: Intent.Fuel, now: Long): Responder.Answer {
        if (!isOnline()) return offline(ToolReplies.offline())
        val wanted = intent.fuel ?: config.fuel
        val town = intent.city ?: config.city
        val place = weather.place(town) ?: return offline(ToolReplies.fuelUnknownPlace(town))
        val station = fuel.cheapest(place.latitude, place.longitude, wanted, now)
            ?: return offline(ToolReplies.fuelNone(FuelData.spoken(wanted), place.name))
        return say(ToolReplies.fuel(station), FaceState.HAPPY)
    }

    private fun onThisDay(intent: Intent.OnThisDay, now: Long): Responder.Answer {
        if (!isOnline()) return offline(ToolReplies.offline())
        val calendar = FrenchDates.calendar(now)
        val month = intent.month ?: (calendar.get(java.util.Calendar.MONTH) + 1)
        val day = intent.day ?: calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val events = wikipedia.onThisDay(month, day)
        if (events.isEmpty()) return offline(ToolReplies.offline())
        val asked = FrenchDates.parseDay(String.format("%04d-%02d-%02d", FrenchDates.year(now), month, day)) ?: now
        return say(ToolReplies.onThisDay(events, asked))
    }

    /** Null when the encyclopedia has nothing to say, so that a provider can try. */
    private fun encyclopedia(intent: Intent.Encyclopedia): Responder.Answer? {
        if (!isOnline()) return null
        val article = wikipedia.about(intent.subject) ?: return null
        return say(ToolReplies.article(article))
    }

    /** Headlines are facts; turning them into two or three spoken sentences is a provider's job. */
    private fun news(): Responder.Answer {
        if (!isOnline()) return offline(ToolReplies.offline())
        val titles = news.headlines(config.newsFeeds)
        if (titles.isEmpty()) return offline("Je n'arrive pas à récupérer les informations.")
        val summary = gateway.ask(ToolReplies.summarisePrompt(titles), emptyList(), "")
        return if (summary.isError) say(ToolReplies.headlinesFallback(titles)) else summary
    }

    private fun say(text: String, emotion: FaceState? = null) =
        Responder.Answer(text, isError = false, emotion = emotion, source = "local")

    private fun offline(text: String) =
        Responder.Answer(text, isError = true, emotion = FaceState.SAD, source = "local")

    private companion object {
        const val TAG = "router"
        /** Long enough to find the phone; after that "oui" is an ordinary word again. */
        const val OFFER_FOLLOW_UP_MS = 10_000
        /** A page is a few hundred words; reasoning models spend part of this thinking. */
        const val PAGE_MAX_TOKENS = 2_000
        const val PAGE_TIMEOUT_MS = 60_000
        const val PAGE_BUDGET_MS = 90_000
    }
}
