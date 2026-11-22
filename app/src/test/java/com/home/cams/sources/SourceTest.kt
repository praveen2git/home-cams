package com.home.cams.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SourceTest {
    private val nvr = Source("n1", "NVR", Brand.HIKVISION, "192.168.1.200", user = "admin", pass = "p@ss:1/x", channelCount = 3)
    private val at = LocalDateTime.of(2026, 11, 12, 9, 5, 0)

    @Test
    fun hikvision_liveAndPlayback() {
        assertEquals("rtsp://admin:p%40ss%3A1%2Fx@192.168.1.200:554/Streaming/Channels/201", Brand.HIKVISION.liveUri(nvr, 2, sub = false))
        assertEquals("rtsp://admin:p%40ss%3A1%2Fx@192.168.1.200:554/Streaming/Channels/1202", Brand.HIKVISION.liveUri(nvr, 12, sub = true))
        assertEquals(
            "rtsp://admin:p%40ss%3A1%2Fx@192.168.1.200:554/Streaming/tracks/201?starttime=20261112T090500Z",
            Brand.HIKVISION.playbackUri(nvr, 2, at)
        )
    }

    @Test
    fun dahua_liveAndPlayback() {
        val s = nvr.copy(brand = Brand.DAHUA, rtspPort = 8554, pass = "pw")
        assertEquals("rtsp://admin:pw@192.168.1.200:8554/cam/realmonitor?channel=2&subtype=0", Brand.DAHUA.liveUri(s, 2, sub = false))
        assertEquals("rtsp://admin:pw@192.168.1.200:8554/cam/realmonitor?channel=2&subtype=1", Brand.DAHUA.liveUri(s, 2, sub = true))
        assertEquals(
            "rtsp://admin:pw@192.168.1.200:8554/cam/playback?channel=2&subtype=0&starttime=2026_11_12_09_05_00",
            Brand.DAHUA.playbackUri(s, 2, at)
        )
    }

    @Test
    fun tvt_liveOnly() {
        val s = nvr.copy(brand = Brand.TVT, pass = "pw")
        assertEquals("rtsp://admin:pw@192.168.1.200:554/chID=1&streamType=main", Brand.TVT.liveUri(s, 1, sub = false))
        assertEquals("rtsp://admin:pw@192.168.1.200:554/chID=1&streamType=sub", Brand.TVT.liveUri(s, 1, sub = true))
        assertNull(Brand.TVT.playbackUri(s, 1, at))
    }

    @Test
    fun onvifAndRtsp_useStoredAddresses() {
        val cam = Source("c1", "Door", Brand.ONVIF, "192.168.1.201", user = "admin", pass = "pw", mainUri = "rtsp://192.168.1.201:554/main", subUri = "rtsp://192.168.1.201:554/sub")
        assertEquals("rtsp://admin:pw@192.168.1.201:554/main", Brand.ONVIF.liveUri(cam, 1, sub = false))
        assertEquals("rtsp://admin:pw@192.168.1.201:554/sub", Brand.ONVIF.liveUri(cam, 1, sub = true))
        assertNull(Brand.ONVIF.playbackUri(cam, 1, at))
        // No login yet: the address is empty and the tile says so.
        assertEquals("", Brand.ONVIF.liveUri(cam.copy(mainUri = "", subUri = ""), 1, sub = true))

        val url = Source("u1", "Gate", Brand.RTSP, "h", mainUri = "rtsp://me:secret@h/live", subUri = "rtsp://me:secret@h/live")
        assertEquals("rtsp://me:secret@h/live", Brand.RTSP.liveUri(url, 1, sub = true))
    }

    @Test
    fun withCredentials_encodesAndReplaces() {
        assertEquals("rtsp://admin:p%40ss%3A1@h:554/x", withCredentials("rtsp://old:x@h:554/x", "admin", "p@ss:1"))
        assertEquals("rtsp://admin:pw@h/a@b", withCredentials("rtsp://h/a@b", "admin", "pw"))
        assertEquals("rtsp://h/x", withCredentials("rtsp://h/x", null, null))
        assertEquals("192.168.1.9", hostOf("rtsp://a:b@192.168.1.9:554/x"))
        assertEquals("192.168.1.9", hostOf("http://192.168.1.9/onvif/device_service"))
    }

    @Test
    fun cams_oneTilePerNvrChannel() {
        val door = Source("c1", "Door", Brand.ONVIF, "h", ptzXaddr = "http://h/ptz", ptzToken = "P1")
        val cams = listOf(nvr, door).cams()
        assertEquals(listOf("NVR 1", "NVR 2", "NVR 3", "Door"), cams.map { it.name })
        assertEquals(listOf("n1#1", "n1#2", "n1#3", "c1#1"), cams.map { it.id })
        assertFalse(cams[0].hasPtz)
        assertTrue(cams[3].hasPtz)
    }

    @Test
    fun config_roundTripAndBadInput() {
        val config = Config(
            listOf(nvr, Source("c1", "Door", Brand.ONVIF, "h", onvifXaddr = "http://h/onvif", mainUri = "rtsp://h/m", subUri = "rtsp://h/s", ptzXaddr = "http://h/ptz", ptzToken = "P1")),
            tileCap = 9, layout = 6
        )
        assertEquals(config, ConfigJson.read(ConfigJson.write(config)))
        assertEquals(Config(), ConfigJson.read(null))
        assertEquals(Config(), ConfigJson.read("not json"))
        // A brand from a newer version is skipped, the rest still loads.
        val odd = ConfigJson.write(config).replace("\"HIKVISION\"", "\"FUTURE\"")
        assertEquals(listOf("c1"), ConfigJson.read(odd).sources.map { it.id })
    }

    @Test
    fun tileCapLimitsTheLayouts() {
        assertEquals(listOf(1, 4), Layouts.allowed(4))
        assertEquals(listOf(1, 4, 6, 9), Layouts.allowed(9))
        assertEquals(Config(tileCap = 4, layout = 4), Config(tileCap = 4, layout = 9).fixed())
        assertEquals(Config(tileCap = 6, layout = 6), Config(tileCap = 7, layout = 6).fixed())
        assertEquals(Config(tileCap = 1, layout = 1), Config(tileCap = 0, layout = 0).fixed())
        assertEquals(listOf(1, 2, 3, 3), Layouts.ALL.map { Layouts.columns(it) })
    }

    @Test
    fun wall_arrowsPageThroughCameras() {
        var wall = Wall(count = 10, layout = 4)
        assertEquals(0..3, wall.visible)
        assertTrue(wall.onTopRow)
        wall = wall.move(2) // down one row
        assertFalse(wall.onTopRow)
        wall = wall.move(2) // down again: next page
        assertEquals(4..7, wall.visible)
        assertEquals(2, wall.page)
        assertEquals(3, wall.pages)
        wall = wall.move(100) // clamps to the last camera, on a short page
        assertEquals(9, wall.selected)
        assertEquals(8..9, wall.visible)
        assertEquals(0, wall.move(-100).selected)
        assertEquals(Wall(0, 4), Wall(0, 4).move(1))
    }
}
