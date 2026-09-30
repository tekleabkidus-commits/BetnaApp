package com.appcontrol.mobile

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Resolves DNS only. HTTPS is tunneled without inspecting or terminating TLS. */
class AppDns : Dns {
    private val transport = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    @Volatile private var resolvers: List<Dns> = defaults()
    @Volatile private var rules: Map<String, Int> = emptyMap()
    @Volatile private var enabled = true
    @Volatile private var systemFallback = false
    private fun defaults(): List<Dns> = listOf(
        resolver("https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        resolver("https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4"))
    )
    private fun resolver(url: String, ips: List<String>): Dns {
        require(url.startsWith("https://"))
        val hosts = ips.map { ip ->
            require(ip.matches(Regex("[0-9a-fA-F:.]+")))
            InetAddress.getByName(ip).also { require(isPublic(it)) }
        }
        require(hosts.isNotEmpty())
        return DnsOverHttps.Builder().client(transport).url(url.toHttpUrl()).bootstrapDnsHosts(hosts).includeIPv6(true).build()
    }
    fun configure(config: JSONObject) {
        val items = config.optJSONArray("resolvers")
        if (items != null && items.length() > 0) {
            val next = (0 until items.length()).map { i ->
                val r = items.getJSONObject(i); val ips = r.getJSONArray("bootstrap_ips")
                resolver(r.getString("url"), (0 until ips.length()).map { ips.getString(it) })
            }
            resolvers = next
        }
        val mappings = config.optJSONArray("rules")
        rules = if (mappings == null) emptyMap() else (0 until mappings.length()).associate { i ->
            val r = mappings.getJSONObject(i); r.getString("host").lowercase() to r.getInt("resolver_index")
        }
        enabled = config.optBoolean("enabled", true)
        systemFallback = config.optBoolean("system_fallback", false)
    }
    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase().trimEnd('.')
        if (!enabled) return Dns.SYSTEM.lookup(host)
        val preferred = rules.entries.filter { host == it.key || host.endsWith(".${it.key}") }.maxByOrNull { it.key.length }?.value
        val ordered = if (preferred != null && preferred in resolvers.indices) listOf(resolvers[preferred]) + resolvers.filterIndexed { i, _ -> i != preferred } else resolvers
        for (dns in ordered) try { val addresses = dns.lookup(host); if (addresses.isNotEmpty()) return addresses } catch (_: Exception) { }
        if (systemFallback) return Dns.SYSTEM.lookup(host)
        throw UnknownHostException("DNS lookup failed")
    }
    companion object {
        fun isPublic(ip: InetAddress): Boolean {
            if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) return false
            val bytes = ip.address
            if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
            if (bytes.size == 4) {
                val first = bytes[0].toInt() and 255; val second = bytes[1].toInt() and 255
                if (first == 0 || first >= 224 || (first == 100 && second in 64..127)) return false
            }
            return true
        }
    }
}

class ConnectionEngine(private val dns: AppDns) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val slots = Semaphore(32)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    val port: Int get() = server.localPort
    fun start() { executor.execute {
        while (!server.isClosed) try {
            val client = server.accept()
            if (!slots.tryAcquire()) { client.close(); continue }
            sockets.add(client)
            executor.execute { try { relay(client) } catch (_: Exception) { } finally { sockets.remove(client); runCatching { client.close() }; slots.release() } }
        } catch (_: Exception) { if (server.isClosed) break }
    } }
    private fun readLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) { val b = input.read(); if (b < 0) throw java.io.EOFException(); if (b == 10) break; if (b != 13) bytes.write(b); require(bytes.size() <= 8192) }
        return bytes.toString("ISO-8859-1")
    }
    private fun relay(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val first = readLine(input).split(' ', limit = 3); require(first.size == 3)
        val headers = mutableListOf<String>(); var size = 0
        while (true) { val line = readLine(input); if (line.isEmpty()) break; size += line.length; require(size <= 32768); headers.add(line) }
        val connect = first[0] == "CONNECT"
        val uri = URI(if (connect) "https://${first[1]}" else first[1])
        require(uri.userInfo == null && uri.host != null)
        require(connect || uri.scheme == "http")
        val remotePort = if (uri.port > 0) uri.port else if (connect) 443 else 80
        require(remotePort in listOf(80,443))
        var remote: Socket? = null
        for (address in dns.lookup(uri.host).filter { AppDns.isPublic(it) }) {
            val candidate = Socket()
            try { candidate.connect(InetSocketAddress(address, remotePort), 10_000); remote = candidate; break } catch (_: Exception) { candidate.close() }
        }
        if (remote == null) { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
        val upstream = remote; sockets.add(upstream)
        try {
            // A quiet game socket must not be closed just because the app is backgrounded.
            client.soTimeout = if (connect) 0 else 120_000; upstream.soTimeout = if (connect) 0 else 120_000
            if (connect) client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            else {
                val path = uri.rawPath?.ifBlank { "/" } ?: "/"
                val target = path + (uri.rawQuery?.let { "?$it" } ?: "")
                val filtered = headers.filterNot { it.substringBefore(':').lowercase() in listOf("proxy-connection", "proxy-authorization", "connection", "host") }
                val request = "${first[0]} $target ${first[2]}\r\nHost: ${uri.rawAuthority}\r\n" + filtered.joinToString("\r\n") + "\r\nConnection: close\r\n\r\n"
                upstream.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            }
            val upload = executor.submit { try { input.copyTo(upstream.getOutputStream()); upstream.shutdownOutput() } catch (_: Exception) { } }
            try { upstream.getInputStream().copyTo(client.getOutputStream()) } finally { upload.cancel(true) }
        } finally { sockets.remove(upstream); upstream.close(); client.close() }
    }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } }; executor.shutdownNow() }
}
