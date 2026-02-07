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
        // Manejo de paginación: la página 1 es la base, las demás /page/n/
        val url = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        
        // Selector universal para los artículos en Novelas360
        val items = document.select("article[class*='post-'], article.item-video").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(
            listOf(HomePageList("Últimas Actualizaciones", items)),
            true // Mantenemos true para que permita seguir cargando páginas
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Buscamos el título y link. El sitio a veces usa .post-header y otras veces .entry-title
        val titleElement = this.selectFirst(".post-header h2 a, .entry-title a, h2 a")
        val title = titleElement?.text() ?: return null
        val href = titleElement?.attr("href") ?: return null
        
        // Imagen: Priorizamos el atributo src de la imagen dentro del thumbnail
        val posterUrl = this.selectFirst(".post-thumbnail img, img")?.attr("src")

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        
        return document.select("article[class*='post-']").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Título de la novela o capítulo
        val title = document.selectFirst("h1.entry-title, .post-header h1")?.text()?.trim() ?: "Sin Título"

        // En este sitio, cada página suele ser un capítulo individual
        val episodes = listOf(
            newEpisode(url) {
                name = "Ver Capítulo"
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
        
        // Buscamos todos los iframes (donde se alojan Dailymotion, Netu, etc.)
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            
            if (src.isNotEmpty() && !src.contains("facebook.com") && !src.contains("twitter.com")) {
                // Corregir protocolos relativos (//dominio.com -> https://dominio.com)
                if (src.startsWith("//")) src = "https:$src"
                
                // loadExtractor se encarga de llamar al extractor de Dailymotion, Netu, etc.
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
