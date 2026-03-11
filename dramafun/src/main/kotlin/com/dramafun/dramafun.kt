package com.dramafun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DramaFun : MainAPI() {

    override var mainUrl = "https://ww6.dramafuntv.com"
    override var name = "DramaFun"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(TvType.TvSeries)

    private suspend fun getDoc(url: String): Document {
        return app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        ).document
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("/")) mainUrl + url else url
    }

    // MAIN PAGE

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = getDoc(mainUrl)

        val items = doc.select(".item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList("Latest", items)),
            false
        )
    }

    // SEARCH

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = getDoc("$mainUrl/search.php?keyword=$query")

        return doc.select(".item").mapNotNull { it.toSearchResult() }
    }

    // LOAD SERIES

    override suspend fun load(url: String): LoadResponse {

        val doc = getDoc(url)

        val title = doc.selectFirst("h1")?.text() ?: "Drama"

        val poster = doc.selectFirst("img")?.attr("src")

        val episodes = doc.select("a[href*=\"watch.php\"]")
            .mapNotNull {

                val link = fixUrl(it.attr("href")) ?: return@mapNotNull null

                newEpisode(link) {
                    name = it.text()
                }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
        }
    }

    // LOAD VIDEO

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = getDoc(data)

        doc.select("iframe").forEach {

            val src = it.attr("src")

            if (src.contains("vk.com")) {

                loadExtractor(
                    src,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }

    // PARSER

    private fun Element.toSearchResult(): SearchResponse? {

        val link = selectFirst("a")?.attr("href") ?: return null

        val title = selectFirst("img")?.attr("alt") ?: return null

        val poster = selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(
            title,
            fixUrl(link)!!
        ) {
            posterUrl = poster
        }
    }
}