package com.home.cams.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    @Test
    fun isNewer_comparesNumbersNotText() {
        assertTrue(Releases.isNewer("0.1.0", "v0.2.0"))
        assertTrue(Releases.isNewer("0.9.0", "0.10.0"))
        assertTrue(Releases.isNewer("0.1", "0.1.1"))
        assertFalse(Releases.isNewer("0.2.0", "v0.2.0"))
        assertFalse(Releases.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun parseLatest_needsANewerTagAndAnApk() {
        val json = """{"tag_name":"v0.2.0","assets":[
            {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
            {"name":"home-cams-0.2.0.apk","browser_download_url":"https://x/a.apk","size":1234}]}"""
        assertEquals(Update("0.2.0", "https://x/a.apk", 1234), Releases.parseLatest(json, "0.1.0"))
        assertNull(Releases.parseLatest(json, "0.2.0"))
        assertNull(Releases.parseLatest("""{"tag_name":"v0.3.0","assets":[]}""", "0.1.0"))
    }
}
