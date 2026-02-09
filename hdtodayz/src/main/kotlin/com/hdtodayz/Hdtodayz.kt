package com.hdtodayz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Hdtodayz : MainAPI() {

    override var mainUrl = "https://hdtodayz.to"
    override var name = "HDTodayz"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "home" to "Inicio",
        "movie" to "Películas",
        "tv-show" to "Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}?page=$page"
        val document = app.get(url).document

        val items = document.select("div.film_list-wrap div.flw-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = a.attr("title")
        val href = a.attr("href")
        val poster = selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src")

        val type = if (href.contains("/tv/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/$query").document
        return document.select("div.film_list-wrap div.flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h2")?.text() ?: "Desconocido"
        val poster = document.selectFirst("div.film-poster img")?.attr("src")
        val plot = document.selectFirst("div.description")?.text()

        val watchUrl = document.selectFirst("a.btn-play")?.attr("href")
            ?: document.selectFirst("a[href*='/watch']")?.attr("href")
            ?: url

        val isMovie = url.contains("/movie/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, fixUrl(watchUrl)) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        } else {
            val watchDoc = app.get(fixUrl(watchUrl)).document

            val episodes = watchDoc
                .select("#content-episodes a")
                .map {
                    val epUrl = it.attr("href")
                    val epName = it.text().ifBlank { "Episodio" }

                    newEpisode(fixUrl(epUrl)) {
                        this.name = epName
                    }
                }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = fixUrlNull(poster)
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

        val document = app.get(data).document

        val iframe = document.selectFirst("#vs-vid iframe")?.attr("src")

        if (!iframe.isNullOrBlank()) {
            loadExtractor(
                fixUrl(iframe),
                mainUrl,
                subtitleCallback,
                callback
            )
            return true
        }

        document.select("[data-link]").forEach {
            val link = it.attr("data-link")
            if (link.isNotBlank()) {
                loadExtractor(
                    fixUrl(link),
                    mainUrl,
                    subtitleCallback,
                    callback
                )
            }
        }
        return true
    }
}
