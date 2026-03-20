package com.sololatino

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SoloLatino : MainAPI() {

    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override val hasMainPage = true
    override var lang = "es"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private var lastServer: String? = null

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(mainUrl).document

        val lists = doc.select("section").mapNotNull { section ->

            val title = section.selectFirst("h2")?.text() ?: return@mapNotNull null

            if (
                title.contains("Últimos", true) ||
                title.contains("Recientes", true) ||
                title.contains("Añadidos", true)
            ) return@mapNotNull null

            val items = section.select(".card").mapNotNull { card ->

                val a = card.selectFirst("a") ?: return@mapNotNull null
                val link = fixUrl(a.attr("href"))

                val name = card.selectFirst(".card__title")?.text()
                    ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")

                val type = if (link.contains("/serie/"))
                    TvType.TvSeries else TvType.Movie

                newMovieSearchResponse(name, link, type) {
                    this.posterUrl = poster
                }
            }

            if (items.isEmpty()) null else HomePageList(title, items)
        }

        return newHomePageResponse(lists)
    }

    // =========================
    // LOAD
    // =========================
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.substringBefore("|")
            ?.replace(Regex("""^Ver\s+""", RegexOption.IGNORE_CASE), "")
            ?.replace("Latino", "", true)
            ?.replace(Regex("""\(\d{4}\)"""), "")
            ?.replace("Online", "", true)
            ?.trim() ?: "Sin título"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst("meta[name=description]")
            ?.attr("content") ?: ""

        val isSeries = url.contains("/serie/")

        return if (!isSeries) {

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }

        } else {

            val episodes = doc.select("a.ep-item").map { ep ->

                val epUrl = fixUrl(ep.attr("href"))

                val num = ep.selectFirst(".ep-num")
                    ?.text()?.replace("E", "")?.toIntOrNull()

                val name = ep.selectFirst("p.text-sm")?.text()

                val thumb = ep.selectFirst("img")?.attr("src")

                newEpisode(epUrl) {
                    this.name = name
                    this.episode = num
                    this.posterUrl = thumb ?: poster
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // =========================
    // LINKS (FIX TOTAL)
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        val servers = mutableListOf<String>()

        // 🔥 NORMAL
        servers += doc.select("[data-server-btn]")
            .mapNotNull { it.attr("data-server-url") }

        // 🔥 ANIME onclick
        servers += doc.select("li[onclick]")
            .mapNotNull {
                Regex("""go_to_player\('([^']+)""")
                    .find(it.attr("onclick"))
                    ?.groupValues?.getOrNull(1)
            }

        if (servers.isEmpty()) return false

        val sorted = servers.sortedBy {
            if (lastServer != null && it.contains(lastServer!!)) 0 else 1
        }

        var isFirst = true

        sorted.forEach { url ->

            val fixedUrl = when {

                // 🔥 BASE64 decode
                url.contains("re.sololatino.net") -> {
                    Regex("""link=([^&]+)""")
                        .find(url)
                        ?.groupValues?.getOrNull(1)
                        ?.let {
                            try {
                                String(Base64.decode(it, Base64.DEFAULT))
                            } catch (_: Exception) { null }
                        } ?: url
                }

                else -> url
            }

            val serverName = fixedUrl.substringAfter("//").substringBefore("/")

            val cb: (ExtractorLink) -> Unit = { link ->

                lastServer = serverName

                if (isFirst) {
                    isFirst = false
                    callback.invoke(link)
                }

                callback.invoke(link)
            }

            when {

                // EMBED69
                fixedUrl.contains("embed69") -> {
                    Embed69Extractor.load(fixedUrl, data, subtitleCallback, cb)
                }

                // MP4 DIRECTO
                fixedUrl.endsWith(".mp4") -> {
                    callback.invoke(
                        newExtractorLink(
                            "Direct",
                            "MP4 Directo",
                            fixedUrl
                        ) {
                            this.type = ExtractorLinkType.VIDEO
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

                // SHORT LINKS
                fixedUrl.contains("short") -> {
                    try {
                        val real = app.get(fixedUrl).url
                        loadExtractor(real, data, subtitleCallback, cb)
                    } catch (_: Exception) {}
                }

                // DEFAULT
                else -> {
                    loadExtractor(fixedUrl, data, subtitleCallback, cb)
                }
            }
        }

        return true
    }
}
