package com.bello.assistant.tools

/**
 * Fills the page template (FR-PAGE-03). The template is an asset (`assets/page/page.html`),
 * replaceable by a file pushed to the device; this fallback keeps a page readable without it.
 * Pure, unit tested.
 */
object PageHtml {

    const val MINIMAL = "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<title>{{title}}</title></head><body><main>{{body}}</main>" +
        "<footer>Servi par Bello · {{meta}}</footer></body></html>"

    /**
     * The body goes in last, so nothing a model wrote can be mistaken for a placeholder. The picture,
     * when there is one, goes under the title — in any template, a pushed one included.
     */
    fun render(template: String, title: String, bodyHtml: String, meta: String, heroHtml: String = ""): String = template
        .replace("{{title}}", MarkdownLite.escape(title))
        .replace("{{meta}}", MarkdownLite.escape(meta))
        .replace("{{body}}", withHero(bodyHtml, heroHtml))

    /** The page's picture (FR-PAGE-07); its size is given so the text does not jump when it arrives. */
    fun hero(src: String, alt: String, width: Int, height: Int): String =
        "<img class=\"hero\" src=\"${MarkdownLite.escape(src)}\" alt=\"${MarkdownLite.escape(alt)}\" " +
            "width=\"$width\" height=\"$height\">\n"

    private fun withHero(body: String, hero: String): String {
        if (hero.isEmpty()) return body
        val end = body.indexOf("</h1>")
        if (end < 0) return hero + body
        val after = body.indexOf('\n', end).let { if (it < 0) end + "</h1>".length else it + 1 }
        return body.substring(0, after) + hero + body.substring(after)
    }

    /** The picture of the demo page, written the way the page's author is told to (docs/page-images.md). */
    const val SAMPLE_IMAGE_PROMPT = "Editorial food photograph of a stack of thin golden French crêpes on a " +
        "white ceramic plate, dusted with sugar, a lemon half and a small jar of apricot jam beside it. " +
        "Shot from a 45-degree angle on a weathered oak table with a crumpled linen napkin at the edge. " +
        "Soft natural window light from the left, shallow depth of field, warm cosy tones."

    /** What `scripts/page.sh demo` publishes: the whole path without a provider. */
    val SAMPLE_MARKDOWN = """
        # Crêpes
        Pour 4 personnes · préparation 10 minutes · repos 30 minutes · cuisson 20 minutes · facile

        ## Ingrédients
        - 250 g de farine
        - 3 œufs
        - 50 cl de lait
        - 1 cuillère à soupe de sucre
        - 1 pincée de sel
        - 30 g de beurre fondu
        - 1 cuillère à soupe de rhum ou de fleur d'oranger (facultatif)

        ## Préparation
        1. Mélanger la farine, le sucre et le sel dans un saladier, faire un puits.
        2. Casser les œufs au centre et mélanger au fouet en incorporant la farine petit à petit.
        3. Verser le lait progressivement pour obtenir une pâte lisse, puis le beurre fondu et le parfum.
        4. Laisser reposer **30 minutes** à température ambiante.
        5. Chauffer une poêle légèrement beurrée, verser une petite louche de pâte et l'étaler.
        6. Cuire 1 à 2 minutes par face, jusqu'à ce que les bords se décollent.

        ## Conseils
        - Une pâte trop épaisse s'allonge avec un peu de lait ou d'eau.
        - Les crêpes se gardent 24 heures au frais, empilées sous un torchon.
        - Sucre, confiture, citron ou chocolat fondu : chacun sa crêpe.
    """.trimIndent()
}
