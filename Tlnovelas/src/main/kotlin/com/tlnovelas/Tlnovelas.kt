package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Tlnovelas : MainAPI() {

    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/gratis/telenovelas/" to "Telenovelas"
    )

    // ======================= HOME =======================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(request.data).document

        val items = doc.select(".ani-card").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst(".ani-txt")?.text()?.trim() ?: return@mapNotNull null
            val poster = it.selectFirst("img")?.attr("src")

            newTvSeriesSearchResponse(
                title,
                a.attr("href"),
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = false
        )
    }

    // ======================= SERIE =======================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?.replace("Ver", "", true)
            ?.replace("Capítulos", "", true)
            ?.trim()
            ?: "Telenovela"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".ani-img img")?.attr("src")

        val description = doc.selectFirst(".card-text")?.text()

        val episodes = doc.select("a[href*='/ver/']").mapNotNull {
            val epUrl = it.attr("href")
            val text = it.text()

            val number = Regex("""Cap[ií]tulo\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epUrl) {
                name = text
                episode = number
            }
        }.distinctBy { it.data }.reversed()

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

    // ======================= VIDEOS =======================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = app.get(data).text

        // 1️⃣ Links directos en arrays JS
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

        // 2️⃣ Blogger IDs: e[0]='oEiMaglJZklh|1'
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
                        referer = data,
                        quality = Qualities.Unknown.value
                    )
                )
            }

        // 3️⃣ Iframes estándar
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
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
