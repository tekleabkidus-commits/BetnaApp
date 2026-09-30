package com.appcontrol.mobile

object TabPolicy {
    fun expired(isMain: Boolean, enabled: Boolean, openedAt: Long, lastActivityAt: Long, now: Long, minutes: Int, basis: String): Boolean {
        if (isMain || !enabled) return false
        val start = if (basis == "activity") lastActivityAt else openedAt
        return now - start >= minutes.coerceIn(1, 10080) * 60_000L
    }
}
