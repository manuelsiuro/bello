package com.bello.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownLiteTest {

    @Test fun `headings, bullets and numbered steps become their elements`() {
        val html = MarkdownLite.toHtml("# Crêpes\n## Ingrédients\n- farine\n- œufs\n## Préparation\n1. Mélanger.\n2. Cuire.\n")
        assertEquals(
            "<h1>Crêpes</h1>\n<h2>Ingrédients</h2>\n<ul>\n<li>farine</li>\n<li>œufs</li>\n</ul>\n" +
                "<h2>Préparation</h2>\n<ol>\n<li>Mélanger.</li>\n<li>Cuire.</li>\n</ol>\n",
            html,
        )
    }

    @Test fun `a list is closed before the next block, and a paragraph joins its lines`() {
        val html = MarkdownLite.toHtml("- un\n- deux\nUne phrase\nqui continue.\n\n1) premier\n")
        assertEquals("<ul>\n<li>un</li>\n<li>deux</li>\n</ul>\n<p>Une phrase qui continue.</p>\n<ol>\n<li>premier</li>\n</ol>\n", html)
    }

    @Test fun `bold and italic survive, everything else is escaped`() {
        assertEquals("<p>Laisser reposer <strong>30 minutes</strong> et <em>surtout</em> ne pas <em>remuer</em>.</p>\n",
            MarkdownLite.toHtml("Laisser reposer **30 minutes** et *surtout* ne pas _remuer_."))
        val html = MarkdownLite.toHtml("<script>alert(1)</script> & \"co\" 2 * 3 * 4")
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;co&quot; 2 * 3 * 4</p>\n", html)
    }

    @Test fun `code fences and a lone emotion tag are dropped, a cut is announced`() {
        val html = MarkdownLite.toHtml("```markdown\n[happy]\n# Titre\n```\n", truncated = true)
        assertEquals("<h1>Titre</h1>\n" + MarkdownLite.CUT_NOTE + "\n", html)
        assertFalse(MarkdownLite.toHtml("# Titre").contains("coupée"))
    }

    @Test fun `the title is the first heading, plain, or nothing`() {
        assertEquals("Tarte tatin", MarkdownLite.title("[happy]\n# **Tarte** _tatin_ #\n## Ingrédients"))
        assertNull(MarkdownLite.title("## Ingrédients\n- farine"))
        assertNull(MarkdownLite.title(""))
    }

    @Test fun `the sample page renders with balanced tags`() {
        val html = MarkdownLite.toHtml(PageHtml.SAMPLE_MARKDOWN)
        for (tag in listOf("ul", "ol", "li", "h1", "h2", "p", "strong")) {
            assertEquals(tag, Regex("<$tag>").findAll(html).count(), Regex("</$tag>").findAll(html).count())
        }
        assertEquals("Crêpes", MarkdownLite.title(PageHtml.SAMPLE_MARKDOWN))
        assertEquals(6, Regex("<li>\\d?").findAll(html.substringAfter("<ol>").substringBefore("</ol>")).count())
    }

    @Test fun `the template is filled and the title escaped, the body last`() {
        val html = PageHtml.render(PageHtml.MINIMAL, "Sel & poivre", "<p>{{title}}</p>", "18 septembre")
        assertTrue(html.contains("<title>Sel &amp; poivre</title>"))
        assertTrue(html.contains("<main><p>{{title}}</p></main>"))
        assertTrue(html.contains("Servi par Bello · 18 septembre"))
    }

    @Test fun `the shipped template has the three placeholders and nothing external`() {
        val asset = File("src/main/assets/page/page.html").readText()
        listOf("{{title}}", "{{body}}", "{{meta}}").forEach { assertTrue(it, asset.contains(it)) }
        assertFalse(asset.contains("<script"))
        assertFalse(asset.contains("http"))
    }
}
