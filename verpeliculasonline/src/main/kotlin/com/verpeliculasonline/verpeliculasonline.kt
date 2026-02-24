package com.verpeliculasonline

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

class VerPeliculasOnline : MainAPI() {
    override var mainUrl = "https://verpeliculasonline.org"
    override var name = "VerPeliculasOnline"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/categoria/peliculas/" to "Películas",
        "/categoria/series/" to "Series",
        "/categoria/accion/" to "Acción",
        "/categoria/comedia/" to "Comedia",
        "/categoria/terror/" to "Terror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(fixUrl(url)).document

        val items = doc.select("article, .movie, .item").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst("h3, h2, .title")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src")
                ?: it.selectFirst("img")?.attr("src")
                ?: it.selectFirst("img")?.attr("data-lazy-src")

            newMovieSearchResponse(
                title.trim(),
                fixUrl(a.attr("href")),
                TvType.Movie
            ) {
                posterUrl = fixUrlNull(poster)
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article, .movie, .item").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst("h3, h2, .title")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("data-src")
                ?: it.selectFirst("img")?.attr("src")
                ?: it.selectFirst("img")?.attr("data-lazy-src")

            newMovieSearchResponse(
                title.trim(),
                fixUrl(a.attr("href")),
                TvType.Movie
            ) {
                posterUrl = fixUrlNull(poster)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = doc.selectFirst(".poster img, .imagen img, meta[property='og:image']")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = doc.selectFirst(".sinopsis, .descripcion, .entry-content p, .wp-block-post-excerpt__excerpt")?.text()

        val episodes = doc.select("a[href*='capitulo'], a[href*='episodio'], .episodios a").mapIndexed { idx, el ->
            newEpisode(fixUrl(el.attr("href"))) {
                name = el.text()
                episode = idx + 1
            }
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Obtener la página para buscar información
            val doc = app.get(data).document
            
            // Intentar hacer solicitud AJAX primero (OPUXA)
            val postId = extractPostId(doc, data)
            
            if (postId.isNotEmpty()) {
                try {
                    val ajaxResponse = app.post(
                        "${mainUrl}/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to postId,
                            "nume" to "1",
                            "type" to "movie"
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    ).parsedSafe<AjaxResponse>()
                    
                    if (ajaxResponse?.embedUrl != null) {
                        val videoUrl = ajaxResponse.embedUrl
                        loadExtractor(fixUrl(videoUrl), data, subtitleCallback, callback)
                        return true
                    }
                } catch (e: Exception) {
                    // Si falla AJAX, continuar con otros métodos
                    e.printStackTrace()
                }
            }
            
            // Buscar iframes directamente en la página
            val iframes = doc.select("iframe, .video-container iframe, .player iframe")
            
            iframes.forEach {
                val src = it.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(
                        fixUrl(src),
                        data,
                        subtitleCallback,
                        callback
                    )
                    return true
                }
            }
            
            // Buscar scripts que puedan contener URLs de video
            doc.select("script").forEach { script ->
                val scriptText = script.html()
                // Buscar URLs de opuxa
                if (scriptText.contains("opuxa")) {
                    val patterns = listOf(
                        "https?:\\/\\/[^\"'\\s]*opuxa[^\"'\\s]*\\/[^\"'\\s]*",
                        "src\\s*=\\s*[\"'](https?:\\/\\/[^\"'\\s]*opuxa[^\"'\\s]*)[\"']"
                    )
                    
                    patterns.forEach { pattern ->
                        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                        regex.findAll(scriptText).forEach { match ->
                            val urlMatch = match.value
                            if (urlMatch.contains("opuxa")) {
                                loadExtractor(fixUrl(urlMatch), data, subtitleCallback, callback)
                                return true
                            }
                        }
                    }
                }
                
                // Buscar URLs comunes de video
                val videoPatterns = listOf(
                    "https?:\\/\\/[^\"'\\s]+\\.(mp4|m3u8|webm)[^\"'\\s]*",
                    "src\\s*=\\s*[\"'](https?:\\/\\/[^\"']+)[\"']",
                    "file\\s*:\\s*[\"'](https?:\\/\\/[^\"']+)[\"']"
                )
                
                videoPatterns.forEach { pattern ->
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    regex.findAll(scriptText).forEach { match ->
                        val videoUrl = match.value
                        if (videoUrl.isNotBlank()) {
                            loadExtractor(
                                fixUrl(videoUrl),
                                data,
                                subtitleCallback,
                                callback
                            )
                            return true
                        }
                    }
                }
            }
            
            // Buscar en meta tags
            doc.select("meta[property='og:video'], meta[property='og:video:url']").forEach {
                val content = it.attr("content")
                if (content.isNotBlank()) {
                    loadExtractor(fixUrl(content), data, subtitleCallback, callback)
                    return true
                }
            }
            
            // Buscar enlaces en la página
            doc.select("a[href*='watch'], a[href*='video'], a[href*='player']").forEach {
                val href = it.attr("href")
                if (href.isNotBlank() && !href.startsWith("#")) {
                    loadExtractor(fixUrl(href), data, subtitleCallback, callback)
                    return true
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return false
    }
    
    private fun extractPostId(doc: org.jsoup.nodes.Document, url: String): String {
        // Intentar extraer el ID del post de diferentes formas
        return doc.selectFirst("link[rel=shortlink]")?.attr("href")
            ?.substringAfter("?p=")
            ?: doc.selectFirst("input[name=post]")?.attr("value")
            ?: doc.selectFirst("meta[name=post_id]")?.attr("content")
            ?: doc.selectFirst("#post_id")?.attr("value")
            ?: url.substringAfter("/pelicula/").substringBefore("/").let { slug ->
                // Si tenemos un slug, podemos intentar buscar el post ID
                if (slug.isNotEmpty()) {
                    // Buscar en scripts
                    val scriptText = doc.select("script").html()
                    val pattern = Regex("post_id\\s*[:=]\\s*['\"]?(\\d+)['\"]?")
                    pattern.find(scriptText)?.groupValues?.get(1) ?: ""
                } else {
                    ""
                }
            }
    }
    
    data class AjaxResponse(
        @JsonProperty("embed_url") val embedUrl: String?,
        @JsonProperty("type") val type: String?
    )
}