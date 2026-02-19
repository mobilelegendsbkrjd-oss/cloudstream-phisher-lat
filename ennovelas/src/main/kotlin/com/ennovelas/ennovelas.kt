package com.ennovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class EnNovelas : MainAPI() {
    override var mainUrl = "https://l.ennovelas-tv.com"
    override var name = "EnNovelas"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/episodes" to "Últimos Capítulos",
        "$mainUrl/series" to "Series",
        "$mainUrl/movies" to "Películas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = when (request.name) {
            "Últimos Capítulos" -> "$mainUrl/episodes"
            "Series" -> "$mainUrl/series"
            "Películas" -> "$mainUrl/movies"
            else -> "$mainUrl/episodes"
        }
        
        val doc = app.get(url).document
        val items = doc.select("#load-post article .block-post, .block-post").mapNotNull { element ->
            elementToSearchResponse(element)
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, items.take(20))))
    }

    private fun elementToSearchResponse(element: Element): SearchResponse? {
        val link = element.select("a").attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = element.select(".title").text().takeIf { it.isNotBlank() } 
            ?: element.select("a").attr("title") ?: return null
        
        // Manejar diferentes tipos de imágenes
        var img = element.select("img").attr("data-img")
        if (img.isBlank()) {
            // Para series, la imagen está en un div con data-img
            val imgSer = element.select(".imgSer").attr("data-img")
            if (imgSer.isNotBlank()) {
                img = imgSer.removePrefix("background-image:url(").removeSuffix(");")
            }
        }
        if (img.isBlank()) {
            img = element.select("img").attr("src")
        }
        
        // Limpiar URL de imagen
        img = img.replace("url(", "").replace(")", "").replace("'", "").replace("\"", "")
        
        // Determinar si es serie o película basado en la URL
        val type = when {
            link.contains("/series/") -> TvType.TvSeries
            link.contains("/movies/") || link.contains("/pelicula/") -> TvType.Movie
            else -> TvType.TvSeries
        }

        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = fixUrlNull(img)
            }
            else -> newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val doc = app.get("$mainUrl/$id").document
            val title = doc.selectFirst("h1")?.text() ?: ""
            return listOf(
                newTvSeriesSearchResponse(title, "$mainUrl/$id", TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(doc.selectFirst("img")?.attr("src"))
                }
            )
        }

        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document
        return doc.select(".block-post").mapNotNull { element ->
            elementToSearchResponse(element)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1, .title, .entry-title")?.text() ?: ""
        val description = doc.selectFirst(".postDesc .post-entry div, .description, .sinopsis")?.text() ?: ""
        
        // Intentar diferentes selectores para el poster
        var poster = doc.selectFirst("img.imgLoaded")?.attr("data-img") ?: ""
        if (poster.isBlank()) poster = doc.selectFirst("img")?.attr("src") ?: ""
        
        val type = when {
            url.contains("/movies/") || url.contains("/pelicula/") -> TvType.Movie
            else -> TvType.TvSeries
        }

        // Obtener episodios para series
        val episodes = if (type == TvType.TvSeries) {
            getEpisodesFromDocument(doc, url)
        } else emptyList()

        return when (type) {
            TvType.Movie -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
            else -> newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }
    }

    private suspend fun getEpisodesFromDocument(document: Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Buscar episodios en la estructura actual
        document.select(".block-post, .episode-item").forEachIndexed { index, element ->
            val epUrl = element.select("a").attr("abs:href").takeIf { it.isNotBlank() } 
                ?: element.select("a").attr("href") ?: return@forEachIndexed
            
            // Solo procesar si es un enlace de episodio
            if (!epUrl.contains("/pro/") && !epUrl.contains("/episodio/") && !epUrl.contains("/episode/")) {
                return@forEachIndexed
            }
            
            val epTitle = element.select(".title, .episode-title").text().takeIf { it.isNotBlank() }
                ?: "Episodio ${index + 1}"
            
            // Extraer número de episodio
            val epNumber = Regex("""(?:capitulo|ep(?:isode)?|cap)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

            // Obtener imagen del episodio
            var epPoster = element.select("img").attr("data-img")
            if (epPoster.isBlank()) {
                epPoster = element.select("img").attr("src")
            }

            episodes.add(
                newEpisode(epUrl) {
                    name = epTitle
                    episode = epNumber
                    season = 1
                    posterUrl = fixUrlNull(epPoster)
                }
            )
        }

        return episodes.distinctBy { it.url }.sortedBy { it.episode }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return coroutineScope {
            try {
                val doc = app.get(data).document
                var hasLinks = false
                
                // Buscar iframes de video
                val iframes = doc.select("iframe[src]").map { it.attr("abs:src") }
                    .filter { it.isNotBlank() && it.contains("embed") }
                
                if (iframes.isNotEmpty()) {
                    val jobs = iframes.map { iframeUrl ->
                        async {
                            loadExtractor(iframeUrl, data, subtitleCallback, callback)
                        }
                    }
                    jobs.awaitAll()
                    hasLinks = true
                }

                // Buscar enlaces directos de video
                doc.select("video source[src], a[href$=.mp4], a[href$=.m3u8]").forEach { element ->
                    val videoUrl = element.attr("abs:src").ifEmpty { element.attr("abs:href") }
                    if (videoUrl.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "direct",
                                "Directo",
                                videoUrl,
                                data
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        hasLinks = true
                    }
                }

                return@coroutineScope hasLinks
            } catch (e: Exception) {
                e.printStackTrace()
                return@coroutineScope false
            }
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
