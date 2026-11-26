package com.home.cams.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.home.cams.onvif.CameraDiscovery
import com.home.cams.onvif.OnvifClient
import com.home.cams.sources.Brand
import com.home.cams.sources.ConfigStore
import com.home.cams.sources.Source
import com.home.cams.sources.hostOf
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

private const val MAX_CHANNELS = 64

/**
 * The start of this TV's own address, such as "192.168.1.". Cameras are nearly always on the same
 * network, so the viewer types only the last number.
 * shortcut: assumes the usual home network where only the last number differs, the text stays editable.
 */
private fun lanPrefix(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence().flatMap { it.inetAddresses.asSequence() }
        .first { it is Inet4Address && it.isSiteLocalAddress }.hostAddress!!.substringBeforeLast('.') + "."
}.getOrDefault("")

/** Cameras from an ONVIF search that are not in [known] yet, named by the last part of their IP. */
private suspend fun newOnvifSources(found: List<String>, known: List<Source>, onvif: OnvifClient): List<Source> =
    found.filter { xaddr -> known.none { it.host.substringBefore(':') == hostOf(xaddr) } }.map { xaddr ->
        val host = hostOf(xaddr)
        // Most cameras want a login first. Then the addresses stay empty until the user enters it.
        val info = runCatching { onvif.describe(xaddr, null, null) }.getOrNull()
        Source(
            UUID.randomUUID().toString(), "Camera ${host.substringAfterLast('.')}", Brand.ONVIF, host,
            onvifXaddr = xaddr, mainUri = info?.mainUri.orEmpty(), subUri = info?.subUri.orEmpty(),
            ptzXaddr = info?.ptzXaddr, ptzToken = info?.ptzToken
        )
    }

@Composable
fun SourcesScreen(store: ConfigStore, onvif: OnvifClient, onEdit: (Source?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    Page("Sources", status ?: "Logins stay on this TV.") {
        MenuRow("Add an NVR or a camera", "Press OK", Modifier.focusRequester(first)) { onEdit(null) }
        MenuRow("Find ONVIF cameras", if (searching) "Searching..." else "Press OK") {
            if (searching) return@MenuRow
            searching = true
            scope.launch {
                status = runCatching {
                    val found = CameraDiscovery.probe(context)
                    val added = newOnvifSources(found, store.config.sources, onvif)
                    store.update { it.copy(sources = it.sources + added) }
                    when {
                        found.isEmpty() -> "No camera answered. Add it by IP address instead."
                        added.isEmpty() -> "Found ${found.size}. All are already in the list."
                        else -> "Found ${found.size}, added ${added.size}. Open one and enter its login. The others get the same login."
                    }
                }.getOrElse { "The search failed: ${it.message}" }
                searching = false
            }
        }
        store.config.sources.forEach { s ->
            val detail = if (s.brand.isNvr) "${s.host}, ${s.channelCount} channels" else s.host
            MenuRow(s.name, "${s.brand.label}   $detail") { onEdit(s) }
        }
    }
}

/** Add or edit one source. [initial] is null for a new one. */
@Composable
fun EditSourceScreen(initial: Source?, onvif: OnvifClient, onSave: (Source) -> Unit, onDelete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var brand by remember { mutableStateOf(initial?.brand ?: Brand.TVT) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.rtspPort ?: 554).toString()) }
    var user by remember { mutableStateOf(initial?.user.orEmpty()) }
    var pass by remember { mutableStateOf(initial?.pass.orEmpty()) }
    var channels by remember { mutableStateOf((initial?.channelCount?.takeIf { initial.brand.isNvr } ?: 8).toString()) }
    var url by remember { mutableStateOf(initial?.mainUri?.takeIf { initial.brand == Brand.RTSP }.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val lan = remember { lanPrefix() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    fun save() {
        val base = Source(
            id = initial?.id ?: UUID.randomUUID().toString(),
            // An NVR named "Cam" shows its channels as "Cam 1", "Cam 2", ...
            name = name.ifBlank { if (brand.isNvr) "Cam" else "Camera" },
            brand = brand, host = host, user = user, pass = pass, quality = initial?.quality.orEmpty()
        )
        when {
            brand == Brand.RTSP -> {
                if (!Regex("^(rtsp|https?)://.+", RegexOption.IGNORE_CASE).matches(url)) {
                    status = "Enter an address that starts with rtsp://"
                    return
                }
                onSave(base.copy(host = hostOf(url), mainUri = url, subUri = url))
            }
            host.isBlank() -> status = "Enter the IP address."
            brand.isNvr -> {
                val p = port.toIntOrNull()?.takeIf { it in 1..65535 }
                val n = channels.toIntOrNull()?.takeIf { it in 1..MAX_CHANNELS }
                when {
                    p == null -> status = "The RTSP port must be a number from 1 to 65535."
                    n == null -> status = "Channels must be a number from 1 to $MAX_CHANNELS."
                    else -> onSave(base.copy(rtspPort = p, channelCount = n))
                }
            }
            else -> {
                // ONVIF: ask the camera for its stream addresses with this login. Saving waits for the answer.
                val xaddr = initial?.onvifXaddr?.takeIf { hostOf(it) == host } ?: "http://$host/onvif/device_service"
                busy = true
                status = "Asking the camera..."
                scope.launch {
                    runCatching { onvif.describe(xaddr, user.ifEmpty { null }, pass) }
                        .onSuccess {
                            onSave(base.copy(onvifXaddr = xaddr, mainUri = it.mainUri, subUri = it.subUri, ptzXaddr = it.ptzXaddr, ptzToken = it.ptzToken))
                        }
                        .onFailure {
                            status = it.message
                            busy = false
                        }
                }
            }
        }
    }

    val hint = "NVR types are untested. No video on the wall? Try the next type.".takeIf { brand.isNvr }
    Page(if (initial == null) "Add a source" else "Edit ${initial.name}", status ?: hint) {
        MenuRow("Type", brand.label, Modifier.focusRequester(first)) {
            brand = Brand.entries[(brand.ordinal + 1) % Brand.entries.size]
        }
        TextRow("Name", name) { name = it }
        if (brand == Brand.RTSP) {
            // A whole example in the exact format: changing a few characters beats typing it all.
            TextRow(
                "RTSP address", url, keyboard = KeyboardType.Uri,
                start = "rtsp://${lan.ifEmpty { "192.168.1." }}200:554/chID=1&streamType=main"
            ) { url = it }
        } else {
            TextRow(if (brand == Brand.ONVIF) "IP address (add :port if not 80)" else "IP address", host, keyboard = KeyboardType.Uri, start = lan) { host = it }
            if (brand.isNvr) TextRow("RTSP port", port, keyboard = KeyboardType.Number) { port = it }
        }
        // The login is put into an RTSP address when it plays, so it is never typed inside the address.
        TextRow("Username", user) { user = it }
        TextRow("Password", pass, password = true) { pass = it }
        if (brand.isNvr) TextRow("Channels", channels, keyboard = KeyboardType.Number) { channels = it }
        MenuRow("Save", if (busy) "Please wait..." else "Press OK") { if (!busy) save() }
        if (initial != null) {
            MenuRow("Delete", if (confirmDelete) "Press OK again to delete" else "Press OK") {
                if (confirmDelete) onDelete() else confirmDelete = true
            }
        }
    }
}
