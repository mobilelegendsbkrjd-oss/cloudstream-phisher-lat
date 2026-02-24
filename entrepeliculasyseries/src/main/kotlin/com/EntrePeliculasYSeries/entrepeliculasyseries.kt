package com.EntrePeliculasYSeries

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class EntrePeliculasYSeries : MainAPI() {
    override var mainUrl = "https://entrepeliculasyseries.nz"
    override var name = "Entre Películas y Series"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon
    )

    // NO añadas extractorApis aquí - no existe en MainAPI

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episodios",
        "$mainUrl/peliculas" to "Películas Recientes",
        "$mainUrl/series" to "Series Recientes",
        "$mainUrl/animes" to "Animes Recientes",
        "$mainUrl/peliculas/populares" to "Películas Populares",
        "$mainUrl/series/populares" to "Series Populares",
        "$mainUrl/animes/populares" to "Animes Populares"
    )

    private fun getImage(el: Element?): String? {
        el ?: return null
        val attrs = listOf("src", "data-src", "data-lazy-src", "srcset")
        for (attr in attrs) {
            val v = el.attr(attr)
            if (v.isNotBlank() && !v.startsWith("data:image")) {
                return v.split(",").first().trim().split(" ").first()
            }
        }
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document

        val items = document.select("article.post, ul.post-lst li article, li article.post").mapNotNull { item ->
            try {
                val linkElement = item.selectFirst("a")
                val href = linkElement?.attr("href") ?: return@mapNotNull null
                val link = fixUrl(href)

                val titleElement = item.selectFirst(".entry-header h3, .entry-header h2, h3.title, h2.title, h3, h2")
                val title = titleElement?.text()?.trim() ?: return@mapNotNull null

                val imgElement = item.selectFirst(".post-thumbnail img, figure img, img")
                val poster = getImage(imgElement)

                val tvType = when {
                    link.contains("/pelicula") -> TvType.Movie
                    link.contains("/anime") -> TvType.Anime
                    else -> TvType.TvSeries
                }

                newTvSeriesSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                null
            }
        }

        return HomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val document = try {
            app.get("$mainUrl/?s=$encodedQuery").document
        } catch (e: Exception) {
            return emptyList()
        }

        return document.select("article.post, ul.post-lst li article, .post, .item").mapNotNull { item ->
            try {
                val linkElement = item.selectFirst("a")
                val href = linkElement?.attr("href") ?: return@mapNotNull null
                val link = fixUrl(href)

                val titleElement = item.selectFirst(".entry-header h3, .entry-header h2, h3, h2, .title")
                val title = titleElement?.text()?.trim() ?: return@mapNotNull null

                val imgElement = item.selectFirst(".post-thumbnail img, img")
                val poster = getImage(imgElement)

                val tvType = when {
                    link.contains("/pelicula") -> TvType.Movie
                    link.contains("/anime") -> TvType.Anime
                    else -> TvType.TvSeries
                }

                newTvSeriesSearchResponse(title, link, tvType) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // TÍTULO
        val title = document.selectFirst("h1, h2.title, .title, h1.title")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" |")?.substringBefore(" -")
            ?: return null

        // IMAGEN
        var poster: String? = null

        poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")

        if (poster == null || poster.contains("undefined")) {
            poster = getImage(document.selectFirst(".post-thumbnail img"))
                ?: getImage(document.selectFirst(".poster img"))
                        ?: getImage(document.selectFirst(".cover img"))
                        ?: getImage(document.selectFirst("img[src*=tmdb]"))
                        ?: getImage(document.selectFirst("img"))
        }

        // DESCRIPCIÓN
        val description = document.selectFirst(".description, .sinopsis, .plot, .wp-content, p")?.text()?.trim()

        // DETERMINAR TIPO
        val isMovie = url.contains("/pelicula") || url.contains("/peliculas/")
        val isAnime = url.contains("/anime") || url.contains("/animes/")

        val tvType = when {
            isMovie -> TvType.Movie
            isAnime -> TvType.Anime
            else -> TvType.TvSeries
        }

        // OBTENER SUGERENCIAS/RECOMENDACIONES
        val recommendations = mutableListOf<SearchResponse>()

        // Buscar en la página principal para sugerencias
        if (isMovie) {
            // Para películas, buscar películas similares
            val moviesPage = app.get("$mainUrl/peliculas").document
            moviesPage.select("article.post").take(10).forEach { item ->
                val recLink = item.selectFirst("a")
                val recHref = recLink?.attr("href") ?: return@forEach
                if (recHref == url) return@forEach // Saltar la misma película

                val recUrl = fixUrl(recHref)
                val recTitle = item.selectFirst(".entry-header h3, h3")?.text()?.trim() ?: return@forEach
                val recImg = getImage(item.selectFirst("img"))

                recommendations.add(
                    newTvSeriesSearchResponse(recTitle, recUrl, TvType.Movie) {
                        this.posterUrl = recImg
                    }
                )
            }
        } else {
            // Para series, buscar series similares
            val seriesPage = app.get("$mainUrl/series").document
            seriesPage.select("article.post").take(10).forEach { item ->
                val recLink = item.selectFirst("a")
                val recHref = recLink?.attr("href") ?: return@forEach
                if (recHref.contains(url)) return@forEach // Saltar la misma serie

                val recUrl = fixUrl(recHref)
                val recTitle = item.selectFirst(".entry-header h3, h3")?.text()?.trim() ?: return@forEach
                val recImg = getImage(item.selectFirst("img"))

                recommendations.add(
                    newTvSeriesSearchResponse(recTitle, recUrl, TvType.TvSeries) {
                        this.posterUrl = recImg
                    }
                )
            }
        }

        // Si es película
        if (isMovie) {
            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                plot = description
                this.recommendations = recommendations
            }
        }

        // Para series/animes, buscar episodios
        val episodes = mutableListOf<Episode>()

        // Buscar enlaces de episodios
        document.select("a[href*=/capitulo/], a[href*=/episodio/]").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                val epUrl = fixUrl(href)

                val seasonMatch = Regex("temporada/(\\d+)").find(epUrl)
                val epMatch = Regex("capitulo/(\\d+)|episodio/(\\d+)").find(epUrl)

                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = epMatch?.groupValues?.get(1)?.toIntOrNull()

                val epTitle = link.text().trim()
                val name = when {
                    epTitle.isBlank() -> "Episodio ${episode ?: ""}"
                    epTitle == "Ver" || epTitle == "Ver ahora" -> "Episodio ${episode ?: ""}"
                    else -> epTitle
                }

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                    }
                )
            }
        }

        // Si no hay episodios específicos
        if (episodes.isEmpty()) {
            document.select(".episodes-grid .episode-card a, .episodes-list a").forEach { link ->
                val href = link.attr("href")
                if (href.contains("/capitulo/") || href.contains("/temporada/")) {
                    val epUrl = fixUrl(href)

                    val epText = link.text().trim()
                    val epNum = Regex("\\d+").find(epText)?.value?.toIntOrNull()

                    episodes.add(
                        newEpisode(epUrl) {
                            name = if (epText.isBlank()) "Episodio" else epText
                            season = 1
                            episode = epNum
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            plot = description
            this.recommendations = recommendations
        }
    }

    // En la función loadLinks:
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[Main] loadLinks para: $data")
        
        val document = app.get(data).document
        var found = false

        // Función para manejar URLs
        suspend fun handleUrl(url: String) {
            if (url.isBlank()) return

            val finalUrl = if (url.startsWith("//")) "https:$url" else url

            println("[loadLinks] Procesando URL: $finalUrl")

            when {
                finalUrl.contains("embed69", ignoreCase = true) -> {
                    // Usar Embed69Extractor
                    try {
                        Embed69Extractor.load(finalUrl, data, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) {
                        println("[loadLinks] Error con embed69: ${e.message}")
                    }
                }
                finalUrl.contains("xupalace", ignoreCase = true) -> {
                    // Usar XupalaceExtractor
                    try {
                        // Crear instancia y llamar getUrl
                        val extractor = XupalaceExtractor()
                        extractor.getUrl(finalUrl, data, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) {
                        println("[loadLinks] Error con Xupalace: ${e.message}")
                    }
                }
                else -> {
                    // Para otros, usar extractor genérico
                    try {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                        found = true
                    } catch (e: Exception) {
                        println("[loadLinks] Error con extractor genérico: ${e.message}")
                    }
                }
            }
        }

        // 1. Buscar iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                handleUrl(src)
            }
        }

        // 2. Buscar scripts que contengan URLs de video
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            if (scriptContent.contains("go_to_playerVast")) {
                // Extraer URLs del script
                val urlPattern = """go_to_playerVast\('([^']+)'"""
                val matches = Regex(urlPattern).findAll(scriptContent)

                matches.forEach { match ->
                    val url = match.groupValues[1]
                    handleUrl(url)
                }
            }
        }

        // 3. Buscar enlaces directos en botones onclick
        document.select("[onclick*='go_to_playerVast']").forEach { element ->
            val onclick = element.attr("onclick")
            val match = Regex("""go_to_playerVast\('([^']+)'""").find(onclick)
            match?.let {
                val url = it.groupValues[1]
                handleUrl(url)
            }
        }

        // 4. Buscar todos los patrones posibles en el HTML
        val html = document.html()

        val patterns = listOf(
            """go_to_playerVast\('([^']+)'""",
            """src\s*=\s*["']([^"']+xupalace[^"']+)["']""",
            """iframe[^>]+src=["']([^"']+)["']""",
            """https?://[^\s'"]*xupalace[^\s'"]*""",
            """https?://[^\s'"]*vidhide[^\s'"]*""",
            """https?://[^\s'"]*callistanise[^\s'"]*""",
            """https?://[^\s'"]*embed69[^\s'"]*"""
        )

        patterns.forEach { pattern ->
            try {
                Regex(pattern).findAll(html).forEach { match ->
                    val url = match.groupValues.getOrNull(1) ?: match.value
                    if (url.contains("http") &&
                        (url.contains("xupalace") ||
                                url.contains("callistanise") ||
                                url.contains("embed69") ||
                                url.contains("vidhide") ||
                                url.contains(".m3u8") ||
                                url.contains(".mp4"))) {
                        handleUrl(url)
                    }
                }
            } catch (e: Exception) {
                // Ignorar errores de regex
            }
        }

        // 5. Si no se encontró nada, intentar con la URL original
        if (!found) {
            try {
                println("[loadLinks] No se encontraron enlaces, intentando con URL original")
                loadExtractor(data, data, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                println("[loadLinks] Error con URL original: ${e.message}")
            }
        }

        return found
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }
    }
}