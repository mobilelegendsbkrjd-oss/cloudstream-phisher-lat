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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TIME_OUT = 30L

    override val mainPage = mainPageOf(
        "$mainUrl" to "Últimos Episodios",
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series"
    )

    private fun getImage(el: Element?): String? {
        el ?: return null
        // Prioridad 1: src (que en tu HTML de búsqueda ya trae el link de TMDB)
        val src = el.attr("src")
        if (src.contains("image.tmdb.org")) return src

        // Prioridad 2: srcset (para calidad alta si existe)
        return el.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            ?: el.attr("data-src").ifBlank { null }
            ?: src
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url, timeout = TIME_OUT).document
        val items = document.select("article.post, .post-lst li article").mapNotNull { item ->
            val href = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            newTvSeriesSearchResponse(item.selectFirst(".title, h3")?.text()?.trim() ?: "", fixUrl(href), TvType.TvSeries) {
                this.posterUrl = getImage(item.selectFirst("img"))
            }
        }
        return HomePageResponse(listOf(HomePageList(request.name, items)), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query.trim().replace(" ", "+")}"
        val document = app.get(url).document

        return document.select("ul.post-lst li article, article.post").mapNotNull { item ->
            val linkEl = item.selectFirst("a") ?: return@mapNotNull null
            val href = linkEl.attr("href") ?: return@mapNotNull null
            val title = item.selectFirst(".title, h2, h3")?.text()?.trim() ?: return@mapNotNull null

            val type = if (href.contains("/pelicula/")) TvType.Movie else TvType.TvSeries

            newTvSeriesSearchResponse(title, fixUrl(href), type) {
                // Buscamos la imagen específicamente dentro del artículo actual
                val imgElement = item.selectFirst("img")
                this.posterUrl = getImage(imgElement)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        // --- REDIRECCIÓN INTELIGENTE ---
        val isEpisodeUrl = url.contains("/temporada/")
        val cleanUrl = if (isEpisodeUrl) url.substringBefore("/temporada/").removeSuffix("/") else url

        val document = app.get(cleanUrl, timeout = TIME_OUT).document
        val title = document.selectFirst("h1.movie-title, h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".movie-poster img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val plotSum = document.selectFirst(".movie-description, .description")?.text()?.trim()

        val isMovie = cleanUrl.contains("/pelicula")
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        // Recomendaciones
        val recommendations = document.select(".post-lst li article").mapNotNull { item ->
            val recHref = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recTitle = item.selectFirst(".title, h4")?.text()?.trim() ?: ""
            newTvSeriesSearchResponse(recTitle, fixUrl(recHref), TvType.TvSeries) {
                this.posterUrl = getImage(item.selectFirst("img"))
            }
        }

        if (isMovie) {
            return newMovieLoadResponse(title, cleanUrl, tvType, cleanUrl) { // Aquí mandamos la URL para loadLinks
                this.posterUrl = poster
                this.plot = plotSum
                this.recommendations = recommendations
            }
        }

        // --- EPISODIOS ---
        val episodeElements = document.select(".episodes-grid .episode-card a")
        val fastMode = episodeElements.size > 12

        val episodes = episodeElements.amap { link ->
            val epHref = fixUrl(link.attr("href"))
            val epNum = Regex("capitulo/(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("temporada/(\\d+)").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            if (fastMode) {
                newEpisode(epHref) { // epHref es vital para que loadLinks funcione luego
                    this.episode = epNum
                    this.season = seasonNum
                    this.name = "Episodio $epNum"
                }
            } else {
                val (cleanName, epDescription, epThumb) = try {
                    val epDoc = app.get(epHref, timeout = 10L).document
                    val epTitleFull = epDoc.selectFirst(".player-section p")?.text()?.trim() ?: ""
                    Triple(
                        epTitleFull.replace(Regex("(?i)Episodio\\s*\\d+\\s*:\\s*"), ""),
                        epDoc.selectFirst(".player-section h2")?.text()?.trim(),
                        epDoc.selectFirst("meta[property='og:image']")?.attr("content")
                    )
                } catch (e: Exception) {
                    Triple("Episodio $epNum", null, null)
                }

                newEpisode(epHref) {
                    this.episode = epNum
                    this.season = seasonNum
                    this.name = cleanName
                    this.description = epDescription
                    this.posterUrl = epThumb
                }
            }
        }

        return newTvSeriesLoadResponse(title, cleanUrl, tvType, episodes.sortedBy { it.episode }) {
            this.posterUrl = poster
            this.plot = plotSum
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data aquí debe ser la URL del episodio o película para encontrar los iframes
        val document = app.get(data, timeout = TIME_OUT).document
        var found = false
        val html = document.html()

        // Buscamos en los scripts de reproductores
        Regex("""go_to_playerVast\('([^']+)'""").findAll(html).forEach {
            val link = it.groupValues[1]
            if (link.contains("embed69")) {
                Embed69Extractor.load(link, data, subtitleCallback, callback)
                found = true
            } else {
                if (loadExtractor(link, data, subtitleCallback, callback)) found = true
            }
        }

        // Buscamos en iframes directos
        document.select("iframe").forEach {
            val src = it.attr("src")
            if (src.contains("embed69")) {
                Embed69Extractor.load(src, data, subtitleCallback, callback)
                found = true
            } else if (loadExtractor(src, data, subtitleCallback, callback)) {
                found = true
            }
        }

        return found
    }

    private fun fixUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> mainUrl + (if (url.startsWith("/")) "" else "/") + url
    }
}