package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ==============================
    // HTTP
    // ==============================
    private suspend fun getDoc(url: String): Document {
        return app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer" to mainUrl
            )
        ).document
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    // ==============================
    // MAIN PAGE
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = getDoc("$mainUrl/telenovelas/mexico/")

        val items = document
            .select("div.tabcontent#Todos > a")
            .mapNotNull { it.toSearchResult() }

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
            val title = item.selectFirst("h3")?.text() ?: return@mapNotNull null

            val img = item.selectFirst("img")
            val poster = fixUrl(
                img?.attr("data-src")?.ifBlank { img.attr("src") }
            )

            newTvSeriesSearchResponse(title, link.attr("href"), TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    // ==============================
    // LOAD SERIE
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)

        val title = document.selectFirst("h4 span")
            ?.text()
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBefore("–")
                ?.trim()
            ?: "Novela"

        val plot = document.selectFirst("meta[name=description]")
            ?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")
                ?.attr("content")

        val poster = fixUrl(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
        )

        val episodes = document.select("div.item h3 a")
            .mapIndexedNotNull { index, el ->
                val epUrl = el.attr("href")
                if (epUrl.isBlank()) return@mapIndexedNotNull null

                newEpisode(epUrl) {
                    name = el.text().trim()
                    episode = index + 1
                }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    // ==============================
    // LOAD LINKS (AJAX + IFRAME)
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

            // Extractores conocidos
            if (
                src.contains("dailymotion") ||
                src.contains("ok.ru") ||
                src.contains("netu")
            ) {
                loadExtractor(src, data, subtitleCallback, callback)
                return@forEach
            }

            // Fallback AJAX / embed directo
            val iframeHtml = app.get(src, headers = mapOf("Referer" to data)).text

            Regex("""https?:\/\/[^\s'"]+\.(mp4|m3u8)""")
                .findAll(iframeHtml)
                .forEach {
                    callback(
                        newExtractorLink(
                            source = "Novelas360",
                            name = "Servidor",
                            url = it.value
                        ) {
                            referer = src
                            quality = Qualities.Unknown.value
                        }
                    )
                }
        }
        return true
    }

    // ==============================
    // PARSER
    // ==============================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null

        val title = selectFirst("span.tabcontentnom")
            ?.text()
            ?.trim()
            ?: return null

        val img = selectFirst("img")
        val poster = fixUrl(
            img?.attr("data-src")?.ifBlank { img.attr("src") }
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }
}
