package com.appcontrol.mobile

import android.os.Build
import android.os.Debug
import java.io.File
import org.json.JSONObject

object DeviceRiskSignals {
    /** These are advisory signals that a modified client can falsify. Never use them alone to deny access. */
    fun snapshot():JSONObject = JSONObject()
        .put("debugger_attached",Debug.isDebuggerConnected())
        .put("root_signal",Build.TAGS?.contains("test-keys")==true || listOf("/system/bin/su","/system/xbin/su").any{runCatching{File(it).exists()}.getOrDefault(false)})
}
