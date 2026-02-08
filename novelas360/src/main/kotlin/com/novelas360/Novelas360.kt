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

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private suspend fun getDoc(url: String): Document {
        return app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl
            )
        ).document
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getDoc("$mainUrl/telenovelas/mexico/")
        val items = document.select("div.tabcontent#Todos > a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query")
        return document.select(".video-item, article").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3, h2")?.text() ?: return@mapNotNull null
            val img = item.selectFirst("img")
            val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") } ?: img?.attr("src"))

            newTvSeriesSearchResponse(title, link.attr("href"), TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)

        val title = document.selectFirst("h1, h4 span")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("–")?.trim()
            ?: "Novela"

        val plot = document.selectFirst(".entry-content p, meta[name=description]")?.text()
        val poster = fixUrl(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val episodes = document.select("div.item h3 a, .entry-content a[href*='/video/'], .entry-content a[href*='capitulo']")
            .distinctBy { it.attr("href") }
            .mapIndexedNotNull { index, el ->
                val epUrl = el.attr("href")
                if (epUrl.isBlank()) return@mapIndexedNotNull null

                newEpisode(epUrl) {
                    this.name = el.text().trim().ifBlank { "Capítulo ${index + 1}" }
                    this.episode = index + 1
                }
            }.sortedByDescending { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDoc(data)

        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src")) ?: return@forEach
            
            val loaded = loadExtractor(src, data, subtitleCallback, callback)
            
            if (!loaded) {
                val iframeHtml = app.get(src, headers = mapOf("Referer" to data, "User-Agent" to userAgent)).text
                Regex("""https?:\/\/[^\s'"]+\.(mp4|m3u8)[^\s'"]*""").findAll(iframeHtml).forEach {
                    val videoUrl = it.value
                    // USANDO LA SINTAXIS CORRECTA PARA NEWEXTRACTORLINK
                    callback(
                        newExtractorLink(
                            "Novelas360",
                            "Servidor Externo",
                            videoUrl,
                            src,
                            Qualities.Unknown.value,
                            videoUrl.contains("m3u8")
                        )
                    )
                }
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null

        val title = selectFirst("span.tabcontentnom")?.text()?.trim() ?: return null
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") } ?: img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }
}
