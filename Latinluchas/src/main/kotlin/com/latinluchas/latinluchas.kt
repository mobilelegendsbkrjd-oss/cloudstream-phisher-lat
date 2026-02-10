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

        val home = document
            .select("article, .elementor-post, .post, a[href*='/tv/coli']")
            .mapNotNull { element ->
                val href = element.attr("abs:href")
                if (!href.contains("/tv/coli")) return@mapNotNull null

                val title = element
                    .selectFirst("h2, h3, .entry-title, a")
                    ?.text()
                    ?.trim()
                    ?: "Evento sin título"

                newLiveSearchResponse(title, href) {
                    posterUrl = defaultPoster
                }
            }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(
                HomePageList("Eventos y Repeticiones", home)
            )
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.title()
            .substringBefore(" - TV LatinLuchas")
            .trim()
            .ifBlank { "Evento en vivo" }

        val plot =
            document.selectFirst("meta[property='og:description']")
                ?.attr("content")
                ?: document.selectFirst(
                    ".elementor-widget-container p, .elementor-text-editor"
                )?.text()
                ?: "Repetición o transmisión en vivo - TV LatinLuchas"

        return newLiveStreamLoadResponse(
            title,
            url,
            name,
            url,
            defaultPoster
        ) {
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src")
            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}
