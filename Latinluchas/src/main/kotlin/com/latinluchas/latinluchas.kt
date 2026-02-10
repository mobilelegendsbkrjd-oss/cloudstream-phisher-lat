package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LatinLuchas : MainAPI() {

    override var mainUrl = "https://tv.latinluchas.com/tv"
    override var name = "TV LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        if (page > 1) return newHomePageResponse(emptyList())

        val document = app.get(mainUrl).document

        val items = document
            .select("article, .elementor-post, .post, .replay-show, a[href*='/tv/coli']")
            .mapNotNull { element ->

                val title: String
                val href: String
                val poster: String

                if (element.hasClass("replay-show")) {
                    title = element.selectFirst("h3")?.text()?.trim() ?: "Repetición"

                    poster = element.selectFirst("img")?.attr("abs:data-src")
                        ?.ifBlank { element.selectFirst("img")?.attr("abs:src") }
                        ?: defaultPoster

                    href = element.select("a.watch-button")
                        .firstOrNull { it.attr("href").contains("/tv/") }
                        ?.attr("abs:href")
                        ?: return@mapNotNull null
                } else {
                    val linkElement = if (element.tagName() == "a") element else element.selectFirst("a")
                    href = linkElement?.attr("abs:href") ?: return@mapNotNull null

                    if (!href.contains("/tv/")) return@mapNotNull null

                    title = element.selectFirst("h2, h3, .entry-title, a")?.text()?.trim() ?: "Evento"

                    poster = element.selectFirst("img")?.attr("abs:src")
                        ?: element.selectFirst("img")?.attr("abs:data-src")
                                ?: defaultPoster
                }

                newLiveSearchResponse(title, href) {
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(
                HomePageList("Eventos y Repeticiones", items)
            )
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.title()
            .replace("Ver ", "", true)
            .substringBefore(" En Vivo")
            .substringBefore(" - LATINLUCHAS")
            .trim()
            .ifBlank { "Evento en vivo" }

        val plot = document.selectFirst("meta[property='og:description']")
            ?.attr("content")
            ?: "Transmisión en vivo y repeticiones - TV LatinLuchas"

        val poster = document.selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?: defaultPoster

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        // Buscamos todos los iframes, que es lo que funcionaba originalmente
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("abs:data-src") }
            if (src.isNotBlank()) {
                val fixedSrc = if (src.startsWith("//")) "https:$src" else src

                // Cargamos el extractor de forma directa, sin intentar renombrar
                // Esto garantiza que no haya errores de compilación ni de corrutinas
                loadExtractor(fixedSrc, data, subtitleCallback, callback)
            }
        }

        return true
    }
}