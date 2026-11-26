package com.home.cams.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun rtpPacket(pt: Int, payload: ByteArray): ByteArray {
        val hdr = byteArrayOf(0x80.toByte(), pt.toByte(), 0, 1, 0, 0, 0, 1, 0, 0, 0, 1)
        return hdr + payload
    }

    private fun interleaved(ch: Int, pkt: ByteArray): ByteArray =
        byteArrayOf('$'.code.toByte(), ch.toByte(), (pkt.size shr 8).toByte(), (pkt.size and 0xFF).toByte()) + pkt

    @Test
    fun dropsUnsupportedH265NalTypesAndShortFuPackets() {
        val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\n"
        val describeOk = (
            "RTSP/1.0 200 OK\r\n" +
                "Content-Length: ${sdp.length}\r\n" +
                "\r\n" +
                sdp
            ).toByteArray(Charsets.ISO_8859_1)

        val validVps = interleaved(0, rtpPacket(96, byteArrayOf((32 shl 1).toByte(), 1, 0x11, 0x22)))
        val badNal59 = interleaved(0, rtpPacket(96, byteArrayOf((59 shl 1).toByte(), 1, 0x33)))
        val badShortFu = interleaved(0, rtpPacket(96, byteArrayOf((49 shl 1).toByte(), 1)))
        val validFu = interleaved(0, rtpPacket(96, byteArrayOf((49 shl 1).toByte(), 1, 0x80.toByte(), 0x44)))
        val rtcp = interleaved(1, byteArrayOf(0x80.toByte(), 200.toByte(), 0, 1))

        val stream = RtspHeaderFilterStream(
            ByteArrayInputStream(describeOk + validVps + badNal59 + badShortFu + validFu + rtcp)
        )
        assertArrayEquals(describeOk + validVps + validFu + rtcp, stream.readBytes())
    }

    @Test
    fun shouldDropH265RtpPacket_validatesNalAndExtensionHeaders() {
        val pts = setOf(96)
        assertTrue(shouldDropH265RtpPacket(rtpPacket(96, byteArrayOf((53 shl 1).toByte(), 1)), pts))
        assertTrue(shouldDropH265RtpPacket(rtpPacket(96, byteArrayOf((49 shl 1).toByte(), 1)), pts))
        assertFalse(shouldDropH265RtpPacket(rtpPacket(96, byteArrayOf((49 shl 1).toByte(), 1, 0x80.toByte(), 1)), pts))
        assertFalse(shouldDropH265RtpPacket(rtpPacket(96, byteArrayOf((1 shl 1).toByte(), 1, 2, 3)), pts))
        // Different payload type (e.g. audio) is untouched.
        assertFalse(shouldDropH265RtpPacket(rtpPacket(8, byteArrayOf((59 shl 1).toByte(), 1)), pts))
    }
}
