package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ==============================
    // HEADERS (evita HTML vacío)
    // ==============================
    private suspend fun getDoc(url: String) =
        app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Referer" to mainUrl
            )
        ).document

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    // ==============================
    // MAIN PAGE (México)
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = getDoc("$mainUrl/telenovelas/mexico/")

        val items = document
            .select("div.tabcontent#Todos > a")
            .mapNotNull { it.toCategoryResult() }

        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            false
        )
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query")

        return document.select(".video-item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val title = item.selectFirst("h3")?.text() ?: return@mapNotNull null
            val img = item.selectFirst("img")

            val posterUrl = fixUrl(
                img?.attr("data-src")?.ifBlank { img.attr("src") }
            )

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ==============================
    // LOAD NOVELA (categoría)
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)

        val title = document.selectFirst("h1, h2.entry-title")
            ?.text()
            ?.trim()
            ?: "Novela"

        val episodes = document
            .select("article")
            .mapNotNull { article ->
                val link = article.selectFirst("h2 a, h3 a")
                    ?: return@mapNotNull null

                val epUrl = link.attr("href")
                if (epUrl.isBlank()) return@mapNotNull null

                newEpisode(epUrl) {
                    name = link.text()
                }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = fixUrl(
                document.selectFirst("meta[property=og:image]")?.attr("content")
            )
        }
    }

    // ==============================
    // LOAD LINKS (reproductores)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = getDoc(data)

        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.isBlank()) return@forEach

            if (src.startsWith("//")) src = "https:$src"

            if (
                src.contains("dailymotion") ||
                src.contains("ok.ru") ||
                src.contains("netu") ||
                src.contains("embed")
            ) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        return true
    }

    // ==============================
    // PARSER MAIN PAGE
    // ==============================
    private fun Element.toCategoryResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null

        val title = selectFirst("span.tabcontentnom")
            ?.ownText()
            ?.trim()
            ?: return null

        val img = selectFirst("img")
        val posterUrl = fixUrl(
            img?.attr("data-src")?.ifBlank { img.attr("src") }
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }
}
