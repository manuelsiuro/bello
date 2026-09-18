package com.bello.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test fun `the television is on by default, at the SFR name of the box`() {
        val config = AppConfig.parse("""{"providers": []}""")
        assertNotNull(config.tvBox)
        val tv = config.tvBox!!
        assertEquals("stb", tv.host)
        assertEquals(7682, tv.port)
        assertEquals(2, tv.channels["France 2"])
        assertEquals(16, tv.channels["franceinfo"])
    }

    @Test fun `the file can move the box, replace the channel table, or turn it off`() {
        val moved = AppConfig.parse("""{"tvBox": {"host": "192.168.1.216", "port": 7684, "okAfterDigits": true,
            "channels": {"TF1": 1, "Canal": 4}}}""").tvBox!!
        assertEquals("192.168.1.216", moved.host)
        assertEquals(7684, moved.port)
        assertTrue(moved.okAfterDigits)
        assertEquals(mapOf("TF1" to 1, "Canal" to 4), moved.channels)

        assertNull(AppConfig.parse("""{"tvBox": {"enabled": false}}""").tvBox)
        // An empty or broken table falls back to the default one rather than to no channel at all.
        assertEquals(AppConfig.TvBoxConfig.DEFAULT_CHANNELS, AppConfig.parse("""{"tvBox": {"channels": {}}}""").tvBox!!.channels)
    }
}
