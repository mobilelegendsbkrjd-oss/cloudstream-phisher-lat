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

    private fun fixUrl(url: String?): String? =
        if (url.isNullOrBlank()) null
        else if (url.startsWith("//")) "https:$url" else url

    // ==============================
    // MAIN PAGE (TABS REALES)
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = getDoc("$mainUrl/telenovelas/mexico/")

        val tabs = listOf(
            "Telenovelas México" to "Mexico",
            "Telenovelas Turcas" to "Turcas",
            "Telenovelas Colombianas" to "Colombia",
            "Telenovelas Brasileñas" to "Brasil",
            "Telenovelas Argentinas" to "Argentina"
        )

        val home = tabs.mapNotNull { (title, id) ->
            val items = document
                .select("div.tabcontent#$id a")
                .mapNotNull { it.toSearchResult() }

            if (items.isEmpty()) null else HomePageList(title, items)
        }

        return newHomePageResponse(home, false)
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query")

        return document.select(".video-item").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val img = it.selectFirst("img")

            newTvSeriesSearchResponse(title, a.attr("href"), TvType.TvSeries) {
                posterUrl = fixUrl(img?.attr("data-src") ?: img?.attr("src"))
            }
        }
    }

    // ==============================
    // LOAD SERIE
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)

        val title = document.selectFirst("h4 span")?.text()
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBefore("–")
                ?.trim()
            ?: "Novela"

        val plot = document.selectFirst("meta[name=description]")?.attr("content")

        val poster = fixUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val episodes = document.select("div.item h3 a")
            .mapIndexedNotNull { index, el ->
                val href = el.attr("href")
                if (href.isBlank()) return@mapIndexedNotNull null

                newEpisode(href) {
                    name = el.text()
                    episode = index + 1
                }
            }
            .reversed() // 🔥 999 → 1

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
    // LOAD LINKS (AJAX REAL – SIN IO)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = getDoc(data)

        val postId = document.selectFirst("[id^=post-]")
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
                "Referer" to data
            )
        ).text

        val ajaxDoc = Jsoup.parse(ajaxHtml)

        ajaxDoc.select("iframe").forEach {
            var src = it.attr("src")
            if (src.startsWith("//")) src = "https:$src"

            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }

    // ==============================
    // CARD PARSER
    // ==============================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null

        val title = selectFirst("span.tabcontentnom")?.text()?.trim() ?: return null
        val img = selectFirst("img")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = fixUrl(img?.attr("data-src") ?: img?.attr("src"))
        }
    }
}
