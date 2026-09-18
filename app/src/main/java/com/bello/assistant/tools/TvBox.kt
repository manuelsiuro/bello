package com.bello.assistant.tools

import android.content.Context
import com.bello.assistant.core.FileLog
import com.bello.assistant.net.HttpClients
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The remote protocol of the SFR TV decoder (STB8), as verified in docs/sfr-tv-box.md: one JSON
 * object per WebSocket text frame, on port 7682, no pairing and no key. Pure, unit tested.
 *
 * The box answers `OK` to anything it receives, a made-up key included: a reply means "heard",
 * and only the television tells whether a key name is right.
 */
object TvBoxProtocol {

    const val DEFAULT_PORT = 7682
    const val PATH = "/ws"
    const val DEVICE_ID = "bello"

    const val POWER = "power"
    const val HOME = "home"
    const val BACK = "back"
    const val OK = "ok"
    const val VOL_UP = "volUp"
    const val VOL_DOWN = "volDown"
    const val MUTE = "mute"
    const val CHANNEL_UP = "channelUp"
    const val CHANNEL_DOWN = "channelDown"
    const val PLAY_PAUSE = "playPause"
    const val STOP = "stop"

    /** Every key name the SFR TV app maps for the STB8. */
    val KEYS: Set<String> = setOf(
        POWER, HOME, BACK, OK, VOL_UP, VOL_DOWN, MUTE, CHANNEL_UP, CHANNEL_DOWN, PLAY_PAUSE, STOP,
        "up", "down", "left", "right", "fastForward", "fastBackward", "record",
    ) + (0..9).map { it.toString() }

    fun digits(channel: Int): List<String> = channel.toString().map { it.toString() }

    fun request(action: String, requestId: Long, params: Map<String, String>? = null): String =
        JSONObject().apply {
            put("action", action)
            put("deviceId", DEVICE_ID)
            put("requestId", requestId)
            if (params != null) put("params", JSONObject(params))
        }.toString()

    fun keyRequest(key: String, requestId: Long): String = request("buttonEvent", requestId, mapOf("key" to key))

    data class Reply(val requestId: Long, val action: String, val ok: Boolean, val data: JSONObject)

    /** Null when the text is not a reply at all (a notification, or noise). */
    fun parse(text: String): Reply? = runCatching {
        val root = JSONObject(text)
        if (!root.has("requestId")) return null
        Reply(
            requestId = root.getLong("requestId"),
            action = root.optString("action"),
            ok = root.optString("remoteResponseCode") == "OK",
            data = root.optJSONObject("data") ?: JSONObject(),
        )
    }.getOrNull()

    /** The state in a `getStatus` reply: on, in standby, or unknown. */
    fun power(reply: Reply): Boolean? = when (reply.data.optString("power")) {
        "powerOn" -> true
        "powerOff" -> false
        else -> null
    }
}

/**
 * Drives the decoder. A connection is opened for each command and closed after it — 23 ms on the
 * measured box — so nothing is left to babysit across a box reboot or a config reload, and a
 * multi-key command (a two-digit channel) keeps its one connection for the whole sequence.
 */
class TvBox(context: Context, val host: String, val port: Int = TvBoxProtocol.DEFAULT_PORT) {

    enum class Switch { ALREADY, SWITCHED }

    private val client: OkHttpClient = HttpClients.base(context).newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** True when on, false in standby, null when the box could not be reached. */
    fun isOn(): Boolean? = session { it.ask("getStatus")?.let(TvBoxProtocol::power) }

    /** What the box says it is, for the logs and the settings screen. */
    fun versions(): JSONObject? = session {
        it.ask("getVersions", mapOf("deviceName" to TvBoxProtocol.DEVICE_ID))?.data
    }

    /**
     * The power key is a toggle, so the state is read first and the key only sent when it has to
     * be. Null when the box is unreachable.
     */
    fun switchPower(on: Boolean): Switch? = session { s ->
        val before = s.ask("getStatus")?.let(TvBoxProtocol::power) ?: return@session null
        if (before == on) return@session Switch.ALREADY
        if (s.ask("buttonEvent", mapOf("key" to TvBoxProtocol.POWER)) == null) null else Switch.SWITCHED
    }

    fun press(key: String): Boolean = press(listOf(key))

    /** Keys in sequence on one connection; false when any of them went unanswered. */
    fun press(keys: List<String>, gapMs: Long = KEY_GAP_MS): Boolean = session { s ->
        keys.forEachIndexed { i, key ->
            if (i > 0) Thread.sleep(gapMs)
            s.ask("buttonEvent", mapOf("key" to key)) ?: return@session false
        }
        true
    } ?: false

    private fun <T> session(block: (Session) -> T?): T? {
        val session = Session.open(client, host, port) ?: return null
        return try {
            block(session)
        } finally {
            session.close()
        }
    }

    /** One WebSocket, used from one thread: a request, then its reply, matched on `requestId`. */
    private class Session private constructor(private val socket: WebSocket) {

        private val replies = LinkedBlockingQueue<TvBoxProtocol.Reply>()
        @Volatile private var failed = false
        private var lastId = System.currentTimeMillis()

        fun ask(action: String, params: Map<String, String>? = null): TvBoxProtocol.Reply? {
            val id = ++lastId
            if (failed || !socket.send(TvBoxProtocol.request(action, id, params))) {
                FileLog.w(TAG, "TV_FAIL action=$action reason=send")
                return null
            }
            val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
            while (true) {
                val left = deadline - System.currentTimeMillis()
                val reply = if (left > 0) replies.poll(left, TimeUnit.MILLISECONDS) else null
                if (reply == null) {
                    FileLog.w(TAG, "TV_FAIL action=$action reason=" + if (failed) "closed" else "timeout")
                    return null
                }
                if (reply.requestId != id) continue
                FileLog.i(TAG, "TV_REPLY action=$action${params?.get("key")?.let { " key=$it" } ?: ""} code=${if (reply.ok) "OK" else "KO"}")
                return reply
            }
        }

        fun close() = runCatching { socket.close(1000, null) }.let { }

        companion object {
            fun open(client: OkHttpClient, host: String, port: Int): Session? {
                val opened = LinkedBlockingQueue<Boolean>()
                var session: Session? = null
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.offer(true)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        TvBoxProtocol.parse(text)?.let { session?.replies?.offer(it) }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        FileLog.w(TAG, "TV_FAIL host=$host:$port reason=${t.javaClass.simpleName}: ${t.message}")
                        session?.failed = true
                        opened.offer(false)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        session?.failed = true
                    }
                }
                val url = "ws://$host:$port${TvBoxProtocol.PATH}"
                val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
                val ready = opened.poll(CONNECT_TIMEOUT_MS + REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS) == true
                if (!ready) {
                    FileLog.w(TAG, "TV_FAIL host=$host:$port reason=connect")
                    socket.cancel()
                    return null
                }
                return Session(socket).also { session = it }
            }
        }
    }

    private companion object {
        const val TAG = "tv"
        const val CONNECT_TIMEOUT_MS = 2_000L
        /** Ten times the round trip measured on the box (8–12 ms). */
        const val REPLY_TIMEOUT_MS = 1_500L
        /** Between two digits of a channel number, so the box takes them as one number. */
        const val KEY_GAP_MS = 150L
    }
}
