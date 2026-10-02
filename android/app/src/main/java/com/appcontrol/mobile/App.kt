package com.appcontrol.mobile
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
class App : Application() {
    lateinit var api: AppApi
    val dns by lazy { AppDns(ControlSocketFactory(this)) }
    lateinit var vpn:BetnaVpn
    override fun onCreate() {
        super.onCreate(); api = AppApi(this,dns);vpn=BetnaVpn(this,api)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("announcements","Announcements",NotificationManager.IMPORTANCE_DEFAULT))
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { api.preferences.edit().putString("last_crash", error.javaClass.simpleName.take(60)).commit() }
            original?.uncaughtException(thread, error)
        }
        if (api.pushAvailable && BuildConfig.FIREBASE_APPLICATION_ID.isNotBlank()) {
            val options = FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID).setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build()
            if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this,options)
            runCatching { FirebaseMessaging.getInstance().token.addOnSuccessListener { api.pushToken = it } }
        }
    }
}
