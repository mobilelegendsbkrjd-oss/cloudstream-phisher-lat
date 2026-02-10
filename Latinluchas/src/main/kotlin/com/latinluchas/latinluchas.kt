package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class LatinLuchas : MainAPI() {

    override var name = "TV LatinLuchas"
    override var mainUrl = "https://tv.latinluchas.com/tv"
    override var lang = "es"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Live)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page > 1) {
            return HomePageResponse(emptyList(), false)
        }

        val document = app.get(mainUrl).document
        val results = ArrayList<SearchResponse>()

        document.select("article, .elementor-post, .post, a[href*='/tv/coli']")
            .forEach { element ->
                val href = element.attr("abs:href")
                if (!href.contains("/tv/coli")) return@forEach

                val title = element
                    .selectFirst("h2, h3, .entry-title, a")
                    ?.text()
                    ?.trim()
                    ?: "Evento en vivo"

                results.add(
                    SearchResponse(
                        title,
                        href,
                        name,
                        TvType.Live,
                        defaultPoster,
                        null
                    )
                )
            }

        val home = HomePageList(
            "Eventos y Repeticiones",
            results,
            false
        )

        return HomePageResponse(listOf(home), false)
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
                ?: document.selectFirst(".elementor-text-editor, p")
                    ?.text()
                ?: "Transmisión o repetición en TV LatinLuchas"

        return LiveStreamLoadResponse(
            title,
            url,
            name,
            defaultPoster,
            plot
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        var found = false

        document.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src")
            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            if (loadExtractor(src, data, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }
}
