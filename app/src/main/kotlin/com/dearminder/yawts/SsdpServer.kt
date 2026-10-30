package com.dearminder.yawts

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * SSDP (UPnP discovery): answers M-SEARCH queries and announces the server with NOTIFY, so the
 * phone shows up by name in Windows' "Network" folder (and other UPnP browsers), which open the
 * web page from the device description served by [FileServer].
 *
 * @param localIp   the phone's address on the network the announcements go to
 * @param location  URL of the device description, e.g. http://192.168.1.5:8080/.yawts/description.xml
 */
class SsdpServer(
    private val localIp: String,
    private val location: String,
    private val deviceId: String
) {
    private var socket: MulticastSocket? = null
    private var timer: ScheduledExecutorService? = null
    private var stopped = false
    private val group = InetSocketAddress(InetAddress.getByName(GROUP), PORT)

    /** Joins the multicast group and starts answering; throws if port 1900 can't be opened. */
    @Synchronized
    fun start() {
        if (stopped || socket != null) return
        val s = MulticastSocket(PORT)
        val nif = try { NetworkInterface.getByInetAddress(InetAddress.getByName(localIp)) } catch (e: IOException) { null }
        if (nif != null) {
            s.networkInterface = nif
            s.joinGroup(group, nif)
        } else {
            @Suppress("DEPRECATION")
            s.joinGroup(group.address)
        }
        s.timeToLive = 4
        socket = s
        Thread({ listen(s) }, "yawts-ssdp").apply { isDaemon = true; start() }

        // Announce now (twice - UDP gets lost) and then well before the advertised max-age runs out.
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "yawts-ssdp-notify").apply { isDaemon = true } }
            .apply { scheduleWithFixedDelay({ notifyAll("ssdp:alive") }, 0, MAX_AGE_SECONDS / 2L, TimeUnit.SECONDS) }
        timer?.schedule({ notifyAll("ssdp:alive") }, 2, TimeUnit.SECONDS)
    }

    @Synchronized
    fun stop() {
        stopped = true
        timer?.shutdownNow()
        timer = null
        socket?.let {
            notifyAll("ssdp:byebye")
            it.closeQuietly()
        }
        socket = null
    }

    private fun listen(s: MulticastSocket) {
        val buf = ByteArray(2048)
        while (!s.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try { s.receive(packet) } catch (e: IOException) { continue }
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
            val headers = text.split("\r\n", "\n").drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon > 0) line.substring(0, colon).trim().uppercase(Locale.ROOT) to line.substring(colon + 1).trim() else null
            }.toMap()
            if (headers["MAN"]?.contains("ssdp:discover", ignoreCase = true) != true) continue
            val st = headers["ST"] ?: continue
            for (target in targetsFor(st)) send(s, searchResponse(target), packet.socketAddress)
        }
    }

    /** The search targets we match: everything, root devices, our type, or our UUID. */
    private fun targetsFor(st: String): List<String> = when {
        st.equals("ssdp:all", ignoreCase = true) -> notificationTypes()
        st.equals("upnp:rootdevice", ignoreCase = true) -> listOf("upnp:rootdevice")
        st.equals(DEVICE_TYPE, ignoreCase = true) -> listOf(DEVICE_TYPE)
        st.equals("uuid:$deviceId", ignoreCase = true) -> listOf("uuid:$deviceId")
        else -> emptyList()
    }

    private fun notificationTypes() = listOf("upnp:rootdevice", "uuid:$deviceId", DEVICE_TYPE)

    private fun usn(target: String) = if (target.startsWith("uuid:")) target else "uuid:$deviceId::$target"

    private fun searchResponse(target: String) =
        "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\n" +
            "DATE: ${httpDate(System.currentTimeMillis())}\r\n" +
            "EXT:\r\n" +
            "LOCATION: $location\r\n" +
            "SERVER: $SERVER\r\n" +
            "ST: $target\r\n" +
            "USN: ${usn(target)}\r\n\r\n"

    private fun notifyAll(nts: String) {
        val s = socket ?: return
        for (nt in notificationTypes()) {
            val message = "NOTIFY * HTTP/1.1\r\n" +
                "HOST: $GROUP:$PORT\r\n" +
                (if (nts == "ssdp:alive") "CACHE-CONTROL: max-age=$MAX_AGE_SECONDS\r\nLOCATION: $location\r\nSERVER: $SERVER\r\n" else "") +
                "NT: $nt\r\n" +
                "NTS: $nts\r\n" +
                "USN: ${usn(nt)}\r\n\r\n"
            send(s, message, group)
        }
    }

    private fun send(s: MulticastSocket, message: String, to: SocketAddress) {
        val data = message.toByteArray(Charsets.UTF_8)
        try { s.send(DatagramPacket(data, data.size, to)) } catch (e: IOException) { /* best effort */ }
    }

    companion object {
        const val DEVICE_TYPE = "urn:schemas-upnp-org:device:Basic:1"
        private const val GROUP = "239.255.255.250"
        private const val PORT = 1900
        private const val MAX_AGE_SECONDS = 1800
        private const val SERVER = "Android UPnP/1.0 YAWTS/1.0"
    }
}
