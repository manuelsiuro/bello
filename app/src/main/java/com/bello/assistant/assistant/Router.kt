package com.bello.assistant.assistant

import android.content.Context
import com.bello.assistant.core.AppConfig
import com.bello.assistant.core.FileLog
import com.bello.assistant.llm.LlmGateway
import com.bello.assistant.memory.BelloDb
import com.bello.assistant.memory.FactStore
import com.bello.assistant.memory.SessionMemory
import com.bello.assistant.tools.Alarms
import com.bello.assistant.tools.News
import com.bello.assistant.tools.Schedule
import com.bello.assistant.tools.Weather
import com.bello.assistant.ui.FaceState
import com.bello.assistant.voice.SpeechText

/**
 * Decides who answers (FR-TOOL-08): Bello itself for the clock, timers, alarms, the weather, the
 * news and its own memory — a provider for everything else, with the conversation so far and the
 * remembered facts attached (FR-MEM-01/03).
 */
class Router(
    context: Context,
    private val gateway: LlmGateway,
    private val config: AppConfig,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
) : Responder {

    /** What the conversation has to do on the side: stop ringing, refresh the countdown. */
    interface Listener {
        fun onSchedulesChanged()
        fun onStopRequested()
    }

    private val app = context.applicationContext
    private val db = BelloDb(app)
    val facts = FactStore(db)
    val alarms = Alarms(app)
    private val session = SessionMemory(config.maxTurns, config.sessionIdleMs)
    private val weather = Weather(app)
    private val news = News(app)

    override fun answer(question: String): Responder.Answer {
        val now = clock()
        val intent = Intents.match(SpeechText.forIntent(question))
        if (intent != Intent.None) {
            FileLog.i(TAG, "intent=${intent.javaClass.simpleName}")
            return handle(intent, now)
        }
        if (session.expireIfIdle(now)) FileLog.i(TAG, "session expired, starting fresh")
        val answer = gateway.ask(question, session.history(now), facts.promptBlock())
        if (!answer.isError) session.add(question, answer.text, now)
        return answer
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
            val report = weather.report(intent.city, intent.tomorrow, config.city)
            if (report == null) offline("Je n'arrive pas à consulter la météo pour le moment.")
            else say(report)
        }

        is Intent.News -> news()

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

        is Intent.None -> say("")
    }

    /** Headlines are facts; turning them into two or three spoken sentences is a provider's job. */
    private fun news(): Responder.Answer {
        val titles = news.headlines(config.newsFeeds)
        if (titles.isEmpty()) return offline("Je n'arrive pas à récupérer les informations.")
        val summary = gateway.ask(ToolReplies.summarisePrompt(titles), emptyList(), "")
        return if (summary.isError) say(ToolReplies.headlinesFallback(titles)) else summary
    }

    private fun say(text: String, emotion: FaceState? = null) =
        Responder.Answer(text, isError = false, emotion = emotion, source = "local")

    private fun offline(text: String) =
        Responder.Answer(text, isError = true, emotion = FaceState.SAD, source = "local")

    private companion object { const val TAG = "router" }
}
