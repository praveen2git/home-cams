package com.home.cams.onvif

import android.content.Context
import android.net.wifi.WifiManager
import com.home.cams.sources.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CameraException(message: String) : Exception(message)

/** What a camera told us: its stream addresses, and its PTZ service when it can move. */
data class OnvifInfo(val mainUri: String, val subUri: String, val ptzXaddr: String?, val ptzToken: String?)

/** One ONVIF video profile together with its RTSP stream address. */
data class OnvifProfileStream(val profile: OnvifProfile, val uri: String)

/** Calls the camera's ONVIF services over HTTP. */
class OnvifClient(baseClient: OkHttpClient, private val clockMs: () -> Long = System::currentTimeMillis) {

    private val client = baseClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    private val soap = "application/soap+xml; charset=utf-8".toMediaType()
    private val random = SecureRandom()

    /** Camera clock minus TV clock, per device. Logins are dated with the camera's clock. */
    private val offsets = ConcurrentHashMap<String, Long>()

    private fun offset(deviceXAddr: String): Long = offsets.getOrPut(deviceXAddr) {
        val cameraTime = OnvifSoap.parseSystemTime(post(deviceXAddr, OnvifSoap.GET_SYSTEM_TIME, null, null, 0))
        if (cameraTime != null) cameraTime - clockMs() else 0L
    }

    /** Asks for the main and sub stream addresses, and whether the camera has PTZ. */
    suspend fun describe(deviceXAddr: String, user: String?, password: String?): OnvifInfo = withContext(Dispatchers.IO) {
        offsets.remove(deviceXAddr) // the clock may have been fixed since last time
        val offset = offset(deviceXAddr)
        val caps = post(deviceXAddr, OnvifSoap.GET_CAPABILITIES, user, password, offset)
        val media = OnvifSoap.parseServiceXAddr(caps, "Media") ?: deviceXAddr
        val profiles = OnvifSoap.parseProfiles(post(media, OnvifSoap.GET_PROFILES, user, password, offset))
        if (profiles.isEmpty()) throw CameraException("The camera sent no video profiles.")
        val video = profiles.filter { it.width > 0 && it.height > 0 }.ifEmpty { profiles }
        val main = video.maxBy { it.width * it.height }
        val sub = video.minBy { it.width * it.height }
        fun uri(p: OnvifProfile) = OnvifSoap.parseStreamUri(post(media, OnvifSoap.getStreamUri(p.token), user, password, offset))
            ?: throw CameraException("The camera sent no stream address.")
        val mainUri = uri(main)
        val ptzXaddr = OnvifSoap.parseServiceXAddr(caps, "PTZ")
        val ptzProfile = (listOf(main) + profiles).firstOrNull { it.ptz }
        OnvifInfo(
            mainUri, if (sub == main) mainUri else uri(sub),
            ptzXaddr.takeIf { ptzProfile != null }, ptzProfile?.token.takeIf { ptzXaddr != null }
        )
    }

    /** Lists all video profiles reported by the camera along with their RTSP stream addresses. */
    suspend fun profiles(deviceXAddr: String, user: String?, password: String?): List<OnvifProfileStream> = withContext(Dispatchers.IO) {
        offsets.remove(deviceXAddr)
        val offset = offset(deviceXAddr)
        val caps = post(deviceXAddr, OnvifSoap.GET_CAPABILITIES, user, password, offset)
        val media = OnvifSoap.parseServiceXAddr(caps, "Media") ?: deviceXAddr
        val profiles = OnvifSoap.parseProfiles(post(media, OnvifSoap.GET_PROFILES, user, password, offset))
        if (profiles.isEmpty()) throw CameraException("The camera sent no video profiles.")
        profiles.mapNotNull { p ->
            OnvifSoap.parseStreamUri(post(media, OnvifSoap.getStreamUri(p.token), user, password, offset))
                ?.let { OnvifProfileStream(p, it) }
        }
    }

    /** Sends one PTZ command ([OnvifSoap.continuousMove] or [OnvifSoap.ptzStop]) to a camera that has PTZ. */
    suspend fun ptz(source: Source, body: String) = withContext(Dispatchers.IO) {
        val ptzXaddr = source.ptzXaddr ?: throw CameraException("This camera has no PTZ.")
        post(ptzXaddr, body, source.user, source.pass, offset(source.onvifXaddr ?: ptzXaddr))
        Unit
    }

    private fun post(url: String, body: String, user: String?, password: String?, offsetMs: Long): String {
        val nonce = ByteArray(16).also { random.nextBytes(it) }
        val envelope = OnvifSoap.envelope(body, user, password, nonce, clockMs() + offsetMs)
        val response = try {
            val request = Request.Builder().url(url).post(envelope.toByteArray().toRequestBody(soap)).build()
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw CameraException("The camera did not answer (${e.message}).")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 401 || (it.code >= 400 && OnvifSoap.parseFault(text)?.contains("Authoriz", true) == true)) {
                throw CameraException("Wrong camera username or password.")
            }
            if (!it.isSuccessful) {
                throw CameraException("The camera said: ${OnvifSoap.parseFault(text) ?: "HTTP ${it.code}"}")
            }
            return text
        }
    }
}

/** ONVIF WS-Discovery: one multicast probe on the Wi-Fi. */
object CameraDiscovery {

    /** Device service addresses of the cameras that answered, in IP order. */
    suspend fun probe(context: Context, timeoutMs: Int = 3000): List<String> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Android drops multicast replies unless the app holds this lock.
        val lock = wifi?.createMulticastLock("homecams-onvif")?.apply { setReferenceCounted(false); acquire() }
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = 500
                val probe = OnvifSoap.probe(UUID.randomUUID().toString()).toByteArray()
                socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("239.255.255.250"), 3702))
                val found = linkedSetOf<String>()
                val buffer = ByteArray(16 * 1024)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    OnvifSoap.parseXAddrs(String(packet.data, 0, packet.length))?.let { found += it }
                }
                found.sortedBy { ipOrder(it) }
            }
        } finally {
            lock?.release()
        }
    }

    /** Sorts 192.168.1.201 before 192.168.1.206 numerically. */
    fun ipOrder(xaddr: String): Long {
        val ip = Regex("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)").find(xaddr)?.groupValues ?: return Long.MAX_VALUE
        return (1..4).fold(0L) { acc, i -> acc * 256 + ip[i].toLong() }
    }
}
