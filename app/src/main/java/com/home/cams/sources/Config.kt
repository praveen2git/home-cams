package com.home.cams.sources

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Wall layouts, by number of tiles. A low-end TV can only decode a few streams at once, hence the cap. */
object Layouts {
    val ALL = listOf(1, 2, 4, 6, 9)
    fun allowed(cap: Int) = ALL.filter { it <= cap }.ifEmpty { listOf(1) }
    fun columns(layout: Int) = when (layout) { 1 -> 1; 2, 4 -> 2; else -> 3 }
}

data class Config(
    val sources: List<Source> = emptyList(),
    /** Most live tiles on screen at once. */
    val tileCap: Int = 4,
    val layout: Int = 4
) {
    /** The same config with the cap and layout forced into the allowed values. */
    fun fixed(): Config {
        val cap = Layouts.ALL.lastOrNull { it <= tileCap } ?: 1
        return copy(tileCap = cap, layout = Layouts.allowed(cap).lastOrNull { it <= layout } ?: 1)
    }
}

/** Where the selection is on the wall. Arrow keys page through the cameras. */
data class Wall(val count: Int, val layout: Int, val selected: Int = 0) {
    val columns get() = Layouts.columns(layout)
    val pageStart get() = selected / layout * layout
    val visible get() = pageStart until minOf(pageStart + layout, count)
    val onTopRow get() = selected - pageStart < columns
    val page get() = selected / layout + 1
    val pages get() = (count + layout - 1) / layout
    fun move(delta: Int) = if (count == 0) this else copy(selected = (selected + delta).coerceIn(0, count - 1))
}

object ConfigJson {
    fun write(c: Config): String {
        val arr = JSONArray()
        c.sources.forEach { s ->
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("brand", s.brand.name).put("host", s.host)
                    .put("rtspPort", s.rtspPort).put("user", s.user).put("pass", s.pass)
                    .put("channelCount", s.channelCount).put("onvifXaddr", s.onvifXaddr ?: "")
                    .put("mainUri", s.mainUri).put("subUri", s.subUri)
                    .put("ptzXaddr", s.ptzXaddr ?: "").put("ptzToken", s.ptzToken ?: "")
            )
        }
        return JSONObject().put("sources", arr).put("tileCap", c.tileCap).put("layout", c.layout).toString()
    }

    /** A missing or broken file gives the defaults. A source with an unknown brand is skipped. */
    fun read(text: String?): Config {
        if (text.isNullOrBlank()) return Config()
        return runCatching {
            val o = JSONObject(text)
            val arr = o.optJSONArray("sources") ?: JSONArray()
            Config(
                sources = (0 until arr.length()).mapNotNull { i ->
                    val s = arr.getJSONObject(i)
                    val brand = Brand.entries.firstOrNull { it.name == s.optString("brand") } ?: return@mapNotNull null
                    Source(
                        id = s.getString("id"), name = s.optString("name"), brand = brand, host = s.optString("host"),
                        rtspPort = s.optInt("rtspPort", 554), user = s.optString("user"), pass = s.optString("pass"),
                        channelCount = s.optInt("channelCount", 1),
                        onvifXaddr = s.optString("onvifXaddr").ifEmpty { null },
                        mainUri = s.optString("mainUri"), subUri = s.optString("subUri"),
                        ptzXaddr = s.optString("ptzXaddr").ifEmpty { null },
                        ptzToken = s.optString("ptzToken").ifEmpty { null }
                    )
                },
                tileCap = o.optInt("tileCap", 4),
                layout = o.optInt("layout", 4)
            ).fixed()
        }.getOrDefault(Config())
    }
}

/** Sources and settings, kept only on the TV. Use from the main thread. */
class ConfigStore(private val prefs: SharedPreferences) {
    var config by mutableStateOf(ConfigJson.read(prefs.getString(KEY, null)))
        private set

    fun update(change: (Config) -> Config) {
        config = change(config).fixed()
        prefs.edit().putString(KEY, ConfigJson.write(config)).apply()
    }

    private companion object {
        const val KEY = "config"
    }
}
