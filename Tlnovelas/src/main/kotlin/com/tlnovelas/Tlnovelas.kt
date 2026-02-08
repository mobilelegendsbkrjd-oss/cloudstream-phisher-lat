package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Tlnovelas : MainAPI() {
    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "" to "Últimos Capítulos",
        "gratis/telenovelas/" to "Ver Telenovelas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url =
            if (page <= 1) "$mainUrl/${request.data}"
            else "$mainUrl/${request.data}/page/$page"

        val document = app.get(url).document
        val home = document.select(".vk-poster, .p-content, .ani-card, .ani-txt")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        var title = this.selectFirst(".vk-info p, .p-title, .ani-txt")?.text()
            ?: this.selectFirst("a")?.attr("title") ?: ""

        var href = this.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = this.selectFirst("img")?.attr("src")

        if (href.contains("/ver/")) {
            title = title.split(Regex("(?i)Capitulo|Capítulo"))[0].trim()
            val slug = href.removeSuffix("/").substringAfterLast("/")
                .replace(Regex("(?i)-capitulo-\\d+"), "")
                .replace(Regex("(?i)-capítulo-\\d+"), "")
            href = "$mainUrl/novela/$slug/"
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val novelaLink = document.selectFirst("a[href*='/novela/']")?.attr("href")
        val finalDoc =
            if (novelaLink != null && url.contains("/ver/"))
                app.get(novelaLink).document
            else document

        val title = finalDoc.selectFirst("h1.card-title, .vk-title-main, h1")?.text()
            ?.replace(Regex("(?i)Capitulos de|Ver"), "")
            ?.trim() ?: "Telenovela"

        val poster = finalDoc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: finalDoc.selectFirst(".ani-img img")?.attr("src")

        val description =
            finalDoc.selectFirst(".card-text, .ani-description")?.text()

        val episodes = finalDoc.select("a[href*='/ver/']").mapNotNull {
            val epHref = it.attr("href")
            val epName = it.text()
                .replace(title, "", ignoreCase = true)
                .replace(Regex("(?i)Ver|Capitulo|Capítulo"), "")
                .trim()

            newEpisode(epHref) {
                this.name =
                    if (epName.isEmpty()) "Capítulo"
                    else "Capítulo $epName"
            }
        }.distinctBy { it.data }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response = app.get(data).text

        // Playerwish / JS arrays e[0], e[1]...
        val jsUrlRegex =
            Regex("""e\[\d+\]\s*=\s*['"](https?://[^'"]+)['"]""")

        jsUrlRegex.findAll(response).forEach {
            val link = it.groupValues[1].replace("\\/", "/")
            loadExtractor(link, data, subtitleCallback, callback)
        }

        // Iframes estándar (Streamwish, Vidhide, Luluvdo, Filemoon, etc)
        val iframeRegex =
            Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""",
                RegexOption.IGNORE_CASE)

        iframeRegex.findAll(response).forEach {
            val iframeUrl = it.groupValues[1]
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
