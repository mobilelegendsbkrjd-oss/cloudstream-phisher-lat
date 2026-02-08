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

    private val chromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, gecko) Chrome/121.0.0.0 Safari/537.36"

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
        // Lista de categorías que queremos mostrar en el inicio
        val categories = listOf(
            Pair("Telenovelas México", "$mainUrl/telenovelas/mexico/"),
            Pair("Novelas Turcas", "$mainUrl/telenovelas/turcas/"),
            Pair("Últimos Capítulos", mainUrl)
        )

        val homePageLists = categories.map { (title, url) ->
            val doc = getDoc(url)
            // Selector más flexible: busca enlaces que contengan imágenes o títulos de novela
            val items = doc.select("div.item a, div.tabcontent a, .video-item a").mapNotNull { 
                it.toSearchResult() 
            }.distinctBy { it.url }
            
            HomePageList(title, items)
        }

        return newHomePageResponse(homePageLists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query")
        return document.select(".video-item, div.item").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3, .tabcontentnom")?.text() ?: return@mapNotNull null
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
        var pageCount = 1
        
        while (pageCount <= 50) { 
            val currentUrl = if (pageCount == 1) url else "${url.trimEnd('/')}/page/$pageCount/"
            val pageDoc = try { getDoc(currentUrl) } catch(e: Exception) { null }

            val items = pageDoc?.select("div.item h3 a, .video-item h3 a") ?: emptyList()
            if (items.isEmpty()) break
            
            items.forEach { el ->
                allEpisodes.add(newEpisode(el.attr("href")) {
                    this.name = el.text().trim()
                })
            }
            pageCount++
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
            
            // 1. Intentamos cargar el HTML del iframe para ver si el link está ahí
            val iframeRes = app.get(src, referer = data)
            val iframeHtml = iframeRes.text
            val iframeCookie = iframeRes.headers["set-cookie"] ?: ""

            // 2. Buscamos el link del video (.m3u8 o .mp4)
            Regex("""(https?.*?\.(?:m3u8|mp4).*?)["']""").findAll(iframeHtml).forEach { match ->
                val videoUrl = match.groupValues[1].replace("\\/", "/")
                
                callback(
                    newExtractorLink(
                        "Novelas360",
                        "Servidor Principal",
                        videoUrl
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.isM3u8 = videoUrl.contains("m3u8")
                        // ESTO ES LO QUE ARREGLA EL ERROR IO:
                        this.headers = mapOf(
                            "User-Agent" to chromeUA,
                            "Referer" to src, // El referer debe ser la URL del iframe
                            "Origin" to "https://novelas360.cyou",
                            "Accept" to "*/*",
                            "Cookie" to iframeCookie // Pasamos la cookie de sesión si existe
                        )
                    }
                )
            }
            
            // 3. Si no funciona lo anterior, dejamos que los extractores estándar lo intenten
            loadExtractor(src, data, subtitleCallback, callback)
        }
        return true
    }
