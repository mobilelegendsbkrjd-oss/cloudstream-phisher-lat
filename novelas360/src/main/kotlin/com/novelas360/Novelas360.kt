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
    // Helper para evitar HTML vacío
    // ==============================
    private suspend fun getDoc(url: String) =
        app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
                "Referer" to mainUrl
            )
        ).document

    // ==============================
    // MAIN PAGE (Novelas México)
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = "$mainUrl/telenovelas/mexico/"
        val document = getDoc(url)

        val items = document
            .select("div.tabcontent#Todos > a")
            .mapNotNull { it.toSearchResultFromCategory() }

        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            false
        )
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = getDoc(url)

        return document.select("article").mapNotNull {
            it.toSearchResultFromPost()
        }
    }

    // ==============================
    // LOAD NOVELA (Categoría)
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)

        val title = document.selectFirst("h1, h2.entry-title")
            ?.text()
            ?.trim()
            ?: "Novela"

        val episodes = document
            .select("article")
            .mapIndexed { index, article ->
                val epUrl = article.selectFirst("a")?.attr("href") ?: url
                val epTitle = article.selectFirst("h2, h3")?.text()

                newEpisode(epUrl) {
                    name = epTitle ?: "Capítulo ${index + 1}"
                    episode = index + 1
                }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl =
                document.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    // ==============================
    // LOAD LINKS (Reproductores)
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

            if (src.isNotEmpty()) {
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
        }
        return true
    }

    // ==============================
    // PARSER PARA MAIN PAGE (Categorías)
    // ==============================
    private fun Element.toSearchResultFromCategory(): SearchResponse? {
        val href = attr("href")
        if (href.isEmpty()) return null

        val title = selectFirst("span.tabcontentnom")
            ?.ownText()
            ?.trim()
            ?: return null

        val img = selectFirst("img")
        val posterUrl = img?.attr("data-src")?.ifEmpty {
            img.attr("src")
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // PARSER PARA SEARCH (Posts)
    // ==============================
    private fun Element.toSearchResultFromPost(): SearchResponse? {
        val titleElement = selectFirst("h2 a, h3 a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")

        val img = selectFirst("img")
        val posterUrl = img?.attr("data-src")?.ifEmpty {
            img.attr("src")
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }
}
