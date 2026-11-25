package com.remotephone.app

import android.content.res.Resources
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** Best local IPv4 address for pairing (prefers site-local Wi-Fi addresses). */
    fun localIpv4(): String? {
        var fallback: String? = null
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (addr.isSiteLocalAddress) return host
                        if (fallback == null) fallback = host
                    }
                }
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    /** Real display size in pixels. */
    fun displaySize(): Pair<Int, Int> {
        val m = Resources.getSystem().displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    /** Capture size: real display size, width capped at 1080, even dimensions (H.264). */
    fun captureSize(): Pair<Int, Int> {
        var (w, h) = displaySize()
        if (w > 1080) {
            h = h * 1080 / w
            w = 1080
        }
        return Pair(w and 1.inv(), h and 1.inv())
    }
}
