package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Tlnovelas : MainAPI() {
    override var mainUrl              = "https://ww2.tlnovelas.net"
    override var name                 = "Tlnovelas"
    override val hasMainPage          = true
    override var lang                 = "es"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    // Ajustamos las rutas a las categorías reales del sitio
    override val mainPage = mainPageOf(
        "gratis/online/" to "En Emisión",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        // Selector corregido: El sitio usa .ani-card para las novelas
        val home = document.select(".ani-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        // En .ani-card, el título está en .ani-txt y la imagen en .ani-img img
        val title     = this.selectFirst(".ani-txt")?.text() ?: ""
        val href      = this.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = this.selectFirst(".ani-img img")?.attr("src")
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // El sitio usa un parámetro de búsqueda "q" según el HTML
        val document = app.get("$mainUrl/buscar/?q=$query").document
        return document.select(".ani-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document    = app.get(url).document
        val title       = document.selectFirst("h1.card-title")?.text() ?: "Sin título"
        val poster      = document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = document.selectFirst(".card-text:contains(Ana Leal), .card-text")?.text()
        
        // En este sitio, los capítulos suelen estar en una lista o botones
        // Basado en el HTML, buscamos el botón de "Ver capítulos" o enlaces similares
        val episodes = document.select("a[href*='/ver/']").map {
            val epHref = it.attr("href")
            val epName = it.attr("title").ifBlank { it.text().trim() }
            newEpisode(epHref) {
                this.name = epName
            }
        }.distinctBy { it.data } // Evitamos duplicados

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
        
        // 1. Extraer IDs de video del script
        val videoIdRegex = Regex("""e\[\d+\]\s*=\s*['"](.*?)['"]""")
        videoIdRegex.findAll(response).forEach { match ->
            val fullId = match.groupValues[1]
            val cleanId = fullId.substringBefore("|")
            
            // Ejemplo: Si identificas que el servidor es Fembed o similar,
            // aquí llamarías a su extractor. Por ahora buscamos iframes.
        }

        // 2. Corregido: Convertir String a Documento HTML usando Jsoup.parse
        val document = org.jsoup.Jsoup.parse(response)
        
        // 3. Corregido: Usar un ciclo 'for' para permitir funciones suspend (loadExtractor)
        val iframes = document.select("iframe")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook")) {
                // Ahora sí permite llamar a loadExtractor aquí
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
