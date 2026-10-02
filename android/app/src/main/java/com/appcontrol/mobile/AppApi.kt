package com.appcontrol.mobile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.common.ConnectionResult
import android.app.ActivityManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
class AppApi(private val context:Context, private val dns:AppDns) {
    private val secrets=SecretStore(context)
    val preferences=context.getSharedPreferences("appcontrol",Context.MODE_PRIVATE)
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    val client:OkHttpClient=OkHttpClient.Builder().socketFactory(ControlSocketFactory(context)).dns(dns).proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).followSslRedirects(false).connectTimeout(12,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    val websiteClient:OkHttpClient by lazy{OkHttpClient.Builder().socketFactory(WebsiteSocketFactory(context)).dns(dns).proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false).followSslRedirects(false).addInterceptor{chain->check((context.applicationContext as App).vpn.usable());chain.proceed(chain.request())}.build()}
    private val urls=BuildConfig.API_URLS.split(',').map { it.trim().trimEnd('/') }.filter { it.startsWith("https://") || (BuildConfig.DEBUG && it.startsWith("http://")) }
    private var token=secrets.read()
    var pushToken:String
        get()=preferences.getString("push_token","") ?: ""
        set(value){preferences.edit().putString("push_token",value).apply()}
    var promotions:Boolean
        get()=preferences.getBoolean("promotions",true)
        set(value){preferences.edit().putBoolean("promotions",value).apply()}
    val isDemo:Boolean get()=urls.isEmpty()
    var sessionId=UUID.randomUUID().toString()
        private set
    val pushAvailable:Boolean get()=BuildConfig.FIREBASE_APPLICATION_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank() && GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context)==ConnectionResult.SUCCESS
    var sessionTrigger="app_open"
    @Volatile var configuration:JSONObject=JSONObject().put("website_url",BuildConfig.WEBSITE_URL).put("support_url","").put("tabs",JSONObject().put("auto_close",true).put("timeout_minutes",60).put("basis","opened").put("max_tabs",8))
        private set
    private val queueFile=File(context.filesDir,"events.json")
    private var queue=runCatching { JSONArray(queueFile.readText()) }.getOrDefault(JSONArray())
    private fun metadata()=JSONObject().put("location_permission",preferences.getString("location_permission","unknown")).put("vpn_status",preferences.getString("vpn_status","off")).put("vpn_rx_bytes",preferences.getLong("vpn_rx_bytes",0)).put("vpn_tx_bytes",preferences.getLong("vpn_tx_bytes",0)).put("version_code",BuildConfig.VERSION_CODE).put("version_name",BuildConfig.VERSION_NAME).put("android_version",Build.VERSION.SDK_INT).put("language",Locale.getDefault().language).put("notifications_enabled",NotificationManagerCompat.from(context).areNotificationsEnabled()).put("promotions_enabled",promotions).put("manufacturer",Build.MANUFACTURER.take(80)).put("model",Build.MODEL.take(120)).put("webview_version",WebViewCompat.getCurrentWebViewPackage(context)?.versionName?.take(80)?:JSONObject.NULL).put("push_available",pushAvailable).put("low_ram",context.getSystemService(ActivityManager::class.java).isLowRamDevice).put("capabilities",DeviceRiskSignals.snapshot().put("proxy_override",WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)).put("safe_browsing",WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)))
    fun async(work:()->Unit){executor.execute{runCatching(work)}}
    private fun http(path:String,body:JSONObject?=null,authenticated:Boolean=true):JSONObject {
        var failure:Exception?=null
        for(base in urls)try {
            val builder=Request.Builder().url("$base/api/v1/$path").header("Accept","application/json")
            if(authenticated)builder.header("Authorization","Bearer ${token?:throw IllegalStateException("Not registered")}")
            if(body!=null)builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            client.newCall(builder.build()).execute().use { response ->
                if(!response.isSuccessful)throw java.io.IOException("API_HTTP_${response.code}")
                return JSONObject(response.body?.string()?:"{}")
            }
        }catch(e:Exception){failure=e}
        throw failure?:java.io.IOException("No API configured")
    }
    private fun enroll(){if(token==null){val result=http("installations",metadata(),false);token=result.getString("token");secrets.write(token!!);preferences.edit().putString("installation_id",result.getString("installation_id")).apply()}}
    private fun verified(envelope:JSONObject):JSONObject {
        require(envelope.getString("algorithm")=="SHA256withRSA")
        val bytes=Base64.decode(envelope.getString("payload"),Base64.DEFAULT)
        val signature=Base64.decode(envelope.getString("signature"),Base64.DEFAULT)
        val trusted=BuildConfig.CONFIG_PUBLIC_KEY.split(',').map{it.trim()}.filter{it.isNotEmpty()}
        require(trusted.any{pin->runCatching{val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(pin,Base64.DEFAULT)));val verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(bytes);verifier.verify(signature)}.getOrDefault(false)}){"Invalid configuration signature"}
        val result=JSONObject(String(bytes,Charsets.UTF_8));val now=System.currentTimeMillis()/1000
        require(result.getLong("expires_at")>now && result.getLong("issued_at")<=now+300){"Configuration expired or device clock incorrect"}
        require(result.getLong("revision")>=preferences.getLong("revision",0)){"Outdated configuration"}
        return result
    }
    private fun applyConfig(envelope:JSONObject){
        val next=verified(envelope);next.optJSONObject("dns")?.let{dns.configure(it)};configuration=next
        preferences.edit().putLong("revision",next.getLong("revision")).putString("signed_config",envelope.toString()).apply()
    }
    var configurationListener:(()->Unit)?=null
    fun refresh(callback:(Boolean,String?)->Unit){executor.execute {
        if(isDemo){main.post{callback(true,null)};return@execute}
        val cached=runCatching{val raw=preferences.getString("signed_config",null)?:error("No cached config");applyConfig(JSONObject(raw))}.isSuccess
        if(cached)main.post{callback(true,"Using saved settings")}
        val outcome=runCatching{enroll();http("heartbeat",metadata().put("foreground",true).put("push_token",if(pushToken.isBlank())JSONObject.NULL else pushToken));applyConfig(http("configuration"));flush()}
        if(!cached)main.post{callback(outcome.isSuccess,if(outcome.isSuccess)null else "Cannot load verified app settings")}
        else if(outcome.isSuccess)main.post{configurationListener?.invoke()}
    }}
    fun startOpening(callback:()->Unit) { sessionId=UUID.randomUUID().toString();executor.execute {
        if(!isDemo)runCatching{http("opening",JSONObject().put("session_id",sessionId))}
        main.post { callback() }
    } }
    fun ping():Boolean = if(isDemo)false else runCatching{http("ping",authenticated=false).getBoolean("ok")}.getOrDefault(false)
    fun connectionReport(report:JSONObject,callback:(Boolean)->Unit){ executor.execute {
        val ok=!isDemo&&runCatching{http("diagnostics",report)}.isSuccess;main.post{callback(ok)}
    } }
    @Volatile private var heartbeatQueued=false
    fun heartbeat(foreground:Boolean){synchronized(this){if(heartbeatQueued)return;heartbeatQueued=true};executor.execute{try{if(!isDemo){enroll();http("heartbeat",metadata().put("foreground",foreground).put("push_token",(if(pushToken.isBlank()) JSONObject.NULL else pushToken)));flush()}}catch(_:Exception){}finally{heartbeatQueued=false}}}
    fun messages(trigger:String,elapsedSeconds:Long,callback:(JSONObject?)->Unit){executor.execute {
        val result=runCatching { if(isDemo||!promotions)null else http("messages?trigger=$trigger&session_trigger=$sessionTrigger&session_id=$sessionId&elapsed_seconds=${elapsedSeconds.coerceIn(0,86400)}").optJSONObject("message") }.getOrNull()
        main.post{callback(result)}
    }}
    @Synchronized fun event(type:String,deliveryId:String?=null,host:String?=null,code:String?=null,durationMs:Long?=null){
        val event=JSONObject().put("id",UUID.randomUUID().toString()).put("type",type).put("session_id",sessionId).put("occurred_at",Instant.now().toString())
        if(deliveryId!=null)event.put("delivery_id",deliveryId)
        if(host!=null && host.matches(Regex("[a-zA-Z0-9.-]{1,253}")))event.put("host",host)
        if(code!=null)event.put("code",code.take(80).replace(Regex("[^a-zA-Z0-9_-]"),"_"))
        if(durationMs!=null)event.put("duration_ms",durationMs.coerceIn(0,3600000))
        queue.put(event);while(queue.length()>500)queue.remove(0);persistQueue()
    }
    @Synchronized private fun persistQueue(){val temp=File(queueFile.parent,"events.tmp");temp.writeText(queue.toString());check(temp.renameTo(queueFile))}
    private fun flush(){
        val items=JSONArray();synchronized(this){val cutoff=Instant.now().minusSeconds(6*86400).toString();for(i in queue.length()-1 downTo 0)if(queue.getJSONObject(i).getString("occurred_at")<cutoff)queue.remove(i);for(i in 0 until minOf(100,queue.length()))items.put(queue.get(i))}
        if(items.length()==0)return
        http("events",JSONObject().put("events",items))
        synchronized(this){val ids=(0 until items.length()).map{items.getJSONObject(it).getString("id")}.toSet();for(i in queue.length()-1 downTo 0)if(queue.getJSONObject(i).getString("id") in ids)queue.remove(i);persistQueue()}
    }
    fun location(sample:JSONObject){async{if(!isDemo)http("location",sample)}}
    fun enrollVpn(publicKey:String){async{if(!isDemo){enroll();http("vpn/peer",JSONObject().put("public_key",publicKey))}}}
    fun commandResult(id:String,ok:Boolean){async{if(!isDemo)http("commands/ack",JSONObject().put("id",id).put("status",if(ok)"completed" else "failed").put("failure_code",if(ok)JSONObject.NULL else "CACHE_CLEAR_FAILED"))}}
    fun optOut(id:String){async{if(!isDemo)http("opt-out",JSONObject().put("delivery_id",id))}}
}
