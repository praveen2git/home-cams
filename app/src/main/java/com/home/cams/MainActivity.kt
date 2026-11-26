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
import androidx.compose.ui.platform.LocalContext
import com.home.cams.onvif.OnvifProfileStream
import com.home.cams.sources.Brand
import com.home.cams.sources.Cam
import com.home.cams.sources.Layouts
import com.home.cams.sources.Quality
import com.home.cams.sources.Source
import com.home.cams.sources.Wall
import com.home.cams.sources.cams
import com.home.cams.sources.maskCredentials
import com.home.cams.ui.EditSourceScreen
import com.home.cams.ui.FullScreen
import com.home.cams.ui.MenuRow
import com.home.cams.ui.Page
import com.home.cams.ui.PlaybackScreen
import com.home.cams.ui.PlaybackTab
import com.home.cams.ui.SourcesScreen
import com.home.cams.ui.StreamCache
import com.home.cams.ui.Tab
import com.home.cams.ui.WallScreen
import com.home.cams.ui.clearSmallOnly
import com.home.cams.ui.probeStream
import com.home.cams.update.Update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

private sealed interface Screen {
    data object Wall : Screen
    data object Full : Screen
    data object PlaybackList : Screen
    /** [back] is where Back returns to: the Playback tab or the full screen camera. */
    data class Playback(val back: Screen) : Screen
    data object Settings : Screen
    data object Sources : Screen
    data object Resolution : Screen
    data class CameraDetails(val camId: String, val back: Screen) : Screen
    data class Edit(val source: Source?, val back: Screen = Screen.Sources) : Screen
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
        val scope = rememberCoroutineScope()
        val wall = Wall(cams.size, config.layout, selected.coerceIn(0, (cams.size - 1).coerceAtLeast(0)))

        LaunchedEffect(Unit) {
            if (updateChecked) return@LaunchedEffect
            updateChecked = true
            update = runCatching { app.updater.check() }.getOrNull()
        }

        BackHandler(enabled = screen != Screen.Wall) {
            screen = when (val now = screen) {
                is Screen.Playback -> now.back
                Screen.Sources, Screen.Resolution -> Screen.Settings
                is Screen.CameraDetails -> now.back
                is Screen.Edit -> now.back
                else -> Screen.Wall
            }
        }

        when (val s = screen) {
            Screen.Wall -> WallScreen(
                cams, wall,
                note = update?.let { "Update ${it.version} is ready in Settings" },
                onSelect = { selected = it },
                onOpen = { screen = Screen.Full },
                onTab = ::open
            )
            // A camera can vanish while it is open (its source was deleted): go back to the wall.
            Screen.Full -> if (cams.isEmpty()) SideEffect { screen = Screen.Wall } else {
                FullScreen(
                    cams, wall.selected, app.onvif,
                    onIndex = { selected = it },
                    onPlayback = { screen = Screen.Playback(Screen.Full) },
                    onDetails = { screen = Screen.CameraDetails(cams[wall.selected].id, Screen.Full) }
                )
            }
            is Screen.Playback -> if (cams.isEmpty()) SideEffect { screen = Screen.Wall } else PlaybackScreen(cams[wall.selected])
            Screen.PlaybackList -> PlaybackTab(cams, ::open) {
                selected = it
                screen = Screen.Playback(Screen.PlaybackList)
            }
            Screen.Settings -> SettingsScreen(app)
            Screen.Resolution -> ResolutionScreen(cams)
            is Screen.CameraDetails -> {
                val cam = cams.firstOrNull { it.id == s.camId }
                if (cam == null) SideEffect { screen = s.back } else CameraDetailsScreen(app, cam, s.back)
            }
            Screen.Sources -> SourcesScreen(app.store, app.onvif, onEdit = { screen = Screen.Edit(it) })
            is Screen.Edit -> EditSourceScreen(
                s.source, app.onvif,
                onSave = { saved ->
                    app.store.update { c ->
                        val at = c.sources.indexOfFirst { it.id == saved.id }
                        c.copy(sources = if (at < 0) c.sources + saved else c.sources.toMutableList().also { it[at] = saved })
                    }
                    // Cameras in one home mostly share a login. Typing it once fills every found camera
                    // that still has none. A camera that refuses it just stays without a login.
                    if (saved.brand == Brand.ONVIF && saved.user.isNotEmpty()) {
                        app.store.config.sources
                            .filter { it.brand == Brand.ONVIF && it.id != saved.id && it.user.isEmpty() && it.mainUri.isEmpty() }
                            .forEach { other ->
                                val xaddr = other.onvifXaddr ?: return@forEach
                                scope.launch {
                                    val info = runCatching { app.onvif.describe(xaddr, saved.user, saved.pass) }.getOrNull() ?: return@launch
                                    app.store.update { c ->
                                        c.copy(sources = c.sources.map {
                                            if (it.id != other.id) it else it.copy(
                                                user = saved.user, pass = saved.pass, mainUri = info.mainUri, subUri = info.subUri,
                                                ptzXaddr = info.ptzXaddr, ptzToken = info.ptzToken
                                            )
                                        })
                                    }
                                }
                            }
                    }
                    screen = s.back
                },
                onDelete = {
                    app.store.update { c -> c.copy(sources = c.sources.filterNot { it.id == s.source?.id }) }
                    screen = Screen.Sources
                }
            )
        }
    }

    private fun open(tab: Tab) {
        screen = when (tab) {
            Tab.LIVE -> Screen.Wall
            Tab.PLAYBACK -> Screen.PlaybackList
            Tab.SETTINGS -> Screen.Settings
        }
    }

    /** One row per camera. OK opens that camera's resolution, stream details and options. */
    @Composable
    private fun ResolutionScreen(cams: List<Cam>) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (cams.isNotEmpty()) first.requestFocus() }
        Page("Camera resolution", "Pick a camera to check its resolution, stream details and options.") {
            if (cams.isEmpty()) MenuRow("No cameras yet", "Add one in Sources") { screen = Screen.Sources }
            cams.forEachIndexed { i, cam ->
                val mainUri = cam.liveUri(sub = false)
                val subUri = cam.liveUri(sub = true)
                val mainInfo = StreamCache.info[mainUri]?.summary().orEmpty()
                val subInfo = StreamCache.info[subUri]?.summary().orEmpty()
                val dims = listOfNotNull(
                    mainInfo.takeIf { it.isNotEmpty() }?.let { "Main $it" },
                    subInfo.takeIf { it.isNotEmpty() && subUri != mainUri }?.let { "Sub $it" }
                ).joinToString(", ")
                val value = listOfNotNull(cam.quality.label, dims.takeIf { it.isNotEmpty() }).joinToString("   |   ")
                MenuRow(cam.name, value, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    screen = Screen.CameraDetails(cam.id, Screen.Resolution)
                }
            }
        }
    }

    /** Resolution setting, live stream probe, ONVIF profiles and source details for one camera. */
    @Composable
    private fun CameraDetailsScreen(app: HomeCamsApp, cam: Cam, back: Screen) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        var checking by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        var onvifProfiles by remember(cam.id) { mutableStateOf<List<OnvifProfileStream>>(emptyList()) }
        val mainUri = cam.liveUri(sub = false)
        val subUri = cam.liveUri(sub = true)
        val sameStream = subUri == mainUri && mainUri.isNotEmpty()

        fun checkAll() {
            if (checking) return
            checking = true
            status = "Checking streams..."
            scope.launch {
                val (mInfo, mErr) = probeStream(context, mainUri)
                if (!sameStream) probeStream(context, subUri)
                val xaddr = cam.source.onvifXaddr
                if (xaddr != null) {
                    runCatching {
                        app.onvif.profiles(xaddr, cam.source.user.ifEmpty { null }, cam.source.pass)
                    }.onSuccess { onvifProfiles = it }
                }
                status = when {
                    mInfo != null && mInfo.width > 0 -> "Main: ${mInfo.summary()}"
                    mErr != null -> "Main stream failed: $mErr"
                    else -> "Check finished"
                }
                checking = false
            }
        }

        Page(
            cam.name,
            status ?: "${cam.source.brand.label}   |   ${cam.source.host}:${cam.source.rtspPort}   |   Large looks sharper but a cheap TV may not play many large tiles."
        ) {
            MenuRow("Resolution", cam.quality.label, Modifier.focusRequester(first)) {
                val next = Quality.entries[(cam.quality.ordinal + 1) % Quality.entries.size]
                clearSmallOnly(cam.id)
                app.store.update { c ->
                    c.copy(sources = c.sources.map {
                        if (it.id != cam.source.id) it
                        else it.copy(quality = if (next == Quality.AUTO) it.quality - cam.channel else it.quality + (cam.channel to next))
                    })
                }
            }
            MenuRow("Check stream resolution", if (checking) "Checking..." else "Press OK to probe Main and Sub") {
                checkAll()
            }
            val mainText = StreamCache.info[mainUri]?.summary()?.ifEmpty { null }
                ?: StreamCache.errors[mainUri]
                ?: "Not checked yet"
            MenuRow("Main stream (Large)", mainText) { checkAll() }
            val subText = if (sameStream) "Same as main stream"
            else StreamCache.info[subUri]?.summary()?.ifEmpty { null }
                ?: StreamCache.errors[subUri]
                ?: "Not checked yet"
            MenuRow("Sub stream (Small)", subText) { checkAll() }
            MenuRow("Main address", maskCredentials(mainUri).ifEmpty { "Not set" }) {}
            MenuRow("Sub address", if (sameStream) "Same as main address" else maskCredentials(subUri).ifEmpty { "Not set" }) {}
            onvifProfiles.forEach { p ->
                val prof = p.profile
                val role = when (p.uri) {
                    cam.source.mainUri -> "Large stream"
                    cam.source.subUri -> "Small stream"
                    else -> "OK to use as Large"
                }
                val desc = listOfNotNull(
                    "${prof.width}x${prof.height}".takeIf { prof.width > 0 && prof.height > 0 },
                    prof.encoding.takeIf { it.isNotEmpty() },
                    "${prof.fps}fps".takeIf { prof.fps > 0 },
                    "PTZ".takeIf { prof.ptz },
                    role
                ).joinToString("   |   ")
                MenuRow("Profile: ${prof.name.ifEmpty { prof.token }}", desc) {
                    clearSmallOnly(cam.id)
                    app.store.update { c ->
                        c.copy(sources = c.sources.map { if (it.id == cam.source.id) it.copy(mainUri = p.uri) else it })
                    }
                    status = "Large stream set to ${prof.name.ifEmpty { prof.token }}"
                }
            }
            val features = listOfNotNull(
                "Channel ${cam.channel} of ${cam.source.channelCount}".takeIf { cam.source.brand.isNvr },
                "PTZ: ${if (cam.hasPtz) "Yes" else "No"}",
                "Playback: ${if (cam.playbackUri(LocalDateTime.now()) != null) "Yes" else "No"}"
            ).joinToString("   |   ")
            MenuRow("Camera features", features) {}
            MenuRow("Edit source", "Change IP, login or stream address") {
                screen = Screen.Edit(cam.source, back = Screen.CameraDetails(cam.id, back))
            }
        }
    }

    @Composable
    private fun SettingsScreen(app: HomeCamsApp) {
        val config = app.store.config
        val scope = rememberCoroutineScope()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        fun next(options: List<Int>, current: Int) = options[(options.indexOf(current) + 1) % options.size]

        Page("Settings", tab = Tab.SETTINGS, onTab = ::open) {
            MenuRow("Sources", "${config.sources.size} added", Modifier.focusRequester(first)) { screen = Screen.Sources }
            val picked = config.sources.sumOf { it.quality.count { (_, q) -> q != Quality.AUTO } }
            MenuRow("Camera resolution", if (picked == 0) "All on Auto" else "$picked set by hand") { screen = Screen.Resolution }
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
