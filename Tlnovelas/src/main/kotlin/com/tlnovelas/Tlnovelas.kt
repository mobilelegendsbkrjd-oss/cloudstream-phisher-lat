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
        }.distinctBy { it.url } // Evita duplicados en la Home
        
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        var title = this.selectFirst(".vk-info p, .p-title, .ani-txt")?.text() 
            ?: this.selectFirst("a")?.attr("title") 
            ?: ""
        
        var href = this.selectFirst("a")?.attr("href") ?: ""
        
        // --- LÓGICA DE REFINAMIENTO ---
        if (href.contains("/ver/")) {
            // 1. Limpiar título: de "Amanecer Capítulo 69" a "Amanecer"
            title = title.split("Capítulo")[0].split("Capitulo")[0].trim()
            
            // 2. Normalizar URL: Convertir link de episodio a link de novela
            // Ejemplo: .../ver/amanecer-capitulo-69/ -> .../novela/amanecer/
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("-capitulo-\\d+"), "")
                .replace(Regex("-capítulo-\\d+"), "")
            
            href = "$mainUrl/novela/$slug/"
        }
        
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Si por alguna razón el slug normalizado falla, intentamos cargar la página
        val document = app.get(url).document
        
        // Si caímos en una página de "ver capítulo" por error, buscamos el link a la novela
        val realUrl = if (url.contains("/ver/")) {
            document.selectFirst("a[href*='/novela/']")?.attr("href") ?: url
        } else url
        
        val finalDoc = if (realUrl != url) app.get(realUrl).document else document

        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main")?.text() 
            ?.replace("Capitulos de", "")?.trim() ?: "Sin título"
            
        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content")
        val description = finalDoc.selectFirst(".card-text")?.text()
        
        // Obtener lista completa de episodios desde la página de la novela
        val episodes = finalDoc.select("a[href*='/ver/']").map {
            val epHref = it.attr("href")
            val epName = it.attr("title").ifBlank { it.text().trim() }
                .replace(title, "", ignoreCase = true)
                .replace("Ver", "", ignoreCase = true)
                .trim()
                
            newEpisode(epHref) {
                this.name = epName.ifBlank { "Capítulo" }
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodes) {
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
        // Obtenemos el HTML completo de la página del capítulo
        val response = app.get(data).text
        
        // 1. Buscamos todas las URLs dentro del array e[n] usando Regex
        // Este Regex captura lo que esté entre comillas simples después de e[número]=
        val videoUrlRegex = Regex("""e\[\d+\]\s*=\s*'(https?://[^']+)""")
        
        // 2. Iteramos sobre todos los hallazgos (Opcion 1, 2, 3, 4, etc.)
        videoUrlRegex.findAll(response).forEach { match ->
            val link = match.groupValues[1]
                .replace("\\/", "/") // Limpiamos posibles barras escapadas
            
            // 3. Cargamos el extractor correspondiente para cada link encontrado
            // Esto habilitará servidores como DoodStream, Luluvideo (si están soportados), etc.
            loadExtractor(link, data, subtitleCallback, callback)
        }

        // 4. Búsqueda secundaria: Intentar capturar iframes por si acaso el sitio cambia
        val document = Jsoup.parse(response)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http") && !src.contains("google")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }

}
