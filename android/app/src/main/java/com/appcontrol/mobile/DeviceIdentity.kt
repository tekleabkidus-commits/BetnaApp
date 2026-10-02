package com.appcontrol.mobile

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceIdentity {
    fun identifier(context: Context): String? = runCatching {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{1,16}")) && it.any { ch -> ch != '0' } }
            ?: return null
        MessageDigest.getInstance("SHA-256")
            .digest("betna-device:v1:${context.packageName}:$androidId".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrNull()
}
