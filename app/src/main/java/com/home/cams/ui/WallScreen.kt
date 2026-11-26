package com.home.cams.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.home.cams.sources.Cam
import com.home.cams.sources.Wall

// shortcut: 2 is what a cheap TV stick has, read the real count from the TV if stronger TVs stutter.
private const val HARDWARE_TILES = 2

/**
 * The camera wall. Each tile plays the sub stream, unless the viewer picked "Large" for that camera. Only the tiles on the current page have a
 * player, so the live tile cap holds. Arrow keys move the selection and page through the cameras.
 */
@Composable
fun WallScreen(
    cams: List<Cam>,
    wall: Wall,
    note: String?,
    onSelect: (Int) -> Unit,
    onOpen: () -> Unit,
    onTab: (Tab) -> Unit
) {
    // Not null while the remote is on the tab strip.
    var marked by remember { mutableStateOf<Tab?>(null) }
    KeyBox(Modifier.background(Background), onKey = { e ->
        if (e.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@KeyBox false
        val code = e.nativeKeyEvent.keyCode
        val tab = marked
        if (tab != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> marked = Tab.entries[(tab.ordinal - 1).coerceAtLeast(0)]
                KeyEvent.KEYCODE_DPAD_RIGHT -> marked = Tab.entries[(tab.ordinal + 1).coerceAtMost(Tab.entries.size - 1)]
                KeyEvent.KEYCODE_DPAD_DOWN -> marked = null
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (tab == Tab.LIVE) marked = null else onTab(tab)
                else -> return@KeyBox false
            }
            return@KeyBox true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> onSelect(wall.move(-1).selected)
            KeyEvent.KEYCODE_DPAD_RIGHT -> onSelect(wall.move(1).selected)
            KeyEvent.KEYCODE_DPAD_UP -> if (wall.onTopRow) marked = Tab.LIVE else onSelect(wall.move(-wall.columns).selected)
            KeyEvent.KEYCODE_DPAD_DOWN -> onSelect(wall.move(wall.columns).selected)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (cams.isEmpty()) onTab(Tab.SETTINGS) else onOpen()
            KeyEvent.KEYCODE_MENU -> onTab(Tab.SETTINGS)
            else -> return@KeyBox false
        }
        true
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 18.dp), Arrangement.spacedBy(6.dp)) {
            TabBar(Tab.LIVE, marked = marked)
            if (cams.isEmpty()) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Label("No cameras yet", size = 28, color = TextHigh, weight = FontWeight.SemiBold)
                    Label("Press OK to open Settings and add an NVR or a camera.", Modifier.padding(top = 8.dp), size = 18)
                }
                return@KeyBox
            }
            val rows = wall.layout / wall.columns
            val shown = wall.visible.toList()
            for (row in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f), Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until wall.columns) {
                        val slot = row * wall.columns + col
                        val index = shown.getOrNull(slot)
                        // Empty boxes keep the tile sizes equal on a short last page.
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (index != null) {
                                val cam = cams[index]
                                // Keyed by camera, so a tile that stays on screen keeps its player.
                                key(cam.id) { Tile(cam, index == wall.selected, hardware = slot < HARDWARE_TILES) }
                            }
                        }
                    }
                }
            }
            Label(
                listOfNotNull(
                    "Page ${wall.page} of ${wall.pages}",
                    "OK: full screen",
                    "Up from the top row: tabs",
                    note
                ).joinToString("   |   "),
                size = 12, color = TextLow
            )
        }
    }
}

@Composable
private fun Tile(cam: Cam, selected: Boolean, hardware: Boolean) {
    Box(Modifier.fillMaxSize().border(3.dp, if (selected) Accent else Color.Transparent).padding(3.dp)) {
        CamPlayer(cam.liveUri(sub = cam.quality.sub(tile = true)), tile = true, modifier = Modifier.fillMaxSize(), hardware = hardware)
        Label(
            cam.name,
            Modifier.align(Alignment.BottomStart).background(Scrim).padding(horizontal = 8.dp, vertical = 3.dp),
            size = 13, color = if (selected) TextHigh else TextMid
        )
    }
}
