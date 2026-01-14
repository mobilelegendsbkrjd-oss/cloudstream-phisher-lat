package com.sololatino

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas" to "Películas",
        "$mainUrl/series" to "Series",
        "$mainUrl/animes" to "Animes",
        "$mainUrl/genre_series/toons" to "Cartoons"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/$page/").document
        val home = document.select("div.items article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a div.data h3")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(
            this.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                ?.takeIf { it.isNotBlank() }?.substringBefore(" ") 
                ?: this.selectFirst("div.poster img")?.attr("src")
        )
        
        val type = when {
            href.contains("/pelicula") || href.contains("/peliculas/") -> TvType.Movie
            href.contains("/anime") || href.contains("/animes/") -> TvType.Anime
            href.contains("/toons") || href.contains("/genre_series/toons") -> TvType.Cartoon
            else -> TvType.TvSeries
        }
        
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("div.items article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.data h1")?.text()?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.wp-content")?.text()?.trim() ?: ""
        val tags = document.select("div.sgeneros a").map { it.text().trim() }
        val year = Regex("\\b(19|20)\\d{2}\\b").find(description)?.value?.toIntOrNull()
        
        // Determinar tipo de contenido
        val isMovie = url.contains("/pelicula") || url.contains("/peliculas/") || 
                     document.select("#seasons").isEmpty()
        
        if (isMovie) {
            // Para películas, buscar el enlace de reproducción
            val playerUrl = extractPlayerUrl(document)
            return newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            // Para series, extraer episodios
            val episodes = mutableListOf<Episode>()
            
            document.select("div#seasons div.se-c").forEachIndexed { seasonIndex, season ->
                val seasonNumber = seasonIndex + 1
                
                season.select("ul.episodios li").forEach { episodeElement ->
                    val episodeUrl = fixUrl(episodeElement.selectFirst("a")?.attr("href") ?: "")
                    if (episodeUrl.isNotBlank()) {
                        val episodeTitle = episodeElement.selectFirst("div.episodiotitle div.epst")?.text()?.trim() 
                            ?: "Episodio"
                        val numbers = episodeElement.selectFirst("div.episodiotitle div.numerando")?.text()
                            ?.split("-")?.mapNotNull { it.trim().toIntOrNull() }
                        val seasonNum = numbers?.getOrNull(0) ?: seasonNumber
                        val episodeNum = numbers?.getOrNull(1) ?: (episodes.count { it.season == seasonNum } + 1)
                        val episodePoster = fixUrlNull(episodeElement.selectFirst("div.imagen img")?.attr("src"))
                        
                        episodes.add(
                            Episode(
                                data = episodeUrl,
                                name = episodeTitle,
                                season = seasonNum,
                                episode = episodeNum,
                                posterUrl = episodePoster
                            )
                        )
                    }
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    private fun extractPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Buscar iframes de reproductor
        val iframe = document.selectFirst("iframe.metaframe, iframe.rptss")
        if (iframe != null) {
            val src = iframe.attr("src")
            if (src.isNotBlank() && !src.contains("google")) {
                return src
            }
        }
        
        // Buscar en scripts
        val scripts = document.select("script")
        for (script in scripts) {
            val content = script.html()
            val patterns = listOf(
                """src=["'](https?://[^"']+embed[^"']+)["']""".toRegex(),
                """iframe.*?src=["'](https?://[^"']+)["']""".toRegex(),
                """go_to_player\('([^']+)'\)""".toRegex()
            )
            
            for (pattern in patterns) {
                val match = pattern.find(content)
                val url = match?.groupValues?.getOrNull(1)
                if (!url.isNullOrBlank() && !url.contains("google")) {
                    return url
                }
            }
        }
        
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SoloLatino", "Loading links from: $data")
        
        try {
            // Si es una URL directa de reproductor
            if (data.contains("embed") || data.contains("player")) {
                loadExtractor(data, mainUrl, subtitleCallback, callback)
                return true
            }
            
            // Si es una página de episodio/película
            val document = app.get(data).document
            
            // Método 1: Buscar iframes
            val iframes = document.select("iframe.metaframe, iframe.rptss, iframe[src*='embed']")
            iframes.forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("google")) {
                    Log.d("SoloLatino", "Found iframe: $src")
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Método 2: Buscar en contEP (episodios)
            document.select("div.contEP iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank()) {
                    Log.d("SoloLatino", "Found contEP iframe: $src")
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            
            // Método 3: Buscar en scripts
            val scripts = document.select("script")
            for (script in scripts) {
                val content = script.html()
                val embedPattern = """src=["'](https?://[^"']+embed[^"']+)["']""".toRegex()
                embedPattern.findAll(content).forEach { match ->
                    val url = match.groupValues.getOrNull(1)
                    if (!url.isNullOrBlank() && !url.contains("google")) {
                        Log.d("SoloLatino", "Found embed in script: $url")
                        loadExtractor(url, data, subtitleCallback, callback)
                    }
                }
            }
            
            return iframes.isNotEmpty()
        } catch (e: Exception) {
            Log.e("SoloLatino", "Error loading links: ${e.message}")
            return false
        }
    }
}