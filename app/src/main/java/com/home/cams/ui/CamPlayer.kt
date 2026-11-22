package com.home.cams.ui

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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.SocketFactory

private const val STUCK_WAIT_MS = 200L
private const val CONNECT_TIMEOUT_MS = 4_000

/**
 * Media3 connects the RTSP socket on the playback thread with no time limit. A camera that is off
 * would block that thread for minutes, so the connect gets a limit here.
 */
private object QuickConnect : SocketFactory() {
    private fun connect(address: InetSocketAddress): Socket {
        val socket = Socket()
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
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) error = null
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    if (error != null) {
        // Full screen retries fast: the TV can take its decoder back while the wall's players are still closing.
        LaunchedEffect(player, error) {
            delay(if (tile) 15_000 else 2_000)
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
        error?.let {
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
