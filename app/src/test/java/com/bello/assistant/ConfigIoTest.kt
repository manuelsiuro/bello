package com.bello.assistant

import com.bello.assistant.core.ConfigIo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of import/export that do not need a device: what happens to a key that came back
 * masked from an export somebody edited on their Mac.
 */
class ConfigIoTest {

    private fun keyOf(json: JSONObject, id: String): String {
        val providers = json.getJSONArray("providers")
        for (i in 0 until providers.length()) {
            val provider = providers.getJSONObject(i)
            val name = provider.optString("preset").ifEmpty { provider.optString("id") }
            if (name == id) return provider.optString("key").ifEmpty { provider.optString("apiKey") }
        }
        return ""
    }

    @Test fun `every spelling of a key is masked, or the export leaks`() {
        // The config parser accepts both "key" and "apiKey"; an export that masks one and not the
        // other writes real credentials into a file meant to be shared. This happened once.
        val root = JSONObject("""{"providers":[{"preset":"gemini","key":"AQ.secret"},
            {"preset":"groq","apiKey":"gsk_secret"},{"preset":"geminiweb","enabled":true}]}""")
        assertTrue(!ConfigIo.isMasked(root.toString()))
        ConfigIo.mask(root)
        assertTrue(ConfigIo.isMasked(root.toString()))
        assertTrue(!root.toString().contains("AQ.secret"))
        assertTrue(!root.toString().contains("gsk_secret"))
    }

    @Test fun `a masked key keeps the one already on the tablet`() {
        val onDevice = JSONObject("""{"providers":[{"preset":"gemini","key":"real-key"}]}""")
        val edited = JSONObject("""{"providers":[{"preset":"gemini","key":"…","model":"new-model"}]}""")
        ConfigIo::class.java.getDeclaredMethod("keepMaskedKeys", JSONObject::class.java, JSONObject::class.java)
            .apply { isAccessible = true }.invoke(ConfigIo, edited, onDevice)
        assertEquals("real-key", keyOf(edited, "gemini"))
        assertEquals("new-model", edited.getJSONArray("providers").getJSONObject(0).getString("model"))
    }

    @Test fun `a real key in the file replaces the old one`() {
        val onDevice = JSONObject("""{"providers":[{"preset":"groq","key":"old"}]}""")
        val edited = JSONObject("""{"providers":[{"preset":"groq","key":"brand-new"}]}""")
        ConfigIo::class.java.getDeclaredMethod("keepMaskedKeys", JSONObject::class.java, JSONObject::class.java)
            .apply { isAccessible = true }.invoke(ConfigIo, edited, onDevice)
        assertEquals("brand-new", keyOf(edited, "groq"))
    }

    @Test fun `an empty key falls back to the tablet's own`() {
        val onDevice = JSONObject("""{"providers":[{"preset":"groq","key":"old"}]}""")
        val edited = JSONObject("""{"providers":[{"preset":"groq","key":""}]}""")
        ConfigIo::class.java.getDeclaredMethod("keepMaskedKeys", JSONObject::class.java, JSONObject::class.java)
            .apply { isAccessible = true }.invoke(ConfigIo, edited, onDevice)
        assertEquals("old", keyOf(edited, "groq"))
        assertTrue(keyOf(edited, "unknown").isEmpty())
    }
}
