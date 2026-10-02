package com.appcontrol.mobile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
/** Management traffic can reach the administrator while a required website tunnel is unavailable. */
class ControlSocketFactory(private val context:Context):SocketFactory(){
    private fun factory():SocketFactory {
        val cm=context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull{network->cm.getNetworkCapabilities(network)?.let{!it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true}?.socketFactory?:getDefault()
    }
    override fun createSocket():Socket=factory().createSocket()
    override fun createSocket(host:String,port:Int):Socket=factory().createSocket(host,port)
    override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int):Socket=factory().createSocket(host,port,local,localPort)
    override fun createSocket(host:InetAddress,port:Int):Socket=factory().createSocket(host,port)
    override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int):Socket=factory().createSocket(host,port,local,localPort)
}
