package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Tlnovelas : MainAPI() {
    override var mainUrl = "https://ww2.tlnovelas.net"
    override var name = "Tlnovelas"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/gratis/telenovelas/" to "Telenovelas"
    )

    // ======================= LISTADO =======================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
                this.posterUrl = poster
            }
        }
        return HomePageResponse(listOf(HomePageList(request.name, items)))
    }

    // ======================= SERIE =======================

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?.replace("Ver", "")
            ?.replace("Capítulos", "")
            ?.trim() ?: "Telenovela"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".ani-img img")?.attr("src")

        val description = doc.selectFirst(".card-text")?.text()

        val episodes = doc.select("a[href*='/ver/']").mapNotNull {
            val epUrl = it.attr("href")
            val epTitle = it.text()

            val number = Regex("""Cap[ií]tulo\s*(\d+)""")
                .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            Episode(
                epUrl,
                name = epTitle,
                episode = number
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
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

        // ---------- 1️⃣ Arrays JS: e[0]='https://host/e/xxx'
        Regex("""e\[\d+]\s*=\s*['"](https?://[^'"]+)['"]""")
            .findAll(html)
            .forEach {
                loadExtractor(it.groupValues[1], data, subtitleCallback, callback)
            }

        // ---------- 2️⃣ Blogger IDs: e[0]='ID|1'
        Regex("""e\[\d+]\s*=\s*['"]([a-zA-Z0-9_-]+)\|\d+['"]""")
            .findAll(html)
            .forEach {
                val token = it.groupValues[1]
                val bloggerUrl = "https://www.blogger.com/video.g?token=$token"

                callback(
                    ExtractorLink(
                        source = "Blogger",
                        name = "Blogger",
                        url = bloggerUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            }

        // ---------- 3️⃣ Iframes normales
        Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""")
            .findAll(html)
            .forEach {
                loadExtractor(it.groupValues[1], data, subtitleCallback, callback)
            }

        return true
    }
}
