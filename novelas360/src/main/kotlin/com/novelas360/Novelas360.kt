package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ==============================
    // HEADERS (anti HTML vacío)
    // ==============================
    private suspend fun getDoc(url: String): Document =
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
    // LOAD (categoría = serie)
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)

        return if (url.contains("/categories/")) {
            loadCategoryAsSeries(document, url)
        } else {
            val title = document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBefore("–")
                ?.trim()
                ?: "Novela"

            val episode = newEpisode(url) {
                name = "Reproducir"
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                listOf(episode)
            )
        }
    }

    private suspend fun loadCategoryAsSeries(
        document: Document,
        url: String
    ): LoadResponse {

        val title = document.selectFirst("h4 span")?.text()
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
            .mapIndexedNotNull { index, link ->
                val epUrl = link.attr("href")
                val name = link.text().trim()
                if (epUrl.isBlank()) return@mapIndexedNotNull null

                newEpisode(epUrl) {
                    this.name = name
                    this.episode = index + 1
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
    // LOAD LINKS (AJAX REAL)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = getDoc(data)

        val postId = document
            .selectFirst("[id^=post-]")
            ?.id()
            ?.removePrefix("post-")
            ?: return false

        val ajaxHtml = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "mars_load_video_player",
                "post_id" to postId
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data,
                "User-Agent" to "Mozilla/5.0"
            )
        ).text

        val ajaxDoc = Jsoup.parse(ajaxHtml)

        ajaxDoc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.isBlank()) return@forEach
            if (src.startsWith("//")) src = "https:$src"

            if (
                src.contains("dailymotion") ||
                src.contains("ok.ru") ||
                src.contains("netu")
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
            ?.text()
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
