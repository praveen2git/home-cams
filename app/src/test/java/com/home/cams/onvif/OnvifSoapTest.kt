package com.home.cams.onvif

import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnvifSoapTest {

    @Test
    fun passwordDigest_matchesTheOnvifGuideVector() {
        val nonce = "LKqI6G/AikKCQrN0zqZFlg==".decodeBase64()!!.toByteArray()
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", OnvifSoap.passwordDigest(nonce, "2010-09-16T07:50:45Z", "userpassword"))
    }

    @Test
    fun envelope_withAndWithoutLogin() {
        val withLogin = OnvifSoap.envelope(OnvifSoap.GET_PROFILES, "admin", "pw", ByteArray(16), 0)
        assertTrue(withLogin.contains("<Username>admin</Username>"))
        assertTrue(withLogin.contains("<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">1970-01-01T00:00:00Z</Created>"))
        val noLogin = OnvifSoap.envelope(OnvifSoap.GET_SYSTEM_TIME, null, null, ByteArray(16), 0)
        assertFalse(noLogin.contains("Security"))
        assertTrue(OnvifSoap.probe("abc").contains("dn:NetworkVideoTransmitter"))
    }

    @Test
    fun parsesDiscoveryTimeServicesProfilesAndUri() {
        assertEquals(
            "http://192.168.1.201/onvif/device_service",
            OnvifSoap.parseXAddrs("<d:XAddrs>http://[fe80::1]/onvif/device_service http://192.168.1.201/onvif/device_service</d:XAddrs>")
        )

        val time = """
            <tds:SystemDateAndTime><tt:UTCDateTime>
              <tt:Time><tt:Hour>4</tt:Hour><tt:Minute>0</tt:Minute><tt:Second>5</tt:Second></tt:Time>
              <tt:Date><tt:Year>2026</tt:Year><tt:Month>11</tt:Month><tt:Day>12</tt:Day></tt:Date>
            </tt:UTCDateTime></tds:SystemDateAndTime>
        """
        assertEquals(1_794_456_005_000L, OnvifSoap.parseSystemTime(time))
        assertNull(OnvifSoap.parseSystemTime("<nothing/>"))

        val caps = "<tt:Device><tt:XAddr>http://x/device</tt:XAddr></tt:Device>" +
            "<tt:Media><tt:XAddr>http://192.168.1.201/onvif/Media</tt:XAddr></tt:Media>" +
            "<tt:PTZ><tt:XAddr>http://192.168.1.201/onvif/PTZ</tt:XAddr></tt:PTZ>"
        assertEquals("http://192.168.1.201/onvif/Media", OnvifSoap.parseServiceXAddr(caps, "Media"))
        assertEquals("http://192.168.1.201/onvif/PTZ", OnvifSoap.parseServiceXAddr(caps, "PTZ"))
        assertNull(OnvifSoap.parseServiceXAddr("<tt:Media><tt:XAddr>http://x</tt:XAddr></tt:Media>", "PTZ"))

        val profiles = """
            <trt:Profiles token="Profile_1" fixed="true"><tt:Name>main</tt:Name>
              <tt:Resolution><tt:Width>2560</tt:Width><tt:Height>1440</tt:Height></tt:Resolution>
              <tt:PTZConfiguration token="ptz0"><tt:NodeToken>n0</tt:NodeToken></tt:PTZConfiguration></trt:Profiles>
            <trt:Profiles token="Profile_2" fixed="true"><tt:Name>sub</tt:Name>
              <tt:Resolution><tt:Width>640</tt:Width><tt:Height>360</tt:Height></tt:Resolution></trt:Profiles>
        """
        assertEquals(
            listOf(OnvifProfile("Profile_1", 2560, 1440, ptz = true), OnvifProfile("Profile_2", 640, 360)),
            OnvifSoap.parseProfiles(profiles)
        )

        assertEquals(
            "rtsp://192.168.1.201:554/ch01?a=1&b=2",
            OnvifSoap.parseStreamUri("<trt:MediaUri><tt:Uri>rtsp://192.168.1.201:554/ch01?a=1&amp;b=2</tt:Uri></trt:MediaUri>")
        )
        assertEquals("Sender not Authorized", OnvifSoap.parseFault("<env:Reason><env:Text xml:lang=\"en\">Sender not Authorized</env:Text></env:Reason>"))
    }

    @Test
    fun ptz_moveSendsOnlyThePartThatMoves() {
        val pan = OnvifSoap.continuousMove("P<1", pan = 0.5f, tilt = 0f, zoom = 0f)
        assertEquals(
            """<ContinuousMove xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>P&lt;1</ProfileToken>""" +
                """<Velocity><PanTilt xmlns="http://www.onvif.org/ver10/schema" x="0.5" y="0.0"/></Velocity></ContinuousMove>""",
            pan
        )
        val zoom = OnvifSoap.continuousMove("P1", pan = 0f, tilt = 0f, zoom = -0.5f)
        assertTrue(zoom.contains("""<Zoom xmlns="http://www.onvif.org/ver10/schema" x="-0.5"/>"""))
        assertFalse(zoom.contains("PanTilt"))
        assertEquals(
            """<Stop xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>P1</ProfileToken><PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>""",
            OnvifSoap.ptzStop("P1")
        )
    }

    @Test
    fun discovery_sortsByIpNumber() {
        assertTrue(CameraDiscovery.ipOrder("http://192.168.1.9/x") < CameraDiscovery.ipOrder("http://192.168.1.10/x"))
        assertEquals(Long.MAX_VALUE, CameraDiscovery.ipOrder("http://camera.local/x"))
    }
}
