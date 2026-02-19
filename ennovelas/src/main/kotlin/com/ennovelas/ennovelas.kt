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
        val items = doc.select(".block-post").mapNotNull { element ->
            val href = element.select("a").attr("href")
            val title = element.select(".title").text()
            val imgElement = element.select("img").first()
            var img = imgElement?.attr("data-img") ?: imgElement?.attr("src") ?: ""
            
            if (img.contains("grey.gif") && element.select(".imgSer").isNotEmpty()) {
                val bgImg = element.select(".imgSer").attr("data-img")
                if (bgImg.isNotEmpty()) {
                    img = bgImg.replace("background-image:url(", "").replace(");", "")
                }
            }
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img)
            }
        }
        
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select(".block-post").mapNotNull { element ->
            val href = element.select("a").attr("href")
            val title = element.select(".title").text()
            val img = element.select("img").attr("data-img").ifEmpty { 
                element.select("img").attr("src") 
            }
            
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(img)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val title = doc.selectFirst("h1")?.text() ?: ""
        val description = doc.selectFirst(".postDesc .post-entry div")?.text() ?: ""
        val poster = doc.selectFirst("img.imgLoaded")?.attr("data-img") ?: 
                    doc.selectFirst("img")?.attr("src") ?: ""
        
        val type = if (url.contains("/movies/") || url.contains("/pelicula/")) 
            TvType.Movie else TvType.TvSeries

        val episodes = if (type == TvType.TvSeries) {
            doc.select(".block-post").mapIndexed { index, element ->
                val epUrl = element.select("a").attr("href")
                val epTitle = element.select(".title").text()
                newEpisode(epUrl) {
                    name = epTitle
                    episode = index + 1
                    season = 1
                }
            }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return false
        
        loadExtractor(iframe, data, subtitleCallback, callback)
        return true
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
