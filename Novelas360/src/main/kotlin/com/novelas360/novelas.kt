package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Novelas : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(TvType.TvSeries)

    private val chromeUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

    private suspend fun getDoc(url: String): Document {
        return app.get(
            url,
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to mainUrl
            )
        ).document
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = getDoc("$mainUrl/telenovelas/mexico/")

        val items = document
            .select("div.tabcontent#Todos > a")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val document = getDoc("$mainUrl/?s=$query")

        return document.select(".video-item").mapNotNull { item ->

            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3")?.text() ?: return@mapNotNull null

            newTvSeriesSearchResponse(title, link.attr("href"))
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = getDoc(url)

        val title = doc.selectFirst("h4 span")?.text() ?: "Novela"

        val episodes = doc.select("div.item h3 a").map {
            newEpisode(it.attr("href")) {
                name = it.text()
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.reversed()
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = getDoc(data)

        document.select("iframe").forEach { iframe ->

            val src = iframe.attr("src")

            if (src.contains("novelas360.cyou") || src.contains("cyfs")) {

                val extractor = ExtractorNovelas360()

                extractor.getUrl(src, data)?.forEach {
                    callback.invoke(it)
                }
            }

            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val href = attr("href")
        val title = selectFirst("span.tabcontentnom")?.text() ?: return null

        return newTvSeriesSearchResponse(title, href)
    }
}
