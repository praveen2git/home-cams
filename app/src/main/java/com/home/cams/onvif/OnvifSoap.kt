package com.home.cams.onvif

import okio.ByteString.Companion.toByteString
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class OnvifProfile(
    val token: String,
    val width: Int,
    val height: Int,
    val ptz: Boolean = false,
    val name: String = "",
    val encoding: String = "",
    val fps: Int = 0
)

/**
 * The small part of ONVIF Home Cams needs, as plain text: no ONVIF or XML library.
 * Responses are read with tolerant regexes that ignore namespace prefixes.
 */
object OnvifSoap {

    private const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    private const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    private const val DIGEST_TYPE =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
    private const val BASE64_TYPE =
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"
    private const val PTZ = "http://www.onvif.org/ver20/ptz/wsdl"
    private const val SCHEMA = "http://www.onvif.org/ver10/schema"

    fun probe(messageId: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" """ +
            """xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">""" +
            """<e:Header><w:MessageID>uuid:$messageId</w:MessageID>""" +
            """<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>""" +
            """<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>""" +
            """<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""

    /** `Base64(SHA1(nonce + created + password))` (WS-UsernameToken profile). */
    fun passwordDigest(nonce: ByteArray, created: String, password: String): String {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce)
        sha.update(created.toByteArray(Charsets.UTF_8))
        sha.update(password.toByteArray(Charsets.UTF_8))
        return sha.digest().toByteString().base64()
    }

    fun created(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    /** A SOAP 1.2 envelope; with a user it carries a digest login made at [cameraTimeMs]. */
    fun envelope(body: String, user: String?, password: String?, nonce: ByteArray, cameraTimeMs: Long): String {
        val header = if (user.isNullOrEmpty()) "" else {
            val created = created(cameraTimeMs)
            "<s:Header><Security s:mustUnderstand=\"1\" xmlns=\"$WSSE\"><UsernameToken>" +
                "<Username>${escape(user)}</Username>" +
                "<Password Type=\"$DIGEST_TYPE\">${passwordDigest(nonce, created, password.orEmpty())}</Password>" +
                "<Nonce EncodingType=\"$BASE64_TYPE\">${nonce.toByteString().base64()}</Nonce>" +
                "<Created xmlns=\"$WSU\">$created</Created>" +
                "</UsernameToken></Security></s:Header>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\">$header<s:Body>$body</s:Body></s:Envelope>"
    }

    const val GET_SYSTEM_TIME = """<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>"""
    const val GET_CAPABILITIES =
        """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>All</Category></GetCapabilities>"""
    const val GET_PROFILES = """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>"""

    fun getStreamUri(profileToken: String): String =
        """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup>""" +
            """<Stream xmlns="$SCHEMA">RTP-Unicast</Stream>""" +
            """<Transport xmlns="$SCHEMA"><Protocol>RTSP</Protocol></Transport>""" +
            """</StreamSetup><ProfileToken>${escape(profileToken)}</ProfileToken></GetStreamUri>"""

    /**
     * Starts a move that runs until [ptzStop]. Speeds are -1..1.
     * Only the part that moves is sent: cameras without zoom reject a Zoom element.
     */
    fun continuousMove(profileToken: String, pan: Float, tilt: Float, zoom: Float): String {
        val panTilt = if (pan != 0f || tilt != 0f) """<PanTilt xmlns="$SCHEMA" x="$pan" y="$tilt"/>""" else ""
        val z = if (zoom != 0f) """<Zoom xmlns="$SCHEMA" x="$zoom"/>""" else ""
        return """<ContinuousMove xmlns="$PTZ"><ProfileToken>${escape(profileToken)}</ProfileToken>""" +
            """<Velocity>$panTilt$z</Velocity></ContinuousMove>"""
    }

    fun ptzStop(profileToken: String): String =
        """<Stop xmlns="$PTZ"><ProfileToken>${escape(profileToken)}</ProfileToken>""" +
            """<PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>"""

    private fun tag(name: String) = Regex("<(?:[\\w-]+:)?$name\\b[^>]*>([^<]*)</(?:[\\w-]+:)?$name>")

    private fun first(xml: String, name: String): String? = tag(name).find(xml)?.groupValues?.get(1)?.trim()

    /** The camera's device service address, IPv4 first. */
    fun parseXAddrs(xml: String): String? {
        val all = first(xml, "XAddrs")?.split(Regex("\\s+")).orEmpty()
        return all.firstOrNull { Regex("^https?://\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(it) } ?: all.firstOrNull()
    }

    /** The camera clock in UTC (logins fail when clocks differ). */
    fun parseSystemTime(xml: String): Long? {
        val utc = Regex("UTCDateTime>(.*?)</(?:[\\w-]+:)?UTCDateTime", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?.groupValues?.get(1) ?: return null
        fun n(name: String) = first(utc, name)?.toIntOrNull()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(n("Year") ?: return null, (n("Month") ?: return null) - 1, n("Day") ?: return null,
                n("Hour") ?: return null, n("Minute") ?: return null, n("Second") ?: 0)
        }
        return cal.timeInMillis
    }

    /** The address of one service ("Media", "PTZ") from a GetCapabilities answer. */
    fun parseServiceXAddr(xml: String, service: String): String? {
        val block = Regex("<(?:[\\w-]+:)?$service>(.*?)</(?:[\\w-]+:)?$service>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return null
        return first(block, "XAddr")
    }

    fun parseProfiles(xml: String): List<OnvifProfile> {
        val profiles = Regex("<(?:[\\w-]+:)?Profiles\\b([^>]*)>(.*?)</(?:[\\w-]+:)?Profiles>", RegexOption.DOT_MATCHES_ALL)
        return profiles.findAll(xml).mapNotNull { m ->
            val token = Regex("token=[\"']([^\"']+)[\"']").find(m.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
            val body = m.groupValues[2]
            OnvifProfile(
                token, first(body, "Width")?.toIntOrNull() ?: 0, first(body, "Height")?.toIntOrNull() ?: 0,
                // A profile that can move carries a PTZ configuration.
                ptz = body.contains("PTZConfiguration"),
                name = first(body, "Name").orEmpty(),
                encoding = first(body, "Encoding").orEmpty(),
                fps = first(body, "FrameRateLimit")?.toIntOrNull() ?: 0
            )
        }.toList()
    }

    fun parseStreamUri(xml: String): String? = first(xml, "Uri")?.replace("&amp;", "&")

    /** ONVIF error text, e.g. "Sender not Authorized". */
    fun parseFault(xml: String): String? = first(xml, "Text")

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
