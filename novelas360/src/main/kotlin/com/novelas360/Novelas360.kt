package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {
    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Usamos la URL que me pasaste donde se ven las novelas de México
        val url = if (page <= 1) "$mainUrl/telenovelas/mexico/" else "$mainUrl/telenovelas/mexico/page/$page/"
        val document = app.get(url).document
        
        // En tu HTML, cada novela está dentro de un <article>
        val items = document.select("article").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Selector basado EXACTAMENTE en tu HTML de 'Mexico':
        // El link y título están en: <div class="post-header"><h2><a href="...">Título</a></h2></div>
        val titleElement = this.selectFirst(".post-header h2 a, h2 a, h3 a")
        val title = titleElement?.text() ?: return null
        val href = titleElement?.attr("href") ?: return null
        
        // Imagen: El sitio usa data-src para el Lazy Load. Si no lo ponemos, sale el cuadro vacío o gris.
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        // En búsqueda, WordPress usa la misma estructura de <article>
        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // En la página del video, el título es un h1 con clase entry-title
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""

        // Como el sitio no tiene lista de capítulos (cada post es un capítulo), creamos uno solo
        val episodes = listOf(
            newEpisode(url) {
                name = "Reproducir Video"
            }
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = document.selectFirst("meta[property='og:image']")?.attr("content")
            this.plot = document.selectFirst(".entry-content p")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Buscamos todos los iframes. 
        // Tu primer HTML mostró que usan Dailymotion dentro de un iframe.
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            
            if (src.isNotEmpty()) {
                // Arreglar links tipo //www.dailymotion.com/...
                if (src.startsWith("//")) src = "https:$src"
                
                // Evitamos cargar basura de redes sociales
                if (src.contains("dailymotion.com") || src.contains("ok.ru") || src.contains("netu") || src.contains("embed")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
