package com.home.cams.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class RtspHeaderFilterTest {

    @Test
    fun stripsAuthVersionFromWwwAuthenticate() {
        val basic = "WWW-Authenticate: Basic realm=\"RTSPD\", AuthVersion=\"1.1\"\r\n"
        assertEquals("WWW-Authenticate: Basic realm=\"RTSPD\"\r\n", cleanRtspHeaderLine(basic))

        val digest = "WWW-Authenticate: Digest realm=\"RTSP SERVER\", nonce=\"abc\", AuthVersion=\"1.1\"\r\n"
        assertEquals("WWW-Authenticate: Digest realm=\"RTSP SERVER\", nonce=\"abc\"\r\n", cleanRtspHeaderLine(digest))

        val normal = "WWW-Authenticate: Basic realm=\"cam\"\r\n"
        assertEquals(normal, cleanRtspHeaderLine(normal))
    }

    @Test
    fun preservesSdpBodyAndInterleavedBinaryFrames() {
        val response = (
            "RTSP/1.0 401 Unauthorized\r\n" +
                "CSeq: 1\r\n" +
                "WWW-Authenticate: Basic realm=\"RTSPD\", AuthVersion=\"1.1\"\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        val sdp = "v=0\r\ns=WWW-Authenticate: Basic realm=\"X\", AuthVersion=\"1.1\"\r\n"
        val describeOk = (
            "RTSP/1.0 200 OK\r\n" +
                "Content-Length: ${sdp.length}\r\n" +
                "\r\n" +
                sdp
            ).toByteArray(Charsets.ISO_8859_1)
        val payload = "WWW-Authenticate: Basic realm=\"Y\", AuthVersion=\"1.1\"".toByteArray(Charsets.ISO_8859_1)
        val rtpFrame = byteArrayOf('$'.code.toByte(), 0, (payload.size shr 8).toByte(), (payload.size and 0xFF).toByte()) + payload

        val stream = RtspHeaderFilterStream(ByteArrayInputStream(response + describeOk + rtpFrame))
        val out = stream.readBytes()
        val expectedResponse = (
            "RTSP/1.0 401 Unauthorized\r\n" +
                "CSeq: 1\r\n" +
                "WWW-Authenticate: Basic realm=\"RTSPD\"\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(expectedResponse + describeOk + rtpFrame, out)
    }
}
