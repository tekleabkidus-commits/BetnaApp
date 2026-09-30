package com.appcontrol.mobile

object OpeningPolicy {
    private val triggers = setOf("app_open", "first_open", "updated")
    fun isOpening(trigger: String) = trigger in triggers
    fun canDisplay(openingOnly: Boolean, resumed: Boolean, startedMs: Long, nowMs: Long, windowSeconds: Int, blocked: Boolean): Boolean =
        resumed && !blocked && (!openingOnly || (nowMs - startedMs in 0..windowSeconds.coerceIn(2,30)*1000L))
}
