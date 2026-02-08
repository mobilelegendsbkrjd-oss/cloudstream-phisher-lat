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
    // HTTP SAFE
    // ==============================
    private suspend fun getDoc(url: String): Document? =
        runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to mainUrl
                )
            ).document
        }.getOrNull()

    private fun fixUrl(url: String?): String? =
        if (url.isNullOrBlank()) null
        else if (url.startsWith("//")) "https:$url" else url

    // ==============================
    // MAIN PAGE (CATEGORÍAS)
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val categories = listOf(
            "Telenovelas México" to "/telenovelas/mexico/",
            "Telenovelas Turcas" to "/telenovelas/turcas/",
            "Telenovelas Colombianas" to "/telenovelas/colombianas/",
            "Telenovelas Brasileñas" to "/telenovelas/brasilenas/",
            "Telenovelas Argentinas" to "/telenovelas/argentinas/"
        )

        val home = categories.mapNotNull { (title, path) ->
            val doc = getDoc("$mainUrl$path") ?: return@mapNotNull null

            val items = doc.select("div.tabcontent#Todos > a")
                .mapNotNull { it.toSearchResult() }

            HomePageList(title, items)
        }

        return newHomePageResponse(home, false)
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query") ?: return emptyList()

        return document.select(".video-item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3")?.text() ?: return@mapNotNull null

            val img = item.selectFirst("img")
            val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

            newTvSeriesSearchResponse(title, link.attr("href"), TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    // ==============================
    // LOAD SERIE
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url) ?: throw ErrorLoadingException()

        val title = document.selectFirst("h4 span")?.text()
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBefore("–")
                ?.trim()
            ?: "Novela"

        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val poster = fixUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val episodesAsc = document.select("div.item h3 a")
            .mapIndexedNotNull { index, el ->
                val epUrl = el.attr("href")
                if (epUrl.isBlank()) return@mapIndexedNotNull null

                newEpisode(epUrl) {
                    name = el.text().trim()
                    episode = index + 1
                }
            }

        // 🔥 ORDEN DESCENDENTE
        val episodes = episodesAsc.reversed()

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
    // LOAD LINKS (ANTI IO ERROR)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = getDoc(data) ?: return false

        document.select("iframe").forEach { iframe ->
            runCatching {
                var src = iframe.attr("src")
                if (src.isBlank()) return@runCatching
                if (src.startsWith("//")) src = "https:$src"

                if (
                    src.contains("dailymotion") ||
                    src.contains("ok.ru") ||
                    src.contains("netu")
                ) {
                    loadExtractor(src, data, subtitleCallback, callback)
                    return@runCatching
                }

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
        }
        return true
    }

    // ==============================
    // PARSER
    // ==============================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null

        val title = selectFirst("span.tabcontentnom")?.text()?.trim() ?: return null

        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }
}
