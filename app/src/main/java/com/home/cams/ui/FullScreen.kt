package com.home.cams.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import com.home.cams.onvif.OnvifClient
import com.home.cams.onvif.OnvifSoap
import com.home.cams.sources.Cam
import com.home.cams.sources.Quality
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

private const val PTZ_SPEED = 0.5f

/**
 * Cameras whose main stream this TV could not decode (a cheap TV stops at 1920x1080, many cameras send more).
 * They play the sub stream in full screen until the app restarts or the user changes the camera's resolution.
 */
private val smallOnly = mutableStateListOf<String>()

fun clearSmallOnly(camId: String) {
    smallOnly.remove(camId)
}

/** Pan, tilt and zoom speeds for a remote key in PTZ mode, or null for other keys. */
private fun ptzMove(keyCode: Int): Triple<Float, Float, Float>? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> Triple(-PTZ_SPEED, 0f, 0f)
    KeyEvent.KEYCODE_DPAD_RIGHT -> Triple(PTZ_SPEED, 0f, 0f)
    KeyEvent.KEYCODE_DPAD_UP -> Triple(0f, PTZ_SPEED, 0f)
    KeyEvent.KEYCODE_DPAD_DOWN -> Triple(0f, -PTZ_SPEED, 0f)
    // Many TV remotes have no channel keys, so fast-forward and rewind zoom too.
    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Triple(0f, 0f, PTZ_SPEED)
    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND -> Triple(0f, 0f, -PTZ_SPEED)
    else -> null
}

/**
 * One camera on the main stream. The wall is not on screen now, so its players are already released.
 * Up/Down change camera. OK starts PTZ mode on a camera that can move: hold a key to move, let go to stop.
 */
@Composable
fun FullScreen(
    cams: List<Cam>,
    index: Int,
    onvif: OnvifClient,
    onIndex: (Int) -> Unit,
    onPlayback: () -> Unit,
    onDetails: () -> Unit = {}
) {
    val cam = cams[index]
    var ptz by remember(cam.id) { mutableStateOf(false) }
    var status by remember(cam.id) { mutableStateOf<String?>(null) }
    val canPlayBack = remember(cam.id) { cam.playbackUri(LocalDateTime.now()) != null }
    val scope = rememberCoroutineScope()
    // One command at a time and in order, so a Stop can never overtake its move.
    val order = remember { Mutex() }
    fun send(body: String) {
        scope.launch {
            order.withLock {
                runCatching { onvif.ptz(cam.source, body) }
                    .onSuccess { status = null }
                    .onFailure { status = it.message }
            }
        }
    }
    val token = cam.source.ptzToken.orEmpty()

    BackHandler(enabled = ptz) {
        send(OnvifSoap.ptzStop(token))
        ptz = false
    }
    KeyBox(Modifier.background(Color.Black), onKey = { e ->
        val native = e.nativeKeyEvent
        val code = native.keyCode
        val down = native.action == KeyEvent.ACTION_DOWN
        if (ptz) {
            val move = ptzMove(code) ?: return@KeyBox false
            if (!down) send(OnvifSoap.ptzStop(token))
            else if (native.repeatCount == 0) send(OnvifSoap.continuousMove(token, move.first, move.second, move.third))
            return@KeyBox true
        }
        if (!down) return@KeyBox false
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> onIndex(Math.floorMod(index + 1, cams.size))
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> onIndex(Math.floorMod(index - 1, cams.size))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (cam.hasPtz) ptz = true else return@KeyBox false
            KeyEvent.KEYCODE_DPAD_LEFT -> if (canPlayBack) onPlayback() else return@KeyBox false
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> onDetails()
            else -> return@KeyBox false
        }
        true
    }) {
        val small = cam.quality.sub(tile = false) || (cam.quality == Quality.AUTO && cam.id in smallOnly)
        val uri = cam.liveUri(sub = small)
        var info by remember(uri) { mutableStateOf(StreamCache.info[uri]) }
        var decoderFails by remember(cam.id, small) { mutableIntStateOf(0) }
        CamPlayer(
            uri, tile = false, modifier = Modifier.fillMaxSize(),
            errorText = { e ->
                // Only fall back automatically when on Auto, and ignore the first transient decoder-busy error
                // while the wall player is still releasing its decoder.
                val unsupported = e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                if (cam.quality == Quality.AUTO && !small && e.errorCode / 1000 == 4 && (unsupported || ++decoderFails >= 2)) {
                    smallOnly += cam.id
                }
                cameraErrorText(e)
            },
            onInfo = { info = it }
        )
        val streamLabel = buildString {
            append(if (small) "Sub" else "Main")
            val summary = info?.summary().orEmpty()
            if (summary.isNotEmpty()) append(" ").append(summary)
        }
        val hint = if (ptz) {
            "PTZ   |   Arrows: move   |   Channel or FF/Rewind: zoom   |   Back: leave PTZ"
        } else {
            listOfNotNull(
                streamLabel,
                "Up/Down: next camera",
                "OK: PTZ".takeIf { cam.hasPtz },
                "Left: playback".takeIf { canPlayBack },
                "Right: options",
                "Back: wall",
                "Small picture: the large one is too big for this TV".takeIf { cam.quality == Quality.AUTO && cam.id in smallOnly }
            ).joinToString("   |   ")
        }
        Label(
            listOfNotNull(cam.name, hint, status).joinToString("   |   "),
            Modifier.padding(start = 48.dp, top = 27.dp).background(Scrim).padding(horizontal = 10.dp, vertical = 5.dp),
            size = 14, color = if (ptz) Accent else TextMid
        )
    }
}
