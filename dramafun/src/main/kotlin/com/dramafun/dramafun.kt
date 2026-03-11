package com.dramafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class dramafun : MainAPI() {

    override var mainUrl = "https://ww6.dramafuntv.com"
    override var name = "DramaFun"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    // ================================
    // HOME
    // ================================

    override val mainPage = mainPageOf(
        "$mainUrl/category.php?cat=Novelas-y-Telenovelas-Completas&page=" to "Novelas",
        "$mainUrl/newvideos.php?page=" to "Nuevos",
        "$mainUrl/topvideos.php?page=" to "Populares"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = request.data + page
        val document = app.get(url).document

        val home = document.select("ul#pm-grid li").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    // ================================
    // SEARCH
    // ================================

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/search.php?keywords=${query}"
        val document = app.get(url).document

        return document.select("ul#pm-grid li").mapNotNull {
            it.toSearchResult()
        }
    }

    // ================================
    // LOAD
    // ================================

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title =
            document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()

        val poster =
            document.selectFirst("meta[property=og:image]")?.attr("content")

        val iframe =
            document.selectFirst("iframe")?.attr("src")

        val plot =
            document.selectFirst("meta[name=description]")?.attr("content")

        val episode = newEpisode(
            url,
        ) {
            this.name = title
            this.posterUrl = poster
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            listOf(episode)
        ) {
            posterUrl = poster
            plot = plot
        }
    }

    // ================================
    // LINKS
    // ================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val iframe =
            document.selectFirst("iframe")?.attr("src")

        if (iframe != null) {

            loadExtractor(
                iframe,
                mainUrl,
                subtitleCallback,
                callback
            )

        }

        return true
    }

    // ================================
    // PARSER
    // ================================

    private fun Element.toSearchResult(): SearchResponse? {

        val link =
            this.selectFirst("a[href*=watch.php]")?.attr("href")
                ?: return null

        val title =
            this.selectFirst(".caption h3 a")?.text()
                ?: return null

        val poster =
            this.selectFirst("img")?.attr("data-echo")

        return newMovieSearchResponse(
            title,
            fixUrl(link),
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }
}
