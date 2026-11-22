package com.home.cams.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.home.cams.sources.Cam
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

const val PLAYBACK_DAYS = 14
private const val STEP_MINUTES = 10
private const val DAY_MINUTES = 24 * 60
private val DAY_TEXT = DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME_TEXT = DateTimeFormatter.ofPattern("HH:mm")
private val FULL_TEXT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss")

/** The picker's choice as a time: [daysBack] days before [today], [minute] minutes after midnight. */
fun pickedTime(today: LocalDate, daysBack: Int, minute: Int): LocalDateTime =
    today.minusDays(daysBack.toLong()).atStartOfDay().plusMinutes(minute.toLong())

/** Where a jump of [minutes] lands, counted from what is on screen now. It never goes past [now]. */
fun jumpTime(start: LocalDateTime, positionMs: Long, minutes: Long, now: LocalDateTime): LocalDateTime =
    minOf(start.plusSeconds(positionMs / 1000).plusMinutes(minutes), now.minusMinutes(1))

/** The Playback tab: the cameras that have recordings. [onOpen] gets the camera's place in [cams]. */
@Composable
fun PlaybackTab(cams: List<Cam>, onTab: (Tab) -> Unit, onOpen: (Int) -> Unit) {
    val list = remember(cams) { cams.withIndex().filter { it.value.playbackUri(LocalDateTime.now()) != null } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Page(
        "Playback",
        if (list.isEmpty()) "No camera here has recordings. Playback needs an NVR that supports it. Add one in Settings > Sources."
        else "Pick a camera, then a day and a time.",
        tab = Tab.PLAYBACK,
        // With no camera to focus, the remote starts on the tab strip.
        tabFocus = first.takeIf { list.isEmpty() },
        onTab = onTab
    ) {
        list.forEachIndexed { i, (index, cam) ->
            MenuRow(cam.name, "Press OK", if (i == 0) Modifier.focusRequester(first) else Modifier) { onOpen(index) }
        }
    }
}

/**
 * Recorded video: pick a day and a time, then play. There is no seek bar and no speed control
 * (Media3 RTSP has no Scale header), so a jump restarts the stream at the new time.
 */
@Composable
fun PlaybackScreen(cam: Cam) {
    val now = remember { LocalDateTime.now() }
    var day by remember { mutableIntStateOf(0) }
    // Start half an hour ago, on a 10-minute mark.
    var minute by remember {
        mutableIntStateOf(((now.hour * 60 + now.minute - 30).coerceAtLeast(0)) / STEP_MINUTES * STEP_MINUTES)
    }
    var row by remember { mutableIntStateOf(1) }
    var playing by remember { mutableStateOf<LocalDateTime?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val picked = pickedTime(now.toLocalDate(), day, minute)

    BackHandler(enabled = playing != null) { playing = null }
    KeyBox(Modifier.background(if (playing == null) Background else Color.Black), onKey = { e ->
        if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@KeyBox false
        val code = e.nativeKeyEvent.keyCode
        val start = playing
        if (start != null) {
            val minutes = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1L
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1L
                KeyEvent.KEYCODE_MEDIA_REWIND -> -10L
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 10L
                else -> return@KeyBox false
            }
            playing = jumpTime(start, player?.currentPosition ?: 0L, minutes, LocalDateTime.now())
            return@KeyBox true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> row = (row - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> row = (row + 1).coerceAtMost(2)
            // Left goes back in time, Right goes forward.
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (row == 0) day = (day + 1).coerceAtMost(PLAYBACK_DAYS - 1)
                else minute = Math.floorMod(minute - STEP_MINUTES, DAY_MINUTES)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (row == 0) day = (day - 1).coerceAtLeast(0)
                else minute = Math.floorMod(minute + STEP_MINUTES, DAY_MINUTES)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (picked.isAfter(LocalDateTime.now())) status = "That time has not happened yet." else {
                    status = null
                    playing = picked
                }
            else -> return@KeyBox false
        }
        true
    }) {
        val start = playing
        if (start != null) {
            // A new start time gives a new address, so the player restarts there.
            CamPlayer(
                cam.playbackUri(start).orEmpty(), tile = false, modifier = Modifier.fillMaxSize(),
                errorText = { "No recording at this time" }, onPlayer = { player = it }
            )
            Label(
                "${cam.name}   |   from ${start.format(FULL_TEXT)}   |   Left/Right: 1 minute   |   Rewind/FF: 10 minutes   |   Back: pick a time",
                Modifier.padding(start = 48.dp, top = 27.dp).background(Scrim).padding(horizontal = 10.dp, vertical = 5.dp),
                size = 14
            )
            return@KeyBox
        }
        Column(Modifier.fillMaxSize(), Arrangement.spacedBy(14.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
            Label("Playback: ${cam.name}", size = 26, color = TextHigh, weight = FontWeight.SemiBold)
            Label(status ?: "Up/Down: choose a row   |   Left/Right: change   |   OK: play", size = 15, color = if (status != null) Accent else TextMid)
            val dayText = when (day) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> picked.format(DAY_TEXT)
            }
            PickerRow("Day", dayText, row == 0)
            PickerRow("Time", picked.format(TIME_TEXT), row == 1)
            Label("Play", Modifier.padding(top = 6.dp), size = 20, color = if (row == 2) Accent else TextLow, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PickerRow(label: String, value: String, selected: Boolean) {
    Label(
        if (selected) "$label   <   $value   >" else "$label   $value",
        size = 22, color = if (selected) Accent else TextMid
    )
}
