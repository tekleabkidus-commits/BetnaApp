package com.appcontrol.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import com.wireguard.crypto.Key
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class BetnaVpn(private val context:Context,private val api:AppApi):Tunnel {
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val backend by lazy{GoBackend(context)}
    private val privateStore=SecretStore(context,"betna-vpn-private-key","betna.vpn-key")
    private val pair by lazy{privateStore.read()?.let{KeyPair(Key.fromBase64(it))}?:KeyPair().also{privateStore.write(it.privateKey.toBase64())}}
    @Volatile var required=false
        private set
    @Volatile var connected=false
        private set
    @Volatile private var generation=0L
    @Volatile private var activeSettings=""
    var listener:((Boolean)->Unit)?=null
    override fun getName()="Betna"
    override fun onStateChange(newState:Tunnel.State){if(newState==Tunnel.State.DOWN){connected=false;status(if(required)"failed" else "off");main.post{listener?.invoke(!required)}}}
    fun publicKey()=pair.publicKey.toBase64()
    fun usable():Boolean {
        if(!required)return true
        val cm=context.getSystemService(ConnectivityManager::class.java)
        return connected&&cm.allNetworks.any{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}
    }
    fun websiteSocket():java.net.Socket {
        if(!required)return java.net.Socket()
        check(usable())
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val network=cm.allNetworks.firstOrNull{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}?:error("VPN required")
        return network.socketFactory.createSocket()
    }
    private fun status(value:String){api.preferences.edit().putString("vpn_status",value).apply()}
    fun configure(settings:JSONObject,callback:(Boolean)->Unit){
        generation++;val current=generation;val settingsKey=settings.toString();required=settings.optBoolean("enabled",false)
        if(!required){connected=false;activeSettings="";status("off");executor.execute{runCatching{backend.setState(this,Tunnel.State.DOWN,null)};context.stopService(Intent(context,BetnaVpnMonitor::class.java));main.post{callback(true)}};return}
        if(android.net.VpnService.prepare(context)!=null){status("permission_required");main.post{callback(false)};return}
        if(!settings.optBoolean("provisioned")||settings.optString("address").isBlank()){status("failed");main.post{callback(false)};return}
        if(usable()&&activeSettings==settingsKey){main.post{callback(true)};return}
        connected=false
        status("connecting");androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,BetnaVpnMonitor::class.java))
        executor.execute{
            var success=false
            val servers=settings.optJSONArray("servers")
            if(servers!=null)for(i in 0 until servers.length()){
                if(current!=generation)break
                val server=servers.getJSONObject(i)
                val ok=runCatching{
                    backend.setState(this,Tunnel.State.DOWN,null)
                    val text="[Interface]\nPrivateKey = ${pair.privateKey.toBase64()}\nAddress = ${settings.getString("address")}\nDNS = 1.1.1.1\nIncludedApplications = ${context.packageName}\nMTU = 1280\n[Peer]\nPublicKey = ${server.getString("public_key")}\nEndpoint = ${server.getString("endpoint")}\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25\n"
                    val config=Config.parse(ByteArrayInputStream(text.toByteArray()));backend.setState(this,Tunnel.State.UP,config);api.preferences.edit().putLong("vpn_last_rx",0).putLong("vpn_last_tx",0).apply()
                    val key=Key.fromBase64(server.getString("public_key"))
                    var verified=false
                    repeat(15){if(!verified&&current==generation){Thread.sleep(1000);val p=backend.getStatistics(this).peer(key);verified=p!=null&&p.latestHandshakeEpochMillis()>0}}
                    check(verified)
                }.isSuccess
                if(ok){success=true;break}
            }
            connected=success&&current==generation;if(connected)activeSettings=settingsKey;status(if(connected)"connected" else "failed");api.event(if(connected)"vpn_connected" else "vpn_failed",code=if(connected)"TUNNEL_VERIFIED" else "TUNNEL_UNAVAILABLE")
            if(!connected){runCatching{backend.setState(this,Tunnel.State.DOWN,null)};context.stopService(Intent(context,BetnaVpnMonitor::class.java))}
            main.post{if(current==generation)callback(connected)}
        }
    }
    fun sample(){if(!connected)return;executor.execute{runCatching{
        val s=backend.getStatistics(this);val prefs=api.preferences
        val rx=s.totalRx();val tx=s.totalTx()
        val dr=(rx-prefs.getLong("vpn_last_rx",0)).coerceAtLeast(0);val dt=(tx-prefs.getLong("vpn_last_tx",0)).coerceAtLeast(0)
        prefs.edit().putLong("vpn_rx_bytes",prefs.getLong("vpn_rx_bytes",0)+dr).putLong("vpn_tx_bytes",prefs.getLong("vpn_tx_bytes",0)+dt).putLong("vpn_last_rx",rx).putLong("vpn_last_tx",tx).apply()
        if(!usable()||s.peers().none{s.peer(it)?.latestHandshakeEpochMillis()?.let{time->System.currentTimeMillis()-time<180000}==true}){connected=false;status("failed");main.post{listener?.invoke(false)}}
    }.onFailure{connected=false;status("failed");main.post{listener?.invoke(false)}}}}
}
class BetnaVpnMonitor:Service(){
    private val handler=Handler(Looper.getMainLooper())
    private val tick=object:Runnable{override fun run(){(application as App).vpn.sample();handler.postDelayed(this,10000)}}
    override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("betna_connection","Betna secure connection",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,7,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(73,NotificationCompat.Builder(this,"betna_connection").setSmallIcon(com.appcontrol.mobile.R.drawable.ic_betna_monochrome).setContentTitle("Betna secure connection").setContentText("Connection managed by Betna").setContentIntent(open).setOngoing(true).build());handler.post(tick)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy()}
}
