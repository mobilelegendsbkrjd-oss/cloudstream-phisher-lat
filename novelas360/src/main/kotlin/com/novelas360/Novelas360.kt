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

    // ==============================
    // HTTP SAFE
    // ==============================
    private suspend fun getDoc(url: String): Document? =
        runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
                    "Referer" to mainUrl
                )
            ).document
        }.getOrNull()

    private fun fixUrl(url: String?): String? =
        if (url.isNullOrBlank()) null
        else if (url.startsWith("//")) "https:$url" else url

    // ==============================
    // MAIN PAGE (CATEGORÍAS)
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val categories = listOf(
            "Telenovelas México" to "/telenovelas/mexico/",
            "Telenovelas Turcas" to "/telenovelas/turcas/",
            "Telenovelas Colombianas" to "/telenovelas/colombianas/",
            "Telenovelas Brasileñas" to "/telenovelas/brasilenas/",
            "Telenovelas Argentinas" to "/telenovelas/argentinas/"
        )

        val home = categories.mapNotNull { (title, path) ->
            val doc = getDoc("$mainUrl$path") ?: return@mapNotNull null
            
            // INTENTO 1: Buscar por estructura de grilla Bootstrap (muy común en temas WP)
            // Busca divs que tengan clases tipo 'col-md-3', 'col-sm-4', etc, o la clase específica 'video-item'
            val items = doc.select("div[class*='col-'], .video-item, article, .post")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url } // Evitar duplicados

            if (items.isEmpty()) null else HomePageList(title, items)
        }

        return newHomePageResponse(home, false)
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDoc("$mainUrl/?s=$query") ?: return emptyList()

        // Usamos el mismo selector robusto que en main page
        return document.select("div[class*='col-'], .video-item, article, .post, .search-result")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ==============================
    // PARSER (Detecta Título e Imagen automáticamente)
    // ==============================
    private fun Element.toSearchResult(): SearchResponse? {
        // Buscar el enlace principal (a veces es directo, a veces hijo)
        val link = selectFirst("a") ?: return null
        val href = link.attr("href")
        if (href.isBlank() || href.contains("/page/")) return null // Evitar paginación

        // Buscar Título: Prioridad h3 -> h2 -> title attr -> img alt
        val title = selectFirst("h3, h2, .entry-title, .title")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null

        // Buscar Imagen
        val img = selectFirst("img")
        val poster = fixUrl(
            img?.attr("data-src")?.ifBlank { img.attr("src") }
            ?: img?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ==============================
    // LOAD SERIE
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url) ?: throw ErrorLoadingException()

        // Título de la serie
        val title = document.selectFirst("h1, h2.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("–")?.trim()
            ?: "Novela"

        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val poster = fixUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        // Lista de Episodios: Busca enlaces que parezcan episodios dentro del contenido
        // Filtra para asegurar que sean enlaces internos relevantes
        val episodesAsc = document.select(".entry-content a, .post-content a, div.item h3 a")
            .filter { 
                val href = it.attr("href")
                href.contains("/video/") || href.contains("capitulo") 
            }
            .distinctBy { it.attr("href") }
            .mapIndexedNotNull { index, el ->
                val epUrl = el.attr("href")
                val epName = el.text().trim()
                
                if (epUrl.isBlank()) return@mapIndexedNotNull null

                newEpisode(epUrl) {
                    name = epName.ifBlank { "Capítulo ${index + 1}" }
                    episode = index + 1
                }
            }

        // Ordenamos descendente (más nuevo primero)
        val episodes = episodesAsc.reversed()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    // ==============================
    // LOAD LINKS
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = getDoc(data) ?: return false

        // Buscamos iframes comunes
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src")) ?: return@forEach
            
            // Carga directa con Cloudstream (Maneja Okru, Dailymotion, Netu automáticamente)
            loadExtractor(src, data, subtitleCallback, callback)
        }
        
        return true
    }
}
