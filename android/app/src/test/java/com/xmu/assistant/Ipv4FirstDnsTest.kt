package com.xmu.assistant

import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test

class Ipv4FirstDnsTest {
    private val v4a = InetAddress.getByName("192.0.2.1")
    private val v4b = InetAddress.getByName("192.0.2.2")
    private val v6a = InetAddress.getByName("2001:db8::1")
    private val v6b = InetAddress.getByName("2001:db8::2")

    @Test
    fun `XMU hosts put IPv4 first without losing addresses or stable order`() {
        val dns = Ipv4FirstDns(dnsReturning(v6a, v4a, v6b, v4b))

        assertEquals(listOf(v4a, v4b, v6a, v6b), dns.lookup("ids.xmu.edu.cn"))
    }

    @Test
    fun `non-XMU hosts preserve system address order`() {
        val dns = Ipv4FirstDns(dnsReturning(v6a, v4a, v6b, v4b))

        assertEquals(listOf(v6a, v4a, v6b, v4b), dns.lookup("smtp.qq.com"))
    }

    @Test
    fun `IPv6-only results remain available`() {
        val dns = Ipv4FirstDns(dnsReturning(v6a, v6b))

        assertEquals(listOf(v6a, v6b), dns.lookup("ids.xmu.edu.cn"))
    }

    @Test
    fun `IPv4-only results remain unchanged`() {
        val dns = Ipv4FirstDns(dnsReturning(v4a, v4b))

        assertEquals(listOf(v4a, v4b), dns.lookup("ids.xmu.edu.cn"))
    }

    @Test
    fun `strict mode removes unusable IPv6 when IPv4 is available`() {
        val dns = Ipv4FirstDns(
            delegate = dnsReturning(v6a, v4a, v6b, v4b),
            ipv4OnlyWhenAvailable = true,
        )

        assertEquals(listOf(v4a, v4b), dns.lookup("ids.xmu.edu.cn"))
    }

    @Test
    fun `strict mode keeps IPv6 for IPv6-only hosts`() {
        val dns = Ipv4FirstDns(
            delegate = dnsReturning(v6a, v6b),
            ipv4OnlyWhenAvailable = true,
        )

        assertEquals(listOf(v6a, v6b), dns.lookup("ids.xmu.edu.cn"))
    }

    private fun dnsReturning(vararg addresses: InetAddress): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
    }
}
