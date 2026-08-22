package com.xmu.assistant

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

internal class Ipv4FirstDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val ipv4OnlyWhenAvailable: Boolean = false,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        val normalized = hostname.trimEnd('.').lowercase(Locale.US)
        if (normalized != XMU_DOMAIN && !normalized.endsWith(".$XMU_DOMAIN")) return addresses
        val ipv4 = addresses.filterIsInstance<Inet4Address>()
        if (ipv4OnlyWhenAvailable && ipv4.isNotEmpty()) return ipv4
        return ipv4 + addresses.filterNot { it is Inet4Address }
    }

    private companion object {
        const val XMU_DOMAIN = "xmu.edu.cn"
    }
}

internal object XmuHttpClients {
    private val base = OkHttpClient.Builder()
        // The emulator currently has no usable IPv6 route to XMU. Keep IPv6
        // as a fallback for IPv6-only XMU records, but never let a broken AAAA
        // record win when a usable A record is available.
        .dns(Ipv4FirstDns(ipv4OnlyWhenAvailable = true))
        .eventListenerFactory(NetworkTimingEventListenerFactory(BuildConfig.NETWORK_METRICS))
        .build()

    val login: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // callTimeout 兜底整次调用（含重定向链）：无它时成绩 follow 上限 16 跳 ×
        // 各 23s 可把一次请求拖到数分钟，弱网下占死调用方协程。
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val query: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // 下载客户端关闭自动重定向：签名地址的同源 302 可能跳第三方 CDN，
    // OkHttp 跨主机跟随会保留手动附加的 Cookie 头（外泄会话凭据）。
    // 重定向由 FileDownloadTransport 手动逐跳跟随：同源保留 Cookie，跨主机剥离。
    val download: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        // 大文件下载放宽整调上限：防止慢速滴流永久挂住 BoundedParallel.invokeAll。
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
}
