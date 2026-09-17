package com.bello.assistant

import com.bello.assistant.ui.FaceScript
import com.bello.assistant.ui.FaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceScriptTest {
    @Test
    fun buildsCallWithQuotedArguments() {
        assertEquals("bello.setState(\"thinking\");", FaceScript.call("setState", "thinking"))
        assertEquals("bello.clearSubtitles();", FaceScript.call("clearSubtitles"))
    }

    @Test
    fun escapesCharactersThatBreakJavascript() {
        val quoted = FaceScript.quote("l'eau \"froide\"\n\\  </script>")
        assertEquals("\"l'eau \\\"froide\\\"\\n\\\\ \\u2028\\u003c/script>\"", quoted)
    }

    @Test
    fun keepsFrenchTextAndEmoji() {
        assertEquals("\"Très bien ! « oui » 🍌\"", FaceScript.quote("Très bien ! « oui » 🍌"))
    }

    @Test
    fun mapsStateNames() {
        assertEquals(FaceState.SLEEPY, FaceState.fromJs("sleepy"))
        assertNull(FaceState.fromJs("dancing"))
    }
}
