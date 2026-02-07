package com.cloudstream.providers

import com.cloudstream.*
import com.cloudstream.utils.*
import org.jsoup.nodes.Element

class Novelas360Provider : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article").mapNotNull {
            val title = it.selectFirst("h3 a")?.text() ?: return@mapNotNull null
            val link = it.selectFirst("h3 a")!!.attr("href")
            val poster = it.selectFirst("img")?.attr("src")
            newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")!!.text()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val episodes = doc.select("a[href*='capitulo']").mapNotNull {
            val epUrl = it.attr("href")
            val name = it.text()
            val postId = extractPostId(epUrl) ?: return@mapNotNull null

            Episode(
                data = postId.toString(),
                name = name,
                episode = null,
                season = 1
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val postId = data

        val ajaxResponse = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "mars_load_player",
                "post_id" to postId
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
        ).text

        val iframeUrls = Regex("""src=["'](https?://[^"']+)["']""")
            .findAll(ajaxResponse)
            .map { it.groupValues[1] }
            .toList()

        iframeUrls.forEach { iframe ->
            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }

        return iframeUrls.isNotEmpty()
    }

    private fun extractPostId(url: String): Int? {
        val doc = app.get(url).document
        val bodyClass = doc.selectFirst("body")?.classNames() ?: return null
        return bodyClass.firstOrNull { it.startsWith("postid-") }
            ?.removePrefix("postid-")
            ?.toIntOrNull()
    }
}
