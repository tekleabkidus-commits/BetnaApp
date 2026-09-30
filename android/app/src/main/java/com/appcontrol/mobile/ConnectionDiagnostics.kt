package com.appcontrol.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.webkit.WebViewFeature
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConnectionDiagnostics(private val context: Context, private val api: AppApi, private val dns: AppDns) {
    fun run(): JSONObject {
        val network = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        val kind = when {
            capabilities == null -> "offline"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val checks = JSONArray()
        fun check(name: String, work: () -> JSONObject) {
            val started = System.currentTimeMillis()
            val result = runCatching(work).getOrElse { JSONObject().put("status","failed").put("code","CONNECTION_FAILED") }
            result.put("name",name).put("duration_ms",(System.currentTimeMillis()-started).coerceIn(0,120000))
            checks.put(result)
        }
        check("api") { JSONObject().put("status", if(api.isDemo) "unavailable" else if(api.ping()) "ok" else "failed") }
        val website = api.configuration.optString("website_url",BuildConfig.WEBSITE_URL)
        check("dns") { val host=Uri.parse(website).host?:error("Missing hostname");require(dns.lookup(host).isNotEmpty());JSONObject().put("status","ok") }
        check("website") {
            require(Uri.parse(website).scheme=="https")
            api.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
                .newCall(Request.Builder().url(website).head().build()).execute().use {
                    JSONObject().put("status",if(it.code<400 || it.code==405) "ok" else "failed").put("http_status",it.code)
                }
        }
        check("webview") { JSONObject().put("status",if(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))"ok" else "unavailable") }
        check("push") { JSONObject().put("status",if(api.pushAvailable && BuildConfig.FIREBASE_APPLICATION_ID.isNotBlank())"ok" else "unavailable") }
        return JSONObject().put("id",UUID.randomUUID().toString()).put("network",kind).put("checks",checks)
    }
}
