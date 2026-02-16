package com.pandrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Pandrama : MainAPI() {

    override var mainUrl = "https://pandrama.tv"
    override var name = "Pandrama"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
    )

    @Suppress("DEPRECATION")
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = coroutineScope {

        val sections = listOf(
            "🎬 Películas" to "$mainUrl/peliculas",
            "📺 Series" to "$mainUrl/series",
            "💕 Doramas" to "$mainUrl/doramas",
            "🔥 Estrenos" to mainUrl
        )

        val items = ArrayList<HomePageList>()

        sections.chunked(3).forEach { batch ->
            val batchItems = batch.map { (sectionName, url) ->
                async {
                    try {
                        val finalUrl = if (page > 1) "$url/page/$page/" else url
                        val doc = app.get(finalUrl, timeout = 30).document

                        val tvType = when {
                            sectionName.contains("Películas") -> TvType.Movie
                            sectionName.contains("Doramas") -> TvType.AsianDrama
                            else -> TvType.TvSeries
                        }

                        val home = doc.select("article.item, div.items article").mapNotNull {
                            val title = it.selectFirst("h3, h2")?.text() ?: return@mapNotNull null
                            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                            val img = it.selectFirst("img")?.attr("data-src")
                                ?: it.selectFirst("img")?.attr("src")

                            newTvSeriesSearchResponse(title, link, tvType, true) {
                                this.posterUrl = img
                            }
                        }

                        if (home.isNotEmpty()) HomePageList(sectionName, home) else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            items.addAll(batchItems)
        }

        return@coroutineScope newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get("$mainUrl/?s=$query").document

            doc.select("article.item, div.items article").mapNotNull {
                val title = it.selectFirst("h3, h2")?.text() ?: return@mapNotNull null
                val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-src")
                    ?: it.selectFirst("img")?.attr("src")

                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = img
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val isMovie = url.contains("/pelicula") || url.contains("/movie")

        val title = doc.selectFirst("h1")?.text() ?: return null
        val poster = doc.selectFirst("img")?.attr("data-src")
            ?: doc.selectFirst("img")?.attr("src")

        val description = doc.selectFirst("div.entry-content, div.description")?.text() ?: ""

        val backImage = poster

        val episodes = if (!isMovie) {
            doc.select("ul.episodios li, div.episodes li").mapNotNull {
                val epUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = it.text()

                newEpisode(epUrl) {
                    name = epTitle
                }
            }
        } else emptyList()

        return if (!isMovie) {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backImage
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backImage
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {

        try {
            val doc = app.get(data).document
            val iframe = doc.selectFirst("iframe")?.attr("src") ?: return@coroutineScope false

            loadExtractor(iframe, data, subtitleCallback, callback)

        } catch (e: Exception) {}

        return@coroutineScope true
    }
}
