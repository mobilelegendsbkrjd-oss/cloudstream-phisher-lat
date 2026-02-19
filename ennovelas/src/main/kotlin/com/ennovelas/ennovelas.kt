package com.ennovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    private fun elementToSearchResponse(element: Element): SearchResponse? {
        val link = element.select("a").attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = element.select(".title").text().takeIf { it.isNotBlank() } 
            ?: element.select("a").attr("title") ?: return null
        
        var img = element.select("img").attr("data-img")
        if (img.isBlank()) {
            img = element.select("img").attr("src")
        }
        if (img.isBlank()) {
            val imgSer = element.select(".imgSer").attr("data-img")
            if (imgSer.isNotBlank()) {
                img = imgSer.replace("background-image:url(", "").replace(");", "")
            }
        }
        
        val type = if (link.contains("/movies/") || link.contains("/pelicula/")) 
            TvType.Movie else TvType.TvSeries

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
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document
        return doc.select(".block-post").mapNotNull { element ->
            elementToSearchResponse(element)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1")?.text() ?: ""
        val description = doc.selectFirst(".postDesc")?.text() ?: ""
        val poster = doc.selectFirst(".poster img")?.attr("src") ?: ""
        val year = doc.select("ul.postlist li .getMeta a[href*='/years/']").text()
            .filter { it.isDigit() }.toIntOrNull()
        
        val type = if (url.contains("/movies/") || url.contains("/pelicula/")) 
            TvType.Movie else TvType.TvSeries

        val episodes = if (type == TvType.TvSeries) {
            doc.select("ul.eplist a.epNum").map { element ->
                val epUrl = element.attr("href")
                val epNum = element.select("span").text().toIntOrNull() ?: 1
                newEpisode(epUrl) {
                    name = "Episodio $epNum"
                    episode = epNum
                    season = 1
                }
            }
        } else emptyList()

        return when (type) {
            TvType.Movie -> newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.year = year
            }
            else -> newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Buscar el embed URL en los meta tags
        val embedUrl = doc.select("meta[property='og:video:url']").attr("content")
            .ifEmpty { doc.select("meta[property='og:video:secure_url']").attr("content") }
            .ifEmpty { doc.select("link[itemprop='embedURL']").attr("href") }
        
        if (embedUrl.isNotBlank()) {
            // Cargar el embed URL directamente
            loadExtractor(embedUrl, data, subtitleCallback, callback)
            return true
        }
        
        // Si no hay meta tags, buscar iframe
        val iframe = doc.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) {
            loadExtractor(iframe, data, subtitleCallback, callback)
            return true
        }
        
        return false
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
