package com.dramafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DramaFun : MainAPI() {

    override var mainUrl = "https://ww6.dramafuntv.com"
    override var name = "DramaFun"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/category.php?cat=Novelas-y-Telenovelas-Completas&page=" to "Novelas",
        "$mainUrl/newvideos.php?page=" to "Nuevos",
        "$mainUrl/topvideos.php?page=" to "Populares"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(request.data + page).document

        val home = document.select("ul#pm-grid li").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val document =
            app.get("$mainUrl/search.php?keywords=$query").document

        return document.select("ul#pm-grid li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title =
            document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?: document.title()

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")

        val description =
            document.selectFirst("meta[name=description]")
                ?.attr("content")

        val episode = newEpisode(url)

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            listOf(episode)
        ) {
            posterUrl = poster
            plot = description
        }
    }

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

    private fun Element.toSearchResult(): SearchResponse? {

        val link =
            this.selectFirst("a[href*=watch.php]")
                ?.attr("href")
                ?: return null

        val title =
            this.selectFirst(".caption h3 a")
                ?.text()
                ?: return null

        val poster =
            this.selectFirst("img")
                ?.attr("data-echo")

        return newMovieSearchResponse(
            title,
            fixUrl(link),
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }
}
