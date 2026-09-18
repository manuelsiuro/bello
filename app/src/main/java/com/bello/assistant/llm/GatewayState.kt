package com.bello.assistant.llm

/**
 * Per-provider health: cooldowns after failures (FR-LLM-05) and the daily request counter used to
 * stay inside free tiers (FR-LLM-09). Pure and clock-injected, so it is unit tested without network.
 */
class GatewayState(
    private val baseCooldownMs: Long = 60_000,
    private val dailyLimit: Int = 0,
) {

    data class Status(
        val id: String,
        val ok: Int = 0,
        val failed: Int = 0,
        val consecutiveFails: Int = 0,
        val cooldownUntil: Long = 0,
        val usedToday: Int = 0,
        val day: Long = 0,
        val lastError: String? = null,
        val lastLatencyMs: Long = 0,
    )

    private val byId = LinkedHashMap<String, Status>()

    fun status(id: String): Status = byId[id] ?: Status(id)

    fun all(): List<Status> = byId.values.toList()

    /** True when the provider may be called now: not in cooldown and under its daily cap. */
    fun available(id: String, now: Long): Boolean {
        val s = rolled(status(id), now)
        if (now < s.cooldownUntil) return false
        return dailyLimit <= 0 || s.usedToday < dailyLimit
    }

    fun whyUnavailable(id: String, now: Long): String {
        val s = rolled(status(id), now)
        return when {
            now < s.cooldownUntil -> "cooldown ${(s.cooldownUntil - now + 999) / 1000}s"
            dailyLimit in 1..s.usedToday -> "daily limit $dailyLimit reached"
            else -> "available"
        }
    }

    fun onSuccess(id: String, now: Long, latencyMs: Long) {
        val s = rolled(status(id), now)
        byId[id] = s.copy(
            ok = s.ok + 1,
            consecutiveFails = 0,
            cooldownUntil = 0,
            usedToday = s.usedToday + 1,
            lastError = null,
            lastLatencyMs = latencyMs,
        )
    }

    fun onFailure(id: String, now: Long, failure: LlmResult.Failed) {
        val s = rolled(status(id), now)
        val fails = s.consecutiveFails + 1
        val cooldown = cooldownMs(failure.kind, failure.retryAfterMs, fails, baseCooldownMs)
        // A rate limit still consumed a request on the provider's side.
        val used = if (failure.kind == FailureKind.RATE_LIMIT) s.usedToday + 1 else s.usedToday
        byId[id] = s.copy(
            failed = s.failed + 1,
            consecutiveFails = fails,
            cooldownUntil = now + cooldown,
            usedToday = used,
            lastError = "${failure.kind}: ${failure.detail}",
        )
    }

    /** Counters reset at midnight UTC — close enough to the free tiers' own reset. */
    private fun rolled(s: Status, now: Long): Status {
        val day = dayOf(now)
        return if (s.day == day) s else s.copy(day = day, usedToday = 0)
    }

    companion object {
        fun dayOf(now: Long): Long = Math.floorDiv(now, 86_400_000L)

        /** How long to stop using a provider after a failure. */
        fun cooldownMs(
            kind: FailureKind,
            retryAfterMs: Long?,
            consecutiveFails: Int,
            base: Long = 60_000,
        ): Long = when (kind) {
            // Quota: believe Retry-After when the provider sends one.
            FailureKind.RATE_LIMIT -> retryAfterMs ?: base
            // A bad key or a wrong model will not fix itself; stop asking for a while.
            FailureKind.AUTH -> 30 * 60_000L
            FailureKind.BAD_REQUEST -> 10 * 60_000L
            FailureKind.BLOCKED -> 30 * 60_000L
            // Transient: back off, but come back quickly the first time.
            else -> minOf(15_000L shl (consecutiveFails - 1).coerceIn(0, 5), 5 * 60_000L)
        }
    }
}
