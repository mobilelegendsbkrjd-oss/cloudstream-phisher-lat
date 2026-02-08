package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.com"
    override var name = "Novelas360"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ================= MAIN PAGE =================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1)
            "$mainUrl/telenovelas/mexico/"
        else
            "$mainUrl/telenovelas/mexico/page/$page/"

        val document = app.get(url).document

        val items = document.select("article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(HomePageList("Telenovelas México", items)),
            hasNext = true
        )
    }

    // ================= SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article")
            .mapNotNull { it.toSearchResult() }
    }

    // ================= LOAD =================

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            ?: "Novela"

        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")

        val plot = document.selectFirst(".entry-content p")
            ?.text()

        val episodes = listOf(
            newEpisode(url) {
                name = "Reproducir"
            }
        )

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            this.plot = plot
        }
    }

    // ================= LINKS (AJAX) =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        val postId = document
            .selectFirst("[id^=post-]")
            ?.id()
            ?.removePrefix("post-")
            ?: return false

        val ajaxHtml = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "mars_load_video_player",
                "post_id" to postId
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data
            )
        ).text

        val ajaxDoc = Jsoup.parse(ajaxHtml)

        ajaxDoc.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.isNotBlank()) {
                if (src.startsWith("//")) src = "https:$src"
                loadExtractor(
                    fixUrl(src)!!,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }

    // ================= HELPERS =================

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("h2 a, h3 a") ?: return null
        val title = link.text()
        val href = link.attr("href")

        val poster = selectFirst("img")
            ?.attr("data-src")
            ?.ifBlank { selectFirst("img")?.attr("src") }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }
}
