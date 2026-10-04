package com.appcontrol.mobile
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
class PushService:FirebaseMessagingService(){
    override fun onNewToken(token:String){(application as App).api.pushToken=token}
    override fun onMessageReceived(message:RemoteMessage){
        val api=(application as App).api
        val content=runCatching{JSONObject(message.data["campaign"]?:return)}.getOrNull()?:return
        val id=content.optString("delivery_id");if(id.isBlank()||content.optLong("expires_at")<=System.currentTimeMillis()/1000)return
        val prefs=getSharedPreferences("push-dedup",MODE_PRIVATE)
        synchronized(PushService::class.java){
            if(prefs.contains(id))return
            prefs.edit().putLong(id,System.currentTimeMillis()).apply()
            val old=prefs.all.filter{it.value is Long && (it.value as Long)<System.currentTimeMillis()-7*86400000L}.keys
            val edit=prefs.edit();old.forEach{edit.remove(it)};edit.apply()
        }
        api.event("notification_received",id)
        if(!api.promotions||!NotificationManagerCompat.from(this).areNotificationsEnabled()||(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)){
            api.event("notification_suppressed",id,code="permission_or_preference");return
        }
        val intent=Intent(this,MainActivity::class.java).putExtra("push_delivery",id).putExtra("push_url",content.optString("action_url")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending=PendingIntent.getActivity(this,id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"announcements").setSmallIcon(R.drawable.ic_betna_monochrome).setColor(BrowserUi.red).setContentTitle(content.optString("title")).setContentText(content.optString("body")).setStyle(NotificationCompat.BigTextStyle().bigText(content.optString("body"))).setAutoCancel(true).setContentIntent(pending).build()
        runCatching{NotificationManagerCompat.from(this).notify(id.hashCode(),notification)}.onFailure{
            api.event("notification_suppressed",id,code="POST_UNAVAILABLE")
        }
    }
}
