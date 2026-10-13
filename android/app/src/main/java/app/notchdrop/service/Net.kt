package app.notchdrop.service

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object Net {
    private val skipPrefixes = listOf("rmnet", "ccmni", "dummy", "tun", "ipsec", "clat", "v4-", "p2p", "lo")

    /** IPv4 addresses the Mac can reach us on: Wi-Fi client and hotspot interfaces, never cellular. */
    fun localAddrs(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && skipPrefixes.none { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION") // allNetworks is the simplest way to find a non-default Wi-Fi network.
    fun wifiNetwork(cm: ConnectivityManager): Network? =
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    fun onWifiSubnet(cm: ConnectivityManager, addr: InetAddress): Boolean {
        val net = wifiNetwork(cm) ?: return false
        return cm.getLinkProperties(net)?.linkAddresses?.any { contains(it, addr) } == true
    }

    /**
     * Wi-Fi without internet (or the hotspot case) isn't the default network, so unbound sockets would go out over
     * cellular. Bind to Wi-Fi when the target is on its subnet; hotspot clients are reachable via the tethering routes.
     */
    fun socketFor(cm: ConnectivityManager, addr: InetSocketAddress): Socket {
        val net = wifiNetwork(cm)
        val target = addr.address
        if (net != null && target != null && cm.getLinkProperties(net)?.linkAddresses?.any { contains(it, target) } == true) {
            return net.socketFactory.createSocket()
        }
        return Socket()
    }

    private fun contains(link: LinkAddress, addr: InetAddress): Boolean {
        val a = link.address.address
        val b = addr.address
        if (a.size != b.size) return false
        var bits = link.prefixLength
        for (i in a.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }
}
