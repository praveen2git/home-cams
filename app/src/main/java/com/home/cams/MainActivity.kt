package com.home.cams

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.home.cams.sources.Layouts
import com.home.cams.sources.Source
import com.home.cams.sources.Wall
import com.home.cams.sources.cams
import com.home.cams.ui.EditSourceScreen
import com.home.cams.ui.FullScreen
import com.home.cams.ui.MenuRow
import com.home.cams.ui.Page
import com.home.cams.ui.PlaybackScreen
import com.home.cams.ui.SourcesScreen
import com.home.cams.ui.WallScreen
import com.home.cams.update.Update
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Wall : Screen
    data object Full : Screen
    data object Playback : Screen
    data object Settings : Screen
    data object Sources : Screen
    data class Edit(val source: Source?) : Screen
}

class MainActivity : ComponentActivity() {
    // Kept here, not in the composition, so they survive the app going to the background.
    private var inFront by mutableStateOf(true)
    private var screen by mutableStateOf<Screen>(Screen.Wall)
    private var selected by mutableIntStateOf(0)
    private var update by mutableStateOf<Update?>(null)
    private var updateStatus by mutableStateOf<String?>(null)
    private var updateChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Nothing is drawn in the background, so every player is released there.
        setContent { if (inFront) App() }
    }

    override fun onStart() {
        super.onStart()
        inFront = true
    }

    override fun onStop() {
        super.onStop()
        inFront = false
    }

    @Composable
    private fun App() {
        val app = application as HomeCamsApp
        val config = app.store.config
        val cams = remember(config.sources) { config.sources.cams() }
        val wall = Wall(cams.size, config.layout, selected.coerceIn(0, (cams.size - 1).coerceAtLeast(0)))

        LaunchedEffect(Unit) {
            if (updateChecked) return@LaunchedEffect
            updateChecked = true
            update = runCatching { app.updater.check() }.getOrNull()
        }

        BackHandler(enabled = screen != Screen.Wall) {
            screen = when (screen) {
                Screen.Playback -> Screen.Full
                Screen.Sources -> Screen.Settings
                is Screen.Edit -> Screen.Sources
                else -> Screen.Wall
            }
        }

        when (val s = screen) {
            Screen.Wall -> WallScreen(
                cams, wall,
                note = update?.let { "Update ${it.version} is ready in Settings" },
                onSelect = { selected = it },
                onOpen = { screen = Screen.Full },
                onSettings = { screen = Screen.Settings }
            )
            // A camera can vanish while it is open (its source was deleted): go back to the wall.
            Screen.Full, Screen.Playback -> if (cams.isEmpty()) SideEffect { screen = Screen.Wall } else if (s == Screen.Full) {
                FullScreen(cams, wall.selected, app.onvif, onIndex = { selected = it }, onPlayback = { screen = Screen.Playback })
            } else {
                PlaybackScreen(cams[wall.selected])
            }
            Screen.Settings -> SettingsScreen(app)
            Screen.Sources -> SourcesScreen(app.store, app.onvif, onEdit = { screen = Screen.Edit(it) })
            is Screen.Edit -> EditSourceScreen(
                s.source, app.onvif,
                onSave = { saved ->
                    app.store.update { c ->
                        val at = c.sources.indexOfFirst { it.id == saved.id }
                        c.copy(sources = if (at < 0) c.sources + saved else c.sources.toMutableList().also { it[at] = saved })
                    }
                    screen = Screen.Sources
                },
                onDelete = {
                    app.store.update { c -> c.copy(sources = c.sources.filterNot { it.id == s.source?.id }) }
                    screen = Screen.Sources
                }
            )
        }
    }

    @Composable
    private fun SettingsScreen(app: HomeCamsApp) {
        val config = app.store.config
        val scope = rememberCoroutineScope()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        fun next(options: List<Int>, current: Int) = options[(options.indexOf(current) + 1) % options.size]

        Page("Settings") {
            MenuRow("Sources", "${config.sources.size} added", Modifier.focusRequester(first)) { screen = Screen.Sources }
            MenuRow("Live tile cap", "${config.tileCap}   (lower it if tiles fail on this TV)") {
                app.store.update { it.copy(tileCap = next(Layouts.ALL, it.tileCap)) }
            }
            MenuRow(
                "Wall layout",
                when (config.layout) { 1 -> "1 camera"; 2 -> "2 side by side"; 4 -> "2 x 2"; 6 -> "3 x 2"; else -> "3 x 3" }
            ) {
                app.store.update { it.copy(layout = next(Layouts.allowed(it.tileCap), it.layout)) }
            }
            val found = update
            MenuRow(
                "App update",
                updateStatus ?: if (found != null) "Version ${found.version} is ready. OK to install"
                else "Version ${BuildConfig.VERSION_NAME}. OK to check"
            ) {
                scope.launch {
                    if (found == null) {
                        updateStatus = "Checking..."
                        runCatching { app.updater.check() }
                            .onSuccess {
                                update = it
                                updateStatus = if (it == null) "Version ${BuildConfig.VERSION_NAME} is the newest" else null
                            }
                            .onFailure { updateStatus = "Could not check: ${it.message}" }
                    } else {
                        runCatching { app.updater.install(found) { p -> updateStatus = "Downloading ${(p * 100).toInt()}%" } }
                            .onSuccess { updateStatus = "Opening the installer..." }
                            .onFailure { updateStatus = "Update failed: ${it.message}" }
                    }
                }
            }
        }
    }
}
