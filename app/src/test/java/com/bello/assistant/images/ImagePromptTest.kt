package com.bello.assistant.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePromptTest {

    private val page = """
        # Ratatouille
        Pour 4 personnes · préparation 20 minutes · cuisson 45 minutes

        ## Ingrédients
        - 2 courgettes

        [image: Editorial food photograph of a rustic ratatouille in a cast-iron skillet, soft window light.]
    """.trimIndent()

    @Test fun `the image line leaves the page and becomes the prompt`() {
        val split = ImagePrompt.split(page)
        assertFalse(split.markdown.contains("[image"))
        assertTrue(split.markdown.endsWith("- 2 courgettes"))
        assertEquals(
            "Editorial food photograph of a rustic ratatouille in a cast-iron skillet, soft window light. " +
                ImagePrompt.TAIL,
            split.prompt,
        )
    }

    @Test fun `the tag is found however the model spells it`() {
        listOf(
            "[Image : A watercolor of a moon over a quiet bedroom]",
            "- [illustration: A watercolor of a moon over a quiet bedroom]",
            "  [picture:A watercolor of a moon over a quiet bedroom]  ",
        ).forEach { line ->
            val split = ImagePrompt.split("# Dormir\n$line")
            assertEquals(line, "# Dormir", split.markdown)
            assertTrue(line, split.prompt!!.startsWith("A watercolor of a moon over a quiet bedroom."))
        }
    }

    @Test fun `a page without the line has no prompt and is left alone`() {
        val split = ImagePrompt.split("# Crêpes\n- farine\n[détails]")
        assertEquals("# Crêpes\n- farine\n[détails]", split.markdown)
        assertNull(split.prompt)
    }

    @Test fun `a prompt is flattened, unquoted, capped and ends with the wordless tail`() {
        assertEquals("A calm lake at dawn. ${ImagePrompt.TAIL}", ImagePrompt.clean("  « A calm\n lake at dawn »  "))
        val long = ImagePrompt.clean("word ".repeat(300))!!
        assertTrue(long.length <= ImagePrompt.MAX_CHARS + ImagePrompt.TAIL.length + 2)
        assertTrue(long.endsWith(ImagePrompt.TAIL))
        assertNull(ImagePrompt.clean("   "))
        assertNull(ImagePrompt.clean("\"a cat\""))
    }

    @Test fun `without the line, the title gives a calm illustration`() {
        val prompt = ImagePrompt.fallback("Monter une étagère")!!
        assertTrue(prompt.contains("\"Monter une étagère\""))
        assertTrue(prompt.startsWith("Soft flat vector illustration"))
        assertNull(ImagePrompt.fallback(null))
        assertNull(ImagePrompt.fallback(" "))
    }
}
