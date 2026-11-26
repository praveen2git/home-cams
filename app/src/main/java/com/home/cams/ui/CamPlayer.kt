package com.home.cams.ui

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val STUCK_WAIT_MS = 200L
private const val CONNECT_TIMEOUT_MS = 4_000

/**
 * Strips non-standard trailing parameters (such as `, AuthVersion="1.1"` from TVT NVRs) from
 * `WWW-Authenticate` RTSP response headers so Media3's strict header parser accepts them.
 */
internal fun cleanRtspHeaderLine(line: String): String {
    if (!line.startsWith("WWW-Authenticate:", ignoreCase = true)) return line
    return Regex(""",\s*AuthVersion="[^"]*"""").replace(line, "")
}

private val H265_RTPMAP = Regex("""a=rtpmap:(\d+)\s+(?:H265|HEVC)/""", RegexOption.IGNORE_CASE)

/**
 * Checks whether an H.265 RTP packet would trigger a fatal `ParserException` in Media3's
 * `RtpH265Reader` (e.g., proprietary NAL types 50..63 or truncated FU/AP payloads). Dropping it at
 * the framing layer lets `RtpH265Reader` treat it as a packet gap and keep playing.
 */
internal fun shouldDropH265RtpPacket(pkt: ByteArray, h265PayloadTypes: Set<Int>): Boolean {
    if (pkt.size < 12) return false
    val v = (pkt[0].toInt() ushr 6) and 0x03
    if (v != 2) return false
    val pt = pkt[1].toInt() and 0x7F
    if (pt !in h265PayloadTypes) return false
    val cc = pkt[0].toInt() and 0x0F
    val hasExt = (pkt[0].toInt() and 0x10) != 0
    var offset = 12 + cc * 4
    if (hasExt) {
        if (offset + 4 > pkt.size) return true
        val extWords = ((pkt[offset + 2].toInt() and 0xFF) shl 8) or (pkt[offset + 3].toInt() and 0xFF)
        offset += 4 + extWords * 4
    }
    val payloadLen = pkt.size - offset
    if (payloadLen <= 0) return true
    val nalType = (pkt[offset].toInt() ushr 1) and 0x3F
    if (nalType >= 50) return true
    if (nalType == 49 && payloadLen < 3) return true
    if (nalType == 48) {
        var p = offset + 2
        var count = 0
        while (pkt.size - p > 2) {
            val nalLen = ((pkt[p].toInt() and 0xFF) shl 8) or (pkt[p + 1].toInt() and 0xFF)
            p += 2
            if (pkt.size - p < nalLen) return true
            p += nalLen
            count++
        }
        if (pkt.size - p > 0 || count < 2) return true
    }
    return false
}

/**
 * Filters RTSP response headers on a TCP socket and drops unsupported H.265 RTP NAL packets
 * (types 50..63 or truncated FU/AP frames) that would otherwise abort Media3's `RtpH265Reader`.
 */
internal class RtspHeaderFilterStream(private val raw: InputStream) : InputStream() {
    private var buf = ByteArray(0)
    private var pos = 0
    private var rawRemaining = 0
    private var inHeaders = false
    private var pendingContentLength = 0
    private val h265PayloadTypes = mutableSetOf<Int>()

    override fun available(): Int = (buf.size - pos) + raw.available()

    override fun close() = raw.close()

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos < buf.size) {
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
        if (rawRemaining > 0) {
            val n = raw.read(b, off, minOf(len, rawRemaining))
            if (n > 0) rawRemaining -= n
            return n
        }
        if (!fillNext()) return -1
        return read(b, off, len)
    }

    private fun readUpTo(dst: ByteArray, start: Int, count: Int): Int {
        var got = start
        while (got < start + count) {
            val n = raw.read(dst, got, start + count - got)
            if (n < 0) break
            got += n
        }
        return got - start
    }

    private fun fillNext(): Boolean {
        while (true) {
            val first = raw.read()
            if (first < 0) return false
            if (!inHeaders && first == '$'.code) {
                val hdr = ByteArray(4)
                hdr[0] = first.toByte()
                val rest = readUpTo(hdr, 1, 3)
                val got = 1 + rest
                if (got < 4) {
                    buf = hdr.copyOf(got)
                    pos = 0
                    return true
                }
                val length = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                val isRtpChannel = (hdr[1].toInt() and 1) == 0
                if (h265PayloadTypes.isNotEmpty() && isRtpChannel && length > 0) {
                    val frame = ByteArray(4 + length)
                    System.arraycopy(hdr, 0, frame, 0, 4)
                    val readLen = readUpTo(frame, 4, length)
                    if (readLen == length) {
                        val pkt = frame.copyOfRange(4, 4 + length)
                        if (shouldDropH265RtpPacket(pkt, h265PayloadTypes)) {
                            continue
                        }
                    }
                    buf = if (readLen == length) frame else frame.copyOf(4 + readLen)
                    pos = 0
                    return true
                }
                buf = hdr
                pos = 0
                rawRemaining = length
                return true
            }
            inHeaders = true
            val lineBytes = ByteArrayOutputStream()
            lineBytes.write(first)
            if (first != '\n'.code) {
                while (true) {
                    val c = raw.read()
                    if (c < 0) break
                    lineBytes.write(c)
                    if (c == '\n'.code) break
                }
            }
            val text = lineBytes.toString("ISO_8859_1")
            val cleaned = cleanRtspHeaderLine(text)
            if (cleaned.startsWith("Content-Length:", ignoreCase = true)) {
                pendingContentLength = cleaned.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            if (cleaned == "\r\n" || cleaned == "\n") {
                inHeaders = false
                if (pendingContentLength in 1..32_768) {
                    val headerBytes = cleaned.toByteArray(Charsets.ISO_8859_1)
                    val body = ByteArray(pendingContentLength)
                    val gotBody = readUpTo(body, 0, pendingContentLength)
                    pendingContentLength = 0
                    val sdp = String(body, 0, gotBody, Charsets.ISO_8859_1)
                    H265_RTPMAP.findAll(sdp).forEach { m ->
                        m.groupValues[1].toIntOrNull()?.let { h265PayloadTypes += it }
                    }
                    val combined = ByteArray(headerBytes.size + gotBody)
                    System.arraycopy(headerBytes, 0, combined, 0, headerBytes.size)
                    System.arraycopy(body, 0, combined, headerBytes.size, gotBody)
                    buf = combined
                    pos = 0
                    return true
                }
                rawRemaining = pendingContentLength
                pendingContentLength = 0
            }
            buf = cleaned.toByteArray(Charsets.ISO_8859_1)
            pos = 0
            return true
        }
    }
}

private class RtspSocket : Socket() {
    private val filtered by lazy { RtspHeaderFilterStream(super.getInputStream()) }
    override fun getInputStream(): InputStream = filtered
}

/**
 * Media3 connects the RTSP socket on the playback thread with no time limit. A camera that is off
 * would block that thread for minutes, so the connect gets a limit here.
 */
private object QuickConnect : SocketFactory() {
    private fun connect(address: InetSocketAddress): Socket {
        val socket = RtspSocket()
        try {
            socket.connect(address, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        return socket
    }

    override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket(address, port)
}

/**
 * Software decoders first. Tiles that all ask for a hardware decoder make a cheap TV take decoders
 * back from the tiles that already play, so the tiles past the first few never ask for one.
 */
@OptIn(UnstableApi::class)
private val SoftwareFirst = MediaCodecSelector { mime, secure, tunneling ->
    MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedByDescending { it.softwareOnly }
}

/** Why a picture failed, in words the viewer can act on. */
fun cameraErrorText(error: PlaybackException): String {
    val causes = generateSequence(error as Throwable?) { it.cause }.toList()
    val text = causes.joinToString(" ") { it.message.orEmpty() }
    return when {
        text.contains("401") || text.contains("Unauthorized", true) -> "Wrong camera username or password"
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> "The TV took the video decoder back. Trying again..."
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "No free video decoder on this TV"
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            // The RTSP connect fails with a plain socket error, not one of the codes above.
            causes.any { it is SocketException || it is SocketTimeoutException || it is UnknownHostException } ->
            "Camera did not answer"
        else -> "No video (${error.errorCodeName})"
    }
}

/** Video stream details reported by Media3 while playing or probing. */
data class StreamInfo(
    val width: Int = 0,
    val height: Int = 0,
    val codec: String = "",
    val fps: Float = 0f,
    val supported: Boolean? = null
) {
    fun summary(): String = buildList {
        if (width > 0 && height > 0) add("${width}x$height")
        if (codec.isNotEmpty()) add(codec)
        if (fps > 0f) add("${fps.roundToInt()}fps")
        if (supported == false) add("unsupported on this TV")
    }.joinToString(" ")
}

/** Last seen video details and errors per stream URI, kept in memory for quick inspection. */
object StreamCache {
    val info = mutableStateMapOf<String, StreamInfo>()
    val errors = mutableStateMapOf<String, String>()
}

private fun codecLabel(mime: String?): String = when (mime?.lowercase()) {
    "video/avc" -> "H.264"
    "video/hevc" -> "H.265"
    "video/mp4v-es" -> "MPEG-4"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/av01" -> "AV1"
    null -> ""
    else -> mime.substringAfter('/').uppercase()
}

@OptIn(UnstableApi::class)
private fun extractStreamInfo(player: ExoPlayer, size: VideoSize? = null, tracks: Tracks? = null): StreamInfo? {
    val vf = player.videoFormat
    val vg = (tracks ?: player.currentTracks).groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
    val tf = vg?.takeIf { it.length > 0 }?.getTrackFormat(0)
    val w = size?.width?.takeIf { it > 0 } ?: vf?.width?.takeIf { it > 0 } ?: tf?.width?.takeIf { it > 0 } ?: 0
    val h = size?.height?.takeIf { it > 0 } ?: vf?.height?.takeIf { it > 0 } ?: tf?.height?.takeIf { it > 0 } ?: 0
    val mime = vf?.sampleMimeType ?: tf?.sampleMimeType
    val fps = vf?.frameRate?.takeIf { it > 0f } ?: tf?.frameRate?.takeIf { it > 0f } ?: 0f
    // If ExoPlayer is actively decoding frames via software fallback, the stream IS supported on this TV.
    val decoded = (size?.width ?: 0) > 0 || (vf?.width ?: 0) > 0
    val supported = if (decoded) true else vg?.takeIf { it.length > 0 }?.let { it.isTrackSupported(0) || it.isTrackSelected(0) }
    if (w == 0 && mime.isNullOrEmpty()) return null
    return StreamInfo(w, h, codecLabel(mime), fps, supported)
}

/**
 * Connects to [uri] briefly to read its video resolution, codec and whether this TV can decode it.
 * Call when no other screen is holding decoders on a low-end TV.
 */
@OptIn(UnstableApi::class)
suspend fun probeStream(context: Context, uri: String, timeoutMs: Long = 5_000): Pair<StreamInfo?, String?> {
    if (uri.isBlank()) return null to "Missing stream address"
    return withContext(Dispatchers.Main) {
        val player = ExoPlayer.Builder(context)
            .setReleaseTimeoutMs(STUCK_WAIT_MS)
            .setDetachSurfaceTimeoutMs(STUCK_WAIT_MS)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2_000, 250, 500).build())
            .build()
        try {
            var best: StreamInfo? = null
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Pair<StreamInfo?, String?>> { cont ->
                    fun finish(info: StreamInfo?, err: String?) {
                        if (cont.isActive) cont.resume(info to err)
                    }
                    player.addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            extractStreamInfo(player, tracks = tracks)?.let {
                                best = it
                                StreamCache.info[uri] = it
                            }
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            extractStreamInfo(player, size = videoSize)?.let {
                                best = it
                                StreamCache.info[uri] = it
                                if (it.width > 0 && it.height > 0) finish(it, null)
                            }
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                val info = extractStreamInfo(player) ?: best
                                if (info != null) {
                                    best = info
                                    StreamCache.info[uri] = info
                                    if (info.width > 0 && info.height > 0) finish(info, null)
                                }
                            }
                        }

                        override fun onPlayerError(e: PlaybackException) {
                            val msg = cameraErrorText(e)
                            StreamCache.errors[uri] = msg
                            finish(best, msg)
                        }
                    })
                    val item = MediaItem.fromUri(uri)
                    player.setMediaSource(
                        if (uri.startsWith("rtsp", true)) {
                            RtspMediaSource.Factory().setForceUseRtpTcp(true).setSocketFactory(QuickConnect).createMediaSource(item)
                        } else {
                            DefaultMediaSourceFactory(context).createMediaSource(item)
                        }
                    )
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            result ?: (best to if (best != null) null else "Timed out waiting for video")
        } finally {
            player.release()
        }
    }
}

/**
 * One player on a SurfaceView, released when it leaves the screen. A failure shows its own reason
 * and never touches the other players.
 *
 * [tile] is the low-end TV mode for the wall: muted, with a small buffer. Every player retries after
 * an error, so a camera that reboots comes back on its own.
 */
@OptIn(UnstableApi::class)
@Composable
fun CamPlayer(
    uri: String,
    tile: Boolean,
    modifier: Modifier = Modifier,
    /** False for a tile that must leave the hardware decoders to others. */
    hardware: Boolean = true,
    errorText: (PlaybackException) -> String = ::cameraErrorText,
    onInfo: (StreamInfo) -> Unit = {},
    onPlayer: (ExoPlayer?) -> Unit = {}
) {
    if (uri.isBlank()) {
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Label("Open Settings > Sources and enter this camera's login", Modifier.padding(12.dp), size = 14, align = TextAlign.Center)
        }
        return
    }
    val context = LocalContext.current
    var error by remember(uri) { mutableStateOf<String?>(null) }
    // Counts the errors in a row. It restarts the retry timer even when the same error text comes again.
    var fails by remember(uri) { mutableIntStateOf(0) }
    var decoderError by remember(uri) { mutableStateOf(false) }
    var hadPicture by remember(uri) { mutableStateOf(false) }
    // False until the first picture, and again after an error.
    var ready by remember(uri) { mutableStateOf(false) }
    var dots by remember { mutableIntStateOf(0) }
    if (!ready) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(400)
                dots = (dots + 1) % 4
            }
        }
    }
    val player = remember(uri) {
        // Short waits: a player stuck on a dead camera must not freeze the remote when it is released.
        val builder = ExoPlayer.Builder(context).setReleaseTimeoutMs(STUCK_WAIT_MS).setDetachSurfaceTimeoutMs(STUCK_WAIT_MS)
            .setRenderersFactory(
                DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                    .setMediaCodecSelector(if (hardware) MediaCodecSelector.DEFAULT else SoftwareFirst)
            )
        if (tile) builder.setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2_000, 250, 500).build())
        builder.build().apply {
            if (tile) volume = 0f
            val item = MediaItem.fromUri(uri)
            setMediaSource(
                if (uri.startsWith("rtsp", true)) {
                    RtspMediaSource.Factory().setForceUseRtpTcp(true).setSocketFactory(QuickConnect).createMediaSource(item)
                } else {
                    DefaultMediaSourceFactory(context).createMediaSource(item)
                }
            )
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = errorText(e)
                    StreamCache.errors[uri] = error.orEmpty()
                    // Every 4xxx code is a decoder error.
                    decoderError = e.errorCode / 1000 == 4
                    ready = false
                    fails++
                }

                override fun onTracksChanged(tracks: Tracks) {
                    extractStreamInfo(this@apply, tracks = tracks)?.let {
                        StreamCache.info[uri] = it
                        StreamCache.errors.remove(uri)
                        onInfo(it)
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    extractStreamInfo(this@apply, size = videoSize)?.let {
                        StreamCache.info[uri] = it
                        StreamCache.errors.remove(uri)
                        onInfo(it)
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        error = null
                        fails = 0
                        ready = true
                        hadPicture = true
                        extractStreamInfo(this@apply)?.let {
                            StreamCache.info[uri] = it
                            StreamCache.errors.remove(uri)
                            onInfo(it)
                        }
                    }
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    val quickRetry = (decoderError && fails <= 3) || (hadPicture && fails <= 2)
    if (error != null) {
        // Decoder-busy or transient stream glitches on an active tile mostly clear on an immediate
        // retry; after that a tile slows down so its retries do not disturb working tiles.
        LaunchedEffect(player, fails) {
            delay(if (!tile || quickRetry) 2_000 else 15_000)
            player.prepare()
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        // Quick retries mostly recover immediately, so they show as loading rather than flashing an error.
        val text = when {
            ready -> null
            error == null || quickRetry -> "Loading" + ".".repeat(dots)
            else -> error
        }
        text?.let {
            Label(it, Modifier.background(Scrim).padding(12.dp), size = 14, align = TextAlign.Center)
        }
    }
    // Declared last so it is disposed first: the player is released before its surface goes away,
    // and a released player never blocks on the surface.
    DisposableEffect(player) {
        onPlayer(player)
        onDispose {
            onPlayer(null)
            player.release()
        }
    }
}
