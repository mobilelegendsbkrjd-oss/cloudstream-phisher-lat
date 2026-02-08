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

    val doc = app.get(data).document

    // 1. iframe principal del capitulo
    val iframeUrl = doc.selectFirst("iframe")?.attr("src")
        ?: return false

    // 2. Entramos al iframe intermedio
    val iframePage = app.get(iframeUrl).text

    // 3. Buscar links de video dentro del JS o HTML
    // Caso Dailymotion
    Regex("""https://www.dailymotion.com/embed/video/([a-zA-Z0-9]+)""")
        .find(iframePage)
        ?.groupValues
        ?.get(1)
        ?.let { id ->
            callback(
                ExtractorLink(
                    source = "Dailymotion",
                    name = "Dailymotion",
                    url = "https://www.dailymotion.com/video/$id",
                    referer = iframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
        }

    // 4. fallback: buscar cualquier mp4 o m3u8
    Regex("""https?:\/\/[^\s'"]+\.(m3u8|mp4)""")
        .findAll(iframePage)
        .forEach {
            callback(
                ExtractorLink(
                    source = "Novelas360",
                    name = "Servidor",
                    url = it.value,
                    referer = iframeUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = it.value.endsWith("m3u8")
                )
            )
        }

    return true
}
