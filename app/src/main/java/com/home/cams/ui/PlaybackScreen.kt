package com.home.cams.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.home.cams.sources.Cam
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

const val PLAYBACK_DAYS = 14
private const val DAY_MINUTES = 24 * 60
private const val HUD_HIDE_MS = 7_000L

private val DATE_CHIP_TEXT = DateTimeFormatter.ofPattern("EEE d MMM")
private val SHORT_DATE_TEXT = DateTimeFormatter.ofPattern("d MMM")
private val TIME_TEXT = DateTimeFormatter.ofPattern("HH:mm")
private val FULL_TEXT = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss")

private val PanelTopFill = Color(0xCC000000)
private val PanelBottomFill = Color(0xF0000000)
private val TrackFill = Color(0x38FFFFFF)
private val TrackAvailableFill = Color(0x5238BDF8)
private val ChipFill = Color(0x18FFFFFF)
private val ChipFillFocused = Color(0x3038BDF8)
private val ChipActiveDot = Color(0xFF38BDF8)
private val PausedBadgeFill = Color(0xFFD97706)
private val LiveBadgeFill = Color(0xFF0284C7)
private val ErrorBadgeFill = Color(0xFF991B1B)

private enum class PlaybackRow { TIMELINE, CONTROLS, DATES }

private data class JumpControl(val label: String, val minutes: Long)

private val QUICK_CONTROLS = listOf(
    JumpControl("Pause", 0L),
    JumpControl("-30m", -30L),
    JumpControl("-10m", -10L),
    JumpControl("-1m", -1L),
    JumpControl("+1m", 1L),
    JumpControl("+10m", 10L),
    JumpControl("+30m", 30L)
)

private val TIMELINE_HOURS = listOf(0, 3, 6, 9, 12, 15, 18, 21, 24)

/** The picker's choice as a time: [daysBack] days before [today], [minute] minutes after midnight. */
fun pickedTime(today: LocalDate, daysBack: Int, minute: Int): LocalDateTime =
    today.minusDays(daysBack.toLong()).atStartOfDay().plusMinutes(minute.toLong())

/** Where a jump of [minutes] lands, counted from what is on screen now. It never goes past [now]. */
fun jumpTime(start: LocalDateTime, positionMs: Long, minutes: Long, now: LocalDateTime): LocalDateTime =
    minOf(start.plusSeconds(positionMs / 1000).plusMinutes(minutes), now.minusMinutes(1))

/** Initial playback start time: 30 minutes ago, aligned to the minute. */
fun defaultPlaybackStart(now: LocalDateTime): LocalDateTime =
    now.minusMinutes(30).withSecond(0).withNano(0)

/** Latest selectable minute of the day for [daysBack] days before [today]. */
fun maxMinuteForDay(today: LocalDate, daysBack: Int, now: LocalDateTime): Int =
    if (today.minusDays(daysBack.toLong()) == now.toLocalDate()) (now.hour * 60 + now.minute - 1).coerceAtLeast(0)
    else DAY_MINUTES - 1

/** Clamps [daysBack] and [minute] into the valid 14-day window ending before [now]. */
fun clampPlaybackTime(today: LocalDate, daysBack: Int, minute: Int, now: LocalDateTime): LocalDateTime {
    val safeDay = daysBack.coerceIn(0, PLAYBACK_DAYS - 1)
    val safeMinute = minute.coerceIn(0, maxMinuteForDay(today, safeDay, now))
    return pickedTime(today, safeDay, safeMinute)
}

/** Step size for timeline scrubbing so tapping is 1-minute precise and holding moves quickly. */
fun scrubStepMinutes(repeatCount: Int): Int = when {
    repeatCount >= 15 -> 15
    repeatCount >= 5 -> 5
    else -> 1
}

/** Current playback timestamp on screen, clamped so it never runs past [now]. */
fun livePlaybackTime(start: LocalDateTime, positionMs: Long, now: LocalDateTime): LocalDateTime =
    minOf(start.plusSeconds((positionMs / 1000).coerceAtLeast(0L)), now)

private fun dayLabel(today: LocalDate, daysBack: Int): String {
    val date = today.minusDays(daysBack.toLong())
    return when (daysBack) {
        0 -> "Today, ${date.format(SHORT_DATE_TEXT)}"
        1 -> "Yesterday, ${date.format(SHORT_DATE_TEXT)}"
        else -> date.format(DATE_CHIP_TEXT)
    }
}

private fun daysBackFor(today: LocalDate, date: LocalDate): Int =
    ChronoUnit.DAYS.between(date, today).toInt().coerceIn(0, PLAYBACK_DAYS - 1)

/** The Playback tab: the cameras that have recordings. [onOpen] gets the camera's place in [cams]. */
@Composable
fun PlaybackTab(cams: List<Cam>, onTab: (Tab) -> Unit, onOpen: (Int) -> Unit) {
    val list = remember(cams) { cams.withIndex().filter { it.value.playbackUri(LocalDateTime.now()) != null } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Page(
        "Playback",
        if (list.isEmpty()) "No camera here has recordings. Playback needs an NVR that supports it. Add one in Settings > Sources."
        else "Pick a camera to play recordings and choose a date or time.",
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
 * Recorded video with live timestamp, date strip, and 24-hour timeline overlay while playing.
 * There is no speed control (Media3 RTSP has no Scale header), so seeking restarts the stream at the new time.
 */
@Composable
fun PlaybackScreen(cam: Cam) {
    val initialNow = remember { LocalDateTime.now() }
    var playingStart by remember(cam.id) { mutableStateOf(defaultPlaybackStart(initialNow)) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var positionMs by remember(playingStart) { mutableLongStateOf(0L) }
    var playbackState by remember(playingStart) { mutableIntStateOf(Player.STATE_BUFFERING) }
    var hasError by remember(playingStart) { mutableStateOf(false) }
    var paused by remember(playingStart) { mutableStateOf(false) }

    var hudVisible by remember { mutableStateOf(true) }
    var focusRow by remember { mutableStateOf(PlaybackRow.TIMELINE) }
    var controlIndex by remember { mutableIntStateOf(0) }
    var draftDay by remember(cam.id) { mutableIntStateOf(daysBackFor(initialNow.toLocalDate(), playingStart.toLocalDate())) }
    var draftMinute by remember(cam.id) { mutableIntStateOf(playingStart.hour * 60 + playingStart.minute) }
    var scrubbing by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    val currentPlayTime = livePlaybackTime(playingStart, positionMs, now)
    val isReady = playbackState == Player.STATE_READY

    // Poll player progress so the on-screen clock and timeline needle tick smoothly.
    LaunchedEffect(player, playingStart) {
        while (true) {
            val p = player
            if (p != null) {
                positionMs = p.currentPosition.coerceAtLeast(0L)
                playbackState = p.playbackState
                hasError = p.playerError != null
            }
            delay(250L)
        }
    }

    // Keep the timeline cursor synced to the active playback time when the user is not scrubbing.
    LaunchedEffect(currentPlayTime, scrubbing) {
        if (!scrubbing) {
            draftDay = daysBackFor(LocalDate.now(), currentPlayTime.toLocalDate())
            draftMinute = currentPlayTime.hour * 60 + currentPlayTime.minute
        }
    }

    // Auto-hide the overlay while video is actively playing and the user is idle.
    LaunchedEffect(hudVisible, lastInteractionMs, paused, scrubbing, playbackState) {
        if (!hudVisible || paused || scrubbing || playbackState != Player.STATE_READY) return@LaunchedEffect
        delay(HUD_HIDE_MS)
        hudVisible = false
    }

    fun noteInteraction() {
        lastInteractionMs = System.currentTimeMillis()
    }

    fun seekTo(target: LocalDateTime) {
        val currentNow = LocalDateTime.now()
        val bounded = minOf(target, currentNow.minusMinutes(1))
        val oldest = currentNow.toLocalDate().minusDays((PLAYBACK_DAYS - 1).toLong()).atStartOfDay()
        val clamped = if (bounded.isBefore(oldest)) oldest else bounded
        paused = false
        scrubbing = false
        playingStart = clamped
        draftDay = daysBackFor(currentNow.toLocalDate(), clamped.toLocalDate())
        draftMinute = clamped.hour * 60 + clamped.minute
        noteInteraction()
    }

    fun jumpBy(minutes: Long) {
        seekTo(jumpTime(playingStart, player?.currentPosition ?: positionMs, minutes, LocalDateTime.now()))
    }

    fun togglePause() {
        val p = player ?: return
        val nextPaused = !paused
        paused = nextPaused
        p.playWhenReady = !nextPaused
        if (!nextPaused && p.playbackState == Player.STATE_IDLE) p.prepare()
        noteInteraction()
    }

    // Only intercept Back to close the HUD while video is ready; otherwise Back exits Playback immediately.
    BackHandler(enabled = hudVisible && isReady) {
        scrubbing = false
        hudVisible = false
    }

    KeyBox(Modifier.background(Color.Black), onKey = { e ->
        if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@KeyBox false
        val native = e.nativeKeyEvent
        val code = native.keyCode
        val currentNow = LocalDateTime.now()
        val currentToday = currentNow.toLocalDate()

        // Media keys work regardless of whether the overlay is open.
        when (code) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                jumpBy(-10L)
                return@KeyBox true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                jumpBy(10L)
                return@KeyBox true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                hudVisible = true
                togglePause()
                return@KeyBox true
            }
        }

        if (!hudVisible) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> jumpBy(-1L)
                KeyEvent.KEYCODE_DPAD_RIGHT -> jumpBy(1L)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    hudVisible = true
                    focusRow = PlaybackRow.TIMELINE
                    noteInteraction()
                }
                else -> return@KeyBox false
            }
            return@KeyBox true
        }

        noteInteraction()
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                focusRow = when (focusRow) {
                    PlaybackRow.TIMELINE -> PlaybackRow.TIMELINE
                    PlaybackRow.CONTROLS -> PlaybackRow.TIMELINE
                    PlaybackRow.DATES -> PlaybackRow.CONTROLS
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusRow = when (focusRow) {
                    PlaybackRow.TIMELINE -> PlaybackRow.CONTROLS
                    PlaybackRow.CONTROLS -> PlaybackRow.DATES
                    PlaybackRow.DATES -> PlaybackRow.DATES
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when (focusRow) {
                PlaybackRow.TIMELINE -> {
                    val step = scrubStepMinutes(native.repeatCount)
                    draftMinute = (draftMinute - step).coerceIn(0, maxMinuteForDay(currentToday, draftDay, currentNow))
                    scrubbing = true
                }
                PlaybackRow.CONTROLS -> controlIndex = (controlIndex - 1).coerceAtLeast(0)
                PlaybackRow.DATES -> {
                    draftDay = (draftDay + 1).coerceAtMost(PLAYBACK_DAYS - 1)
                    draftMinute = draftMinute.coerceAtMost(maxMinuteForDay(currentToday, draftDay, currentNow))
                    scrubbing = true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (focusRow) {
                PlaybackRow.TIMELINE -> {
                    val step = scrubStepMinutes(native.repeatCount)
                    draftMinute = (draftMinute + step).coerceIn(0, maxMinuteForDay(currentToday, draftDay, currentNow))
                    scrubbing = true
                }
                PlaybackRow.CONTROLS -> controlIndex = (controlIndex + 1).coerceAtMost(QUICK_CONTROLS.lastIndex)
                PlaybackRow.DATES -> {
                    draftDay = (draftDay - 1).coerceAtLeast(0)
                    draftMinute = draftMinute.coerceAtMost(maxMinuteForDay(currentToday, draftDay, currentNow))
                    scrubbing = true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when (focusRow) {
                PlaybackRow.TIMELINE -> {
                    if (scrubbing) seekTo(clampPlaybackTime(currentToday, draftDay, draftMinute, currentNow))
                    else togglePause()
                }
                PlaybackRow.CONTROLS -> {
                    val item = QUICK_CONTROLS[controlIndex]
                    if (item.minutes == 0L) togglePause() else jumpBy(item.minutes)
                }
                PlaybackRow.DATES -> seekTo(clampPlaybackTime(currentToday, draftDay, draftMinute, currentNow))
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (isReady) {
                    scrubbing = false
                    hudVisible = false
                }
            }
            else -> return@KeyBox false
        }
        true
    }) {
        // A new start time gives a new address, so the player restarts there.
        CamPlayer(
            cam.playbackUri(playingStart).orEmpty(),
            tile = false,
            modifier = Modifier.fillMaxSize(),
            errorText = { "No recording at this time" },
            onPlayer = { player = it }
        )

        if (!hudVisible) {
            // Compact corner timestamp while watching full screen.
            Row(
                Modifier
                    .padding(start = 48.dp, top = 27.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Scrim)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        hudVisible = true
                        noteInteraction()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label(cam.name, size = 14, color = TextHigh, weight = FontWeight.SemiBold)
                Label("|", size = 13, color = TextLow)
                Label(currentPlayTime.format(FULL_TEXT), size = 14, color = Accent, weight = FontWeight.Medium)
                if (paused) {
                    Label("PAUSED", size = 12, color = PausedBadgeFill, weight = FontWeight.Bold)
                }
            }
            return@KeyBox
        }

        // Top bar with camera name, playback state, live clock, and remote help.
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(PanelBottomFill, Color.Transparent)))
                    .padding(start = 48.dp, end = 48.dp, top = 27.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Label(cam.name, size = 20, color = TextHigh, weight = FontWeight.SemiBold)
                    val badgeText = when {
                        paused -> "PAUSED"
                        playbackState == Player.STATE_READY -> "PLAYBACK"
                        hasError -> "NO RECORDING"
                        else -> "LOADING"
                    }
                    val badgeColor = when {
                        paused -> PausedBadgeFill
                        playbackState == Player.STATE_READY -> LiveBadgeFill
                        hasError -> ErrorBadgeFill
                        else -> LiveBadgeFill
                    }
                    Label(
                        badgeText,
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        size = 11,
                        color = TextHigh,
                        weight = FontWeight.Bold
                    )
                    Label(currentPlayTime.format(FULL_TEXT), size = 18, color = TextHigh, weight = FontWeight.Medium)
                }
                Label(
                    "Up/Down: row  |  Left/Right: change  |  OK: select  |  Back: hide",
                    size = 13,
                    color = TextMid
                )
            }

            // Bottom camera-app playback overlay: timestamp summary, 24h timeline, quick controls, and date strip.
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, PanelTopFill, PanelBottomFill)))
                    .padding(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val draftDateTime = clampPlaybackTime(today, draftDay, draftMinute, now)
                val maxMin = maxMinuteForDay(today, draftDay, now)
                val playingSameDay = playingStart.toLocalDate() == today.minusDays(draftDay.toLong())
                val playingMinute = (currentPlayTime.hour * 60 + currentPlayTime.minute).coerceIn(0, DAY_MINUTES - 1)

                // Timestamp summary bar directly above the timeline.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val selectedText = "${dayLabel(today, draftDay)}  -  ${draftDateTime.format(TIME_TEXT)}"
                    Label(
                        if (scrubbing) "Select: $selectedText  (Press OK to play)" else "Selected: $selectedText",
                        size = 15,
                        color = if (scrubbing) Accent else TextHigh,
                        weight = if (scrubbing) FontWeight.SemiBold else FontWeight.Medium
                    )
                    Label(
                        "Rewind / FF: -10m / +10m",
                        size = 13,
                        color = TextLow
                    )
                }

                TimelineBar(
                    draftMinute = draftMinute,
                    maxMinute = maxMin,
                    playingMinute = playingMinute.takeIf { playingSameDay },
                    focused = focusRow == PlaybackRow.TIMELINE,
                    onSelectMinute = { minute ->
                        focusRow = PlaybackRow.TIMELINE
                        seekTo(clampPlaybackTime(today, draftDay, minute, LocalDateTime.now()))
                    }
                )

                // Quick transport & relative jump buttons.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QUICK_CONTROLS.forEachIndexed { idx, item ->
                        val isFocused = focusRow == PlaybackRow.CONTROLS && controlIndex == idx
                        val label = if (item.minutes == 0L) (if (paused) "Play" else "Pause") else item.label
                        ControlChip(
                            text = label,
                            focused = isFocused,
                            active = item.minutes == 0L && paused,
                            onClick = {
                                focusRow = PlaybackRow.CONTROLS
                                controlIndex = idx
                                if (item.minutes == 0L) togglePause() else jumpBy(item.minutes)
                            }
                        )
                    }
                }

                // 14-day date strip in chronological order (oldest on left, Today on right).
                DateStrip(
                    today = today,
                    selectedDay = draftDay,
                    playingDay = daysBackFor(today, currentPlayTime.toLocalDate()),
                    focused = focusRow == PlaybackRow.DATES,
                    onSelectDay = { daysBack ->
                        focusRow = PlaybackRow.DATES
                        val currentNow = LocalDateTime.now()
                        seekTo(clampPlaybackTime(currentNow.toLocalDate(), daysBack, draftMinute, currentNow))
                    }
                )
            }
        }
    }
}

@Composable
private fun TimelineBar(
    draftMinute: Int,
    maxMinute: Int,
    playingMinute: Int?,
    focused: Boolean,
    onSelectMinute: (Int) -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (focused) Color(0x1EFFFFFF) else Color(0x14FFFFFF), shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color(0x28FFFFFF), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(maxMinute) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val tappedMinute = (fraction * (DAY_MINUTES - 1)).roundToInt().coerceIn(0, maxMinute)
                        onSelectMinute(tappedMinute)
                    }
                }
        ) {
            val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val availableFraction = ((maxMinute + 1).toFloat() / DAY_MINUTES).coerceIn(0f, 1f)
            val cursorFraction = (draftMinute.toFloat() / (DAY_MINUTES - 1)).coerceIn(0f, 1f)
            val pillWidthDp = 52.dp
            val pillWidthPx = with(density) { pillWidthDp.toPx() }
            val pillOffsetX = ((cursorFraction * widthPx) - pillWidthPx / 2f)
                .coerceIn(0f, (widthPx - pillWidthPx).coerceAtLeast(0f))
                .roundToInt()

            // Floating timestamp above the scrubber needle.
            Box(
                Modifier
                    .offset { IntOffset(pillOffsetX, 0) }
                    .width(pillWidthDp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (focused) Accent else Color(0xCC1E293B))
                    .padding(vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                val hh = draftMinute / 60
                val mm = draftMinute % 60
                Label(
                    "%02d:%02d".format(hh, mm),
                    size = 11,
                    color = if (focused) Color.Black else TextHigh,
                    weight = FontWeight.Bold
                )
            }

            // Full 24-hour track.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(TrackFill)
            ) {
                // Available recording window up to maxMinute.
                Box(
                    Modifier
                        .fillMaxWidth(availableFraction)
                        .height(10.dp)
                        .background(if (focused) TrackAvailableFill else Color(0x44FFFFFF))
                )
            }

            // Current playing needle (shown even while user scrubs elsewhere).
            if (playingMinute != null) {
                val playFraction = (playingMinute.toFloat() / (DAY_MINUTES - 1)).coerceIn(0f, 1f)
                val playOffsetX = (playFraction * widthPx).roundToInt()
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset(playOffsetX - 1, 0) }
                        .width(2.dp)
                        .height(14.dp)
                        .background(TextHigh)
                )
            }

            // Target scrub needle.
            val cursorOffsetX = (cursorFraction * widthPx).roundToInt()
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset { IntOffset(cursorOffsetX - 2, 0) }
                    .width(4.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (focused) Accent else TextHigh)
            )
        }

        // Hour markers across the 24-hour bar.
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            TIMELINE_HOURS.forEach { h ->
                Label("%02d:00".format(h), size = 11, color = TextLow)
            }
        }
    }
}

@Composable
private fun ControlChip(
    text: String,
    focused: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .background(if (focused) ChipFillFocused else if (active) Color(0x33D97706) else ChipFill, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Accent else Color(0x28FFFFFF), shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Label(
            text,
            size = 14,
            color = if (focused) Accent else TextHigh,
            weight = if (focused || active) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun DateStrip(
    today: LocalDate,
    selectedDay: Int,
    playingDay: Int,
    focused: Boolean,
    onSelectDay: (Int) -> Unit
) {
    // Chronological order: oldest (PLAYBACK_DAYS - 1) on left, Today (0) on right.
    val days = remember { (PLAYBACK_DAYS - 1 downTo 0).toList() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(selectedDay) - 3).coerceAtLeast(0))

    LaunchedEffect(selectedDay) {
        val idx = days.indexOf(selectedDay)
        if (idx >= 0) listState.animateScrollToItem((idx - 2).coerceAtLeast(0))
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(days, key = { it }) { daysBack ->
            val isSelected = selectedDay == daysBack
            val isFocused = focused && isSelected
            val isPlaying = playingDay == daysBack
            val date = today.minusDays(daysBack.toLong())
            val title = when (daysBack) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> date.format(DATE_CHIP_TEXT)
            }
            val sub = if (daysBack <= 1) date.format(SHORT_DATE_TEXT) else null
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .clip(shape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelectDay(daysBack) }
                    .background(
                        when {
                            isFocused -> ChipFillFocused
                            isSelected -> Color(0x26FFFFFF)
                            else -> ChipFill
                        },
                        shape
                    )
                    .border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color = when {
                            isFocused -> Accent
                            isSelected -> Color(0x8838BDF8)
                            else -> Color(0x22FFFFFF)
                        },
                        shape = shape
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPlaying) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(ChipActiveDot)
                    )
                }
                Label(
                    title,
                    size = 14,
                    color = if (isFocused) Accent else if (isSelected) TextHigh else TextMid,
                    weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                if (sub != null) {
                    Label(sub, size = 12, color = TextLow)
                }
            }
        }
    }
}
