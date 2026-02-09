package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Tlnovelas : MainAPI() {

    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "es"

    // ==============================
    // HOME
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get("$mainUrl/gratis/telenovelas/").document

        val items = doc.select(".vk-poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = "Telenovelas",
                    list = items
                )
            ),
            hasNext = false
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = selectFirst(".vk-info p")?.text()?.trim() ?: return null
        val href = a.attr("href")
        val poster = selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    // ==============================
    // SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/?q=${query.replace(" ", "+")}"
        val doc = app.get(url).document

        return doc.select(".vk-poster").mapNotNull { it.toSearchResult() }
    }

    // ==============================
    // LOAD SERIE
    // ==============================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title")

        val poster = doc.selectFirst(".vk-imagen img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst(".card-text")?.text()

        val episodes = doc.select("a[href*=\"/ver/\"]").mapIndexedNotNull { index, el ->
            val epUrl = el.attr("href")
            val epName = el.text().ifBlank { "Capítulo ${index + 1}" }

            newEpisode(
                epUrl,
                epName,
                episode = index + 1
            )
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot = description
        }
    }

    // ==============================
    // LINKS / VIDEO
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = app.get(data).text

        // 1️⃣ URLs directas (streamwish, dood, lulu, etc)
        Regex("""e\[\d+]\s*=\s*['"](https?://[^'"]+)['"]""")
            .findAll(html)
            .forEach {
                loadExtractor(
                    it.groupValues[1],
                    data,
                    subtitleCallback,
                    callback
                )
            }

        // 2️⃣ Blogger tokens → Marimar FIX
        Regex("""e\[\d+]\s*=\s*['"]([a-zA-Z0-9_-]+)\|\d+['"]""")
            .findAll(html)
            .forEach {
                val token = it.groupValues[1]
                val bloggerUrl = "https://www.blogger.com/video.g?token=$token"

                callback(
                    newExtractorLink(
                        source = "Blogger",
                        name = "Blogger",
                        url = bloggerUrl,
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to USER_AGENT
                        )
                    )
                )
            }

        // 3️⃣ Iframes normales
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach {
                loadExtractor(
                    it.groupValues[1],
                    data,
                    subtitleCallback,
                    callback
                )
            }

        return true
    }
}
