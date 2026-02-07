package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {
    // Usaremos la sección de México como principal ya que tiene el listado limpio
    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Si la principal falla, apuntamos directamente a la categoría donde están las novelas
        val url = if (page <= 1) "$mainUrl/telenovelas/mexico/" else "$mainUrl/telenovelas/mexico/page/$page/"
        val document = app.get(url).document
        
        // En Videotube, los items suelen estar en 'article' o divs con clase 'item-video'
        val items = document.select("article, .item-video, .video-item").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(
            listOf(HomePageList("Telenovelas", items)),
            true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Selector preciso basado en tu HTML: el título está en un h3 o h2 dentro de la clase post-header o entry-title
        val titleElement = this.selectFirst("h2 a, h3 a, .post-header h2 a, .entry-title a")
        val title = titleElement?.text() ?: return null
        val href = titleElement?.attr("href") ?: return null
        
        // Imagen: El sitio usa 'post-thumbnail'. Buscamos src o data-src por si hay Lazy Load
        val img = this.selectFirst(".post-thumbnail img, img")
        val posterUrl = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: img?.attr("src")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article, .item-video").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Título principal de la página
        val title = document.selectFirst("h1.entry-title, .post-header h1")?.text()?.trim() ?: ""

        // Creamos un episodio único que apunta a la misma URL (donde está el reproductor)
        val episodes = listOf(
            newEpisode(url) {
                name = "Ver Capítulo / Novela"
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
        
        // Buscamos todos los iframes de video (Dailymotion, Netu, etc)
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            
            if (src.isNotEmpty()) {
                if (src.startsWith("//")) src = "https:$src"
                
                // Excluimos widgets de redes sociales para no perder tiempo
                if (!src.contains("facebook.com") && !src.contains("twitter.com") && !src.contains("google.com")) {
                    // loadExtractor intentará resolver automáticamente Dailymotion y otros
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
