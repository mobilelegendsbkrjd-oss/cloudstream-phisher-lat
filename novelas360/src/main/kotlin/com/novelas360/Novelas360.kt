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
            headers = mapOf("User-Agent" to chromeUA, "Referer" to mainUrl)
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
        var pageCount = 1
        
        // PAGINACIÓN PARA CUKUR Y OTRAS LARGAS
        while (pageCount <= 30) {
            val currentUrl = if (pageCount == 1) url else "${url.trimEnd('/')}/page/$pageCount/"
            val pageDoc = try { 
                val d = getDoc(currentUrl)
                if (d.select("div.item h3 a").isEmpty()) null else d
            } catch(e: Exception) { null }

            if (pageDoc == null) break
            
            pageDoc.select("div.item h3 a").forEach { el ->
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
            
            // Si es el dominio .cyou, usamos nuestro extractor especial
            if (src.contains("novelas360.cyou")) {
                NovelasCyou().getUrl(src, data).forEach(callback)
            } else {
                loadExtractor(src, data, subtitleCallback, callback)
            }
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

// --- EXTRACTOR PERSONALIZADO PARA MATAR EL ERROR IO ---
class NovelasCyou : ExtractorApi() {
    override val name = "Novelas360"
    override val mainUrl = "https://novelas360.cyou"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val response = app.get(url, referer = referer).text
        
        // Buscamos el m3u8 escondido en el script
        val m3u8 = Regex("""file:\s*"(https?.*?\.(?:m3u8|mp4).*?)"""").find(response)?.groupValues?.get(1)
            ?: Regex("""source:\s*"(https?.*?\.(?:m3u8|mp4).*?)"""").find(response)?.groupValues?.get(1)

        return if (m3u8 != null) {
            listOf(
                ExtractorLink(
                    name,
                    name,
                    m3u8.replace("\\/", "/"),
                    url,
                    Qualities.Unknown.value,
                    m3u8.contains("m3u8"),
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                        "Referer" to url,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                )
            )
        } else emptyList()
    }
}
