package com.tvcampip

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class DiscoveredCamera(val name: String, val host: String, val deviceUrl: String)

/** Descubre dispositivos ONVIF en la red local con WS-Discovery (multicast UDP 239.255.255.250:3702). */
object OnvifDiscovery {

    private const val GROUP = "239.255.255.250"
    private const val PORT = 3702

    private val xaddrsRe = Regex("<(?:\\w+:)?XAddrs>([^<]+)</", RegexOption.IGNORE_CASE)
    private val nameRe = Regex("onvif://www\\.onvif\\.org/name/([^\\s<]+)", RegexOption.IGNORE_CASE)
    private val hardwareRe = Regex("onvif://www\\.onvif\\.org/hardware/([^\\s<]+)", RegexOption.IGNORE_CASE)

    private fun probeMessage(): String = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header>
<w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>
<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
</e:Header>
<e:Body>
<d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe>
</e:Body>
</e:Envelope>"""

    /** Bloquea hasta [timeoutMs]; llamar desde un hilo en segundo plano. */
    fun discover(context: Context, timeoutMs: Int = 4000): List<DiscoveredCamera> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("onvif-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = LinkedHashMap<String, DiscoveredCamera>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket().apply {
                soTimeout = 500
                timeToLive = 4
            }
            val group = InetAddress.getByName(GROUP)
            val data = probeMessage().toByteArray(Charsets.UTF_8)
            val buffer = ByteArray(16384)
            val end = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L

            while (System.currentTimeMillis() < end) {
                val now = System.currentTimeMillis()
                if (now - lastSend >= 1500) {
                    socket.send(DatagramPacket(data, data.size, group, PORT))
                    lastSend = now
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val xml = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val cam = parse(xml, packet.address?.hostAddress ?: "")
                    if (cam != null && cam.host !in found) found[cam.host] = cam
                } catch (_: SocketTimeoutException) {
                    // seguir esperando respuestas hasta agotar el tiempo
                }
            }
        } finally {
            socket?.close()
            lock?.release()
        }
        return found.values.toList()
    }

    private fun parse(xml: String, sourceHost: String): DiscoveredCamera? {
        val addrs = xaddrsRe.find(xml)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+")) ?: return null
        val url = addrs.firstOrNull { it.startsWith("http") && !it.contains("[") } ?: return null
        val host = runCatching { URI(url).host }.getOrNull() ?: sourceHost
        if (host.isEmpty()) return null

        fun scope(re: Regex): String? =
            re.find(xml)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

        val name = scope(nameRe) ?: scope(hardwareRe) ?: host
        return DiscoveredCamera(name = name, host = host, deviceUrl = url)
    }
}
