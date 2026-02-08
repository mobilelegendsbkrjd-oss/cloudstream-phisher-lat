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

    // User-Agent actualizado para evitar bloqueos e IO Errors
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private suspend fun getDoc(url: String): Document {
        return app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
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
        // Selector original que confirmaste que funciona
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

        // BUSQUEDA DE EPISODIOS MEJORADA
        // Buscamos en el área de contenido y en los items de lista
        val episodes = document.select("div.item h3 a, .entry-content a[href*='/video/'], .entry-content a[href*='capitulo']")
            .distinctBy { it.attr("href") } // Evitar duplicados
            .mapIndexedNotNull { index, el ->
                val epUrl = el.attr("href")
                if (epUrl.isBlank()) return@mapIndexedNotNull null

                newEpisode(epUrl) {
                    this.name = el.text().trim().ifBlank { "Capítulo ${index + 1}" }
                    this.episode = index + 1
                }
            }.sortedBy { it.episode } // Intentar ordenar por número si es posible

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

        // Buscamos todos los iframes
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src")) ?: return@forEach
            
            // Los extractores nativos son más estables para evitar Error IO
            if (!loadExtractor(src, data, subtitleCallback, callback)) {
                // Si no es un extractor conocido, intentamos jalar el MP4/M3U8 manualmente
                val iframeHtml = app.get(src, headers = mapOf("Referer" to data, "User-Agent" to userAgent)).text
                Regex("""https?:\/\/[^\s'"]+\.(mp4|m3u8)[^\s'"]*""").findAll(iframeHtml).forEach {
                    callback(
                        newExtractorLink(
                            source = "Novelas360",
                            name = "Servidor Externo",
                            url = it.value,
                            referer = src,
                            quality = Qualities.Unknown.value,
                            isM3u8 = it.value.contains("m3u8")
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
