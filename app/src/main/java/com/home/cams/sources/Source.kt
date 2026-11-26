package com.home.cams.sources

import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** An NVR or a single camera the user added. */
data class Source(
    val id: String,
    val name: String,
    val brand: Brand,
    val host: String,
    val rtspPort: Int = 554,
    val user: String = "",
    val pass: String = "",
    /** NVR channels. Single cameras have 1. */
    val channelCount: Int = 1,
    val onvifXaddr: String? = null,
    /** Stream addresses without login. Filled from ONVIF, or the URL the user typed. */
    val mainUri: String = "",
    val subUri: String = "",
    /** Set when the camera answers ONVIF PTZ. */
    val ptzXaddr: String? = null,
    val ptzToken: String? = null,
    /** Resolution the viewer picked, by channel. A missing channel is [Quality.AUTO]. */
    val quality: Map<Int, Quality> = emptyMap()
) {
    internal fun rtsp(path: String) = withCredentials("rtsp://$host:$rtspPort$path", user, pass)
}

/**
 * One URL builder per brand. v1 uses RTSP only, no private brand API.
 *
 * The app is generic, so [label] never names a brand: the user sees "NVR type 1/2/3" and tries
 * the next type when there is no video. Hikvision and Dahua formats are from their public docs,
 * TVT from common use. Nobody has tried them on hardware yet.
 */
enum class Brand(val label: String, val isNvr: Boolean) {
    TVT("NVR type 1", true),
    HIKVISION("NVR type 2", true),
    DAHUA("NVR type 3", true),
    ONVIF("ONVIF camera", false),
    RTSP("RTSP address", false);

    fun liveUri(src: Source, ch: Int, sub: Boolean): String = when (this) {
        HIKVISION -> src.rtsp("/Streaming/Channels/$ch${if (sub) "02" else "01"}")
        DAHUA -> src.rtsp("/cam/realmonitor?channel=$ch&subtype=${if (sub) 1 else 0}")
        TVT -> src.rtsp("/chID=$ch&streamType=${if (sub) "sub" else "main"}")
        ONVIF, RTSP -> withCredentials(if (sub) src.subUri else src.mainUri, src.user, src.pass)
    }

    /** Recorded video from [start] (NVR local time), or null when this brand has no playback. */
    fun playbackUri(src: Source, ch: Int, start: LocalDateTime): String? = when (this) {
        HIKVISION -> src.rtsp("/Streaming/tracks/${ch}01?starttime=${start.format(HIK_TIME)}")
        DAHUA -> src.rtsp("/cam/playback?channel=$ch&subtype=0&starttime=${start.format(DAHUA_TIME)}")
        // TVT playback format is not known yet (design Milestone 0).
        TVT, ONVIF, RTSP -> null
    }

    private companion object {
        val HIK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val DAHUA_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")
    }
}

/** Which stream a camera plays. "Large" is the main stream, "Small" the sub stream. */
enum class Quality(val label: String) {
    AUTO("Auto: small on the wall, large in full screen"),
    HIGH("Large everywhere"),
    LOW("Small everywhere");

    /** True when the sub stream plays, on a wall tile or in full screen. */
    fun sub(tile: Boolean) = when (this) { AUTO -> tile; HIGH -> false; LOW -> true }
}

/** One picture on the wall: a single camera, or one channel of an NVR. */
data class Cam(val source: Source, val channel: Int) {
    val id get() = "${source.id}#$channel"
    val name get() = if (source.brand.isNvr) "${source.name} $channel" else source.name
    val hasPtz get() = source.ptzXaddr != null && source.ptzToken != null
    val quality get() = source.quality[channel] ?: Quality.AUTO
    fun liveUri(sub: Boolean) = source.brand.liveUri(source, channel, sub)
    fun playbackUri(start: LocalDateTime) = source.brand.playbackUri(source, channel, start)
}

fun List<Source>.cams(): List<Cam> = flatMap { s -> (1..s.channelCount.coerceAtLeast(1)).map { Cam(s, it) } }

/** Puts the login into an RTSP URL, encoded so symbols in passwords work. */
fun withCredentials(uri: String, user: String?, password: String?): String {
    if (user.isNullOrEmpty()) return uri
    val scheme = uri.substringBefore("://", "")
    if (scheme.isEmpty()) return uri
    val rest = uri.substringAfter("://").let { r ->
        // Drop any login already there. Only look before the first '/', a path may hold an '@'.
        val authority = r.substringBefore('/')
        if ('@' in authority) r.substring(authority.lastIndexOf('@') + 1) else r
    }
    val enc = { s: String -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
    return "$scheme://${enc(user)}:${enc(password.orEmpty())}@$rest"
}

fun hostOf(url: String): String = Regex("://(?:[^/@]*@)?([^/:]+)").find(url)?.groupValues?.get(1) ?: url
