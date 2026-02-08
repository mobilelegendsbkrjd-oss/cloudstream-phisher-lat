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

    private val chromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private suspend fun getDoc(url: String): Document {
        return app.get(
            url,
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to mainUrl
            )
        ).document
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDoc("$mainUrl/telenovelas/mexico/")
        val items = document.select("div.tabcontent#Todos > a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(listOf(HomePageList("Telenovelas México", items)), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query")
        return document.select(".video-item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3")?.text() ?: return@mapNotNull null
            val img = item.selectFirst("img")
            val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

            newTvSeriesSearchResponse(title, link.attr("href"), TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDoc(url)
        val title = doc.selectFirst("h4 span, h1")?.text() ?: "Novela"
        val poster = fixUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        
        val allEpisodes = mutableListOf<Episode>()
        
        // Paginación de capítulos
        var currentUrl: String? = url
        var pageCount = 1
        
        while (currentUrl != null && pageCount <= 40) {
            val pageDoc = if (pageCount == 1) doc else getDoc(currentUrl)
            val items = pageDoc.select("div.item h3 a")
            
            if (items.isEmpty()) break
            
            items.forEach { el ->
                allEpisodes.add(newEpisode(el.attr("href")) {
                    this.name = el.text().trim()
                })
            }

            // Buscar link de "Siguiente" o construir el siguiente path
            pageCount++
            currentUrl = "${url.removeSuffix("/")}/page/$pageCount/"
            
            // Si la página actual no tiene resultados, el siguiente getDoc fallará o traerá vacío y el loop romperá
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes.distinctBy { it.data }.reversed()) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")
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
            
            // Bypass para novelas360.cyou y similares
            val iframeRes = app.get(src, headers = mapOf(
                "Referer" to data,
                "User-Agent" to chromeUA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            ))

            val iframeHtml = iframeRes.text
            
            // Buscamos archivos de video reales dentro del embed
            val videoRegex = Regex("""(https?.*?\.m3u8.*?)["']""")
            videoRegex.findAll(iframeHtml).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                callback(
                    newExtractorLink(
                        "Novelas360",
                        "Servidor HD",
                        videoUrl,
                        src,
                        Qualities.Unknown.value,
                        true
                    ).apply {
                        this.headers = mapOf(
                            "User-Agent" to chromeUA,
                            "Referer" to src,
                            "Origin" to "https://novelas360.cyou"
                        )
                    }
                )
            }
            
            // También intentamos con extractores conocidos por si acaso
            loadExtractor(src, data, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        val title = selectFirst("span.tabcontentnom")?.text()?.trim() ?: return null
        val img = selectFirst("img")
        val poster = fixUrl(img?.attr("data-src")?.ifBlank { img.attr("src") })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }
}
