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
        
        // El sitio usa .vk-poster en la Home y .p-content o .ani-card en otras secciones
        val home = document.select(".vk-poster, .p-content, .ani-card").mapNotNull { 
            it.toSearchResult() 
        }
        
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        // Buscamos el título en los distintos lugares donde aparece según la sección
        val title = this.selectFirst(".vk-info p, .p-title, .ani-txt")?.text() 
            ?: this.selectFirst("a")?.attr("title") 
            ?: ""
        
        val href = this.selectFirst("a")?.attr("href") ?: ""
        
        // Buscamos la imagen
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
        
        // Selector para capítulos (algunos están en la sidebar o en el cuerpo)
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
        val document = Jsoup.parse(response)
        
        // Extraer iframes de servidores externos
        val iframes = document.select("iframe")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
