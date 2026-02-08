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

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/page/$page"
        val document = app.get(url).document
        val home = document.select(".vk-poster, .p-content, .ani-card, .ani-txt").mapNotNull { 
            it.toSearchResult() 
        }.distinctBy { it.url }
        
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        var title = this.selectFirst(".vk-info p, .p-title, .ani-txt")?.text() 
            ?: this.selectFirst("a")?.attr("title") ?: ""
        
        var href = this.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        if (href.contains("/ver/")) {
            // Limpieza: "Amanecer Capítulo 69" -> "Amanecer"
            title = title.split(Regex("(?i)Capitulo|Capítulo"))[0].trim()
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+"), "")
                .replace(Regex("(?i)-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Si entramos desde un link de "ver", intentamos saltar a la página de la novela
        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")
        val finalDoc = if (novelaLink != null && url.contains("/ver/")) {
            app.get(novelaLink).document
        } else document

        // CORRECCIÓN DE TÍTULO: Buscamos en varias etiquetas
        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")?.trim() ?: "Telenovela"
            
        // CORRECCIÓN DE IMAGEN: Usamos el meta tag de la novela
        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content") 
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")

        val description = finalDoc.selectFirst(".card-text, .ani-description")?.text()
        
        val episodes = finalDoc.select("a[href*='/ver/']").mapNotNull {
            val epHref = it.attr("href")
            // Limpieza de nombre de episodio para que no sea largo
            val epName = it.text().trim()
                .replace(title, "", ignoreCase = true)
                .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "")
                .trim()
                
            newEpisode(epHref) {
                this.name = if(epName.isEmpty()) "Capítulo" else "Capítulo $epName"
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
        
        // Regex mejorado para capturar todas las variables e[0], e[1]...
        val videoUrlRegex = Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""")
        
        videoUrlRegex.findAll(response).forEach { match ->
            val link = match.groupValues[1].replace("\\/", "/")
            
            // Forzamos la carga de cada link. 
            // Si solo sale Playerwish, es probable que los otros dominios (bysejikuar, luluvdo) 
            // no tengan un extractor nativo en Cloudstream.
            loadExtractor(link, data, subtitleCallback, callback)
        }

        return true
    }
}
