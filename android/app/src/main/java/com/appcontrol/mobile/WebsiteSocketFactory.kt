package com.appcontrol.mobile
import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
class WebsiteSocketFactory(private val context:Context):SocketFactory(){
    override fun createSocket():Socket=(context.applicationContext as App).vpn.websiteSocket()
    private fun connect(host:InetAddress,port:Int,local:InetAddress?=null,localPort:Int=0):Socket=createSocket().apply{if(local!=null)bind(InetSocketAddress(local,localPort));connect(InetSocketAddress(host,port))}
    override fun createSocket(host:String,port:Int)=connect(InetAddress.getByName(host),port)
    override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int)=connect(InetAddress.getByName(host),port,local,localPort)
    override fun createSocket(host:InetAddress,port:Int)=connect(host,port)
    override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int)=connect(host,port,local,localPort)
}
