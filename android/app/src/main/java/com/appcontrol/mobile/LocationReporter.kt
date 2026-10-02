package com.appcontrol.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class LocationReporter(private val context:Context,private val api:AppApi):LocationListener {
    private val manager=context.getSystemService(LocationManager::class.java)
    private var active=false
    private var lastSent=0L
    fun permission():String=when{
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED->"precise"
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED->"approximate"
        else->"denied"
    }
    @Suppress("MissingPermission")
    fun start(){
        api.preferences.edit().putString("location_permission",permission()).apply()
        if(active||permission()=="denied"||api.configuration.optJSONObject("location")?.optBoolean("enabled",true)==false)return
        active=true
        val interval=api.configuration.optJSONObject("location")?.optLong("interval_seconds",900)?.coerceIn(300,86400)?:900
        val providers=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).filter{runCatching{manager.isProviderEnabled(it)}.getOrDefault(false)}
        providers.forEach { provider->runCatching{
            manager.getLastKnownLocation(provider)?.takeIf{System.currentTimeMillis()-it.time in 0..300000}?.let{onLocationChanged(it)}
            manager.requestLocationUpdates(provider,interval*1000,500f,this)
        } }
    }
    override fun onLocationChanged(location:Location){
        if(!active||permission()=="denied"||api.configuration.optJSONObject("location")?.optBoolean("enabled",true)==false)return
        val interval=(api.configuration.optJSONObject("location")?.optLong("interval_seconds",900)?:900).coerceIn(300,86400)*1000
        if(System.currentTimeMillis()-lastSent<interval||System.currentTimeMillis()-location.time !in 0..300000||!location.hasAccuracy())return
        lastSent=System.currentTimeMillis()
        api.location(JSONObject().put("id",UUID.randomUUID().toString()).put("latitude",location.latitude).put("longitude",location.longitude).put("accuracy_m",location.accuracy.toDouble()).put("precision",permission()).put("observed_at",Instant.ofEpochMilli(location.time).toString()))
    }
    fun stop(){active=false;runCatching{manager.removeUpdates(this)}}
    override fun onProviderEnabled(provider:String){}
    override fun onProviderDisabled(provider:String){}
    @Deprecated("Platform callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
}
