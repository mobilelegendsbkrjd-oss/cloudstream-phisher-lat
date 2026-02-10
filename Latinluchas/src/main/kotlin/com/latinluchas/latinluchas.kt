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
        val items = mutableListOf<SearchResponse>()

        document.select("a[href*='/tv/coli']").forEach { element ->
            val href = element.attr("abs:href")
            if (href.isBlank()) return@forEach

            val title = element.text().trim().ifBlank {
                "Evento en vivo"
            }

            val item = newLiveSearchResponse(
                title,
                href,
                TvType.Live,
                false
            ).apply {
                posterUrl = defaultPoster
            }

            items.add(item)
        }

        return HomePageResponse(
            listOf(
                HomePageList(
                    "Eventos y Repeticiones",
                    items
                )
            ),
            false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.title()
            .substringBefore(" -")
            .trim()
            .ifBlank { "Evento en vivo" }

        val plot =
            document.selectFirst("meta[property='og:description']")
                ?.attr("content")
                ?: "Transmisión en vivo o repetición"

        return newLiveStreamLoadResponse(
            title,
            url,
            name
        ) {
            posterUrl = defaultPoster
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
        var found = false

        document.select("iframe[src]").forEach { iframe ->
            var src = iframe.attr("abs:src")
            if (src.startsWith("//")) src = "https:$src"

            if (loadExtractor(src, data, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }
}
