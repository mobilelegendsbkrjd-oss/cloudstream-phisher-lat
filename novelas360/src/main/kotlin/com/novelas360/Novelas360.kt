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
    private suspend fun getDoc(url: String): Document =
        app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer" to mainUrl
            )
        ).document

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

        val doc = getDoc("$mainUrl/telenovelas/mexico/")

        val items = doc.select("div.tabcontent#Todos > a")
            .mapNotNull { parseCategoryItem(it) }

        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            false
        )
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = getDoc("$mainUrl/?s=$query")

        return doc.select(".video-item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = item.selectFirst("h3")?.text() ?: return@mapNotNull null

            val poster = fixUrl(
                item.selectFirst("img")
                    ?.attr("data-src")
                    ?.ifBlank { item.selectFirst("img")?.attr("src") }
            )

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    // ==============================
    // LOAD SERIE
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)

        val title = doc.selectFirst("h4 span")?.text()
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBefore("–")
                ?.trim()
            ?: "Novela"

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val poster = fixUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val episodes = doc.select("div.item h3 a").mapIndexedNotNull { index, a ->
            val epUrl = a.attr("href")
            if (epUrl.isBlank()) return@mapIndexedNotNull null

            newEpisode(epUrl) {
                name = a.text().trim()
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
    // LOAD LINKS (AJAX / iframe)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = getDoc(data)
        val iframeUrl = doc.selectFirst("iframe")?.attr("src") ?: return false
        val iframeHtml = app.get(iframeUrl).text

        // Dailymotion
        Regex("""dailymotion.com/embed/video/([a-zA-Z0-9]+)""")
            .find(iframeHtml)
            ?.groupValues
            ?.get(1)
            ?.let { id ->
                callback(
                    newExtractorLink(
                        source = "Dailymotion",
                        name = "Dailymotion",
                        url = "https://www.dailymotion.com/video/$id"
                    ) {
                        referer = iframeUrl
                        quality = Qualities.Unknown.value
                    }
                )
            }

        // MP4 / M3U8 fallback
        Regex("""https?:\/\/[^\s'"]+\.(mp4|m3u8)""")
            .findAll(iframeHtml)
            .forEach {
                callback(
                    newExtractorLink(
                        source = "Novelas360",
                        name = "Servidor",
                        url = it.value
                    ) {
                        referer = iframeUrl
                        isM3u8 = it.value.endsWith("m3u8")
                        quality = Qualities.Unknown.value
                    }
                )
            }

        return true
    }

    // ==============================
    // PARSER MAIN PAGE ITEM
    // ==============================
    private fun parseCategoryItem(el: Element): SearchResponse? {
        val href = el.attr("href")
        val title = el.selectFirst("span.tabcontentnom")?.text() ?: return null

        val poster = fixUrl(
            el.selectFirst("img")
                ?.attr("data-src")
                ?.ifBlank { el.selectFirst("img")?.attr("src") }
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }
}
