package com.appcontrol.mobile

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reports observed foreground UI stalls; this is not a replacement for OS ANR reports. */
class UiWatchdog(private val api: AppApi) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadScheduledExecutor()
    @Volatile private var active=false
    @Volatile private var acknowledgedAt=SystemClock.uptimeMillis()
    private var lastReport=0L
    init { worker.scheduleWithFixedDelay({
        if(active){val now=SystemClock.uptimeMillis();val stalled=now-acknowledgedAt
            if(stalled>10000 && now-lastReport>300000){lastReport=now;api.event("ui_stall",code="MAIN_THREAD_TIMEOUT",durationMs=stalled)}
            main.post{acknowledgedAt=SystemClock.uptimeMillis()}
        }
    },2,2,TimeUnit.SECONDS) }
    fun foreground(value:Boolean){active=value;acknowledgedAt=SystemClock.uptimeMillis()}
    fun close(){active=false;worker.shutdownNow();main.removeCallbacksAndMessages(null)}
}
