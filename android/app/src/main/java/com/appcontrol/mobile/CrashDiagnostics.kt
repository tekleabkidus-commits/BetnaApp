package com.appcontrol.mobile

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process

/** Only reason codes and our own source frame are recorded, never exception messages or website data. */
object CrashDiagnostics {
    fun install(context: Context) {
        val preferences=context.getSharedPreferences("appcontrol",Context.MODE_PRIVATE)
        val original=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val frame=error.stackTrace.firstOrNull { it.className.startsWith("com.appcontrol.mobile.") }
            val code=(error.javaClass.simpleName+"_"+(frame?.let { "${it.fileName}_${it.lineNumber}" }?:"UNKNOWN")).take(80)
            runCatching { preferences.edit().putString("last_crash",code).commit() }
            if(original!=null) original.uncaughtException(thread,error)
            else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    fun reportPreviousExit(context: Context, api: AppApi) {
        val preferences=api.preferences
        preferences.getString("last_crash",null)?.let { api.event("app_crash",code=it);preferences.edit().remove("last_crash").apply() }
        if(Build.VERSION.SDK_INT<30) return
        runCatching {
            val last=preferences.getLong("last_exit_report",0)
            val previousStart=preferences.getLong("last_process_start",System.currentTimeMillis())
            val exit=context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName,0,8)
                .firstOrNull { it.processName==context.packageName&&it.timestamp>last&&it.timestamp>=previousStart }
            if(exit!=null) {
                val reason=when(exit.reason) {
                    ApplicationExitInfo.REASON_CRASH->"JAVA_CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE->"NATIVE_CRASH"
                    ApplicationExitInfo.REASON_ANR->"NOT_RESPONDING"
                    ApplicationExitInfo.REASON_LOW_MEMORY->"LOW_MEMORY"
                    ApplicationExitInfo.REASON_USER_REQUESTED->"USER_STOPPED"
                    ApplicationExitInfo.REASON_USER_STOPPED->"USER_STOPPED"
                    ApplicationExitInfo.REASON_EXIT_SELF->"SELF_EXIT"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE->"PERMISSION_CHANGED"
                    ApplicationExitInfo.REASON_SIGNALED->"OS_SIGNAL"
                    else->"OTHER_${exit.reason}"
                }
                api.event("app_exit",code="${reason}_${exit.status}")
                preferences.edit().putLong("last_exit_report",exit.timestamp).apply()
            }
            preferences.edit().putLong("last_process_start",System.currentTimeMillis()).apply()
        }
    }
}
