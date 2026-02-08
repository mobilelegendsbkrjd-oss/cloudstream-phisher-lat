package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Tlnovelas : MainAPI() {
    override var mainUrl              = "https://ww2.tlnovelas.net"
    override var name                 = "Tlnovelas"
    override val hasMainPage          = true
    override var lang                 = "es"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .p-content, .ani-card").mapNotNull { 
            it.toSearchResult() 
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst(".vk-info p, .p-title, .ani-txt")?.text() 
            ?: this.selectFirst("a")?.attr("title") 
            ?: ""
        val href = this.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/buscar/?q=$query").document
        return document.select(".vk-poster, .p-content, .ani-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.card-title, .vk-title-main")?.text() ?: "Sin título"
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst(".card-text")?.text()
        
        val episodes = document.select("a[href*='/ver/']").map {
            val epHref = it.attr("href")
            val epName = it.attr("title").ifBlank { it.text().trim() }
            newEpisode(epHref) {
                this.name = epName
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

        override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data).text
        
        // 1. Regex mejorado para capturar URLs completas dentro del array e[x]
        // Busca patrones como e[0]='https://...'
        val videoUrlRegex = Regex("""e\[\d+\]\s*=\s*['"](https?://.*?)['"]""")
        
        val foundLinks = videoUrlRegex.findAll(response).map { it.groupValues[1] }.toList()
        
        if (foundLinks.isEmpty()) {
            // Si el regex de arriba falla, intentamos una búsqueda más agresiva de enlaces de video
            val genericUrlRegex = Regex("""https?://[\w\d]+\.[\w\d]+(?:/[\w\d]+)*/e/[\w\d]+""")
            genericUrlRegex.findAll(response).forEach { match ->
                loadExtractor(match.value, data, subtitleCallback, callback)
            }
        } else {
            for (link in foundLinks) {
                // Limpiamos el link por si tiene carácteres de escape y cargamos el extractor
                val cleanLink = link.replace("\\/", "/")
                loadExtractor(cleanLink, data, subtitleCallback, callback)
            }
        }

        // 2. Por si acaso, revisamos iframes (aunque el script parece ser el método principal)
        val document = Jsoup.parse(response)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
