package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Novelas : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/categories/mexico/" to "Novelas MX"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val doc = app.get(request.data).document

        val items = doc.select(".items .item")

        val home = items.mapNotNull { item ->

            val title = item.selectFirst("h3")?.text() ?: return@mapNotNull null
            val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = item.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(title, link) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select(".items .item").mapNotNull {

            val title = it.selectFirst("h3")?.text() ?: return@mapNotNull null
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(title, link) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: "Novela"
        val poster = doc.selectFirst(".poster img")?.attr("src")

        val episodes = doc.select("a[href*=/video/]")

        val episodeList = episodes.mapIndexedNotNull { index, ep ->

            val epUrl = ep.attr("href")
            val name = ep.text()

            newEpisode(epUrl) {
                this.name = name
                this.episode = index + 1
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeList
        ) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val extractor = ExtractorNovelas360()

        val links = extractor.getUrl(data, null) ?: return false

        links.forEach {
            callback.invoke(it)
        }

        return true
    }
}
