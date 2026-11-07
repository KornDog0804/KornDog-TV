package com.lumora.net

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Looks hosts up the normal way, then puts IPv4 addresses first (IPv6 stays as the fallback).
 *
 * Some home networks advertise IPv6 that does not actually route. OkHttp then waits out the full
 * connect timeout on the dead IPv6 address before it tries IPv4, so every request crawls.
 * Trying IPv4 first costs nothing on a healthy network and avoids the stall on a broken one.
 */
object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
}
