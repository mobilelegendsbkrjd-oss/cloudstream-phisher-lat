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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document

        val normal = mutableListOf<HomePageList>()
        val tokio = mutableListOf<HomePageList>()

        doc.select("section").forEach { section ->

            val title = section.selectFirst("h2")?.text() ?: return@forEach

            if (
                title.contains("Últimos", true) ||
                title.contains("Recientes", true) ||
                title.contains("Añadidos", true)
            ) return@forEach

            val items = section.select(".card").mapNotNull { card ->

                val a = card.selectFirst("a") ?: return@mapNotNull null
                val link = fixUrl(a.attr("href"))

                val name = card.selectFirst(".card__title")?.text()
                    ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank {
                        it.attr("data-lazy-src").ifBlank {
                            it.attr("src")
                        }
                    }
                }

                val type = if (link.contains("/serie/"))
                    TvType.TvSeries else TvType.Movie

                newMovieSearchResponse(name, link, type) {
                    this.posterUrl = poster
                }
            }

            if (items.isEmpty()) return@forEach

            val list = HomePageList(title, items)

            if (title.lowercase().contains("tokio")) {
                tokio.add(list)
            } else {
                normal.add(list)
            }
        }

        normal.addAll(tokio)

        return newHomePageResponse(normal)
    }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        val html = doc.html()

        val servers = mutableListOf<String>()

        // =========================
        // NUEVO PLAYER
        // =========================
        Regex("""data-server-url="([^"]+)"[^>]+data-server-type="([^"]+)"""")
            .findAll(html)
            .forEach {

                val url = it.groupValues[1]
                val type = it.groupValues[2]

                when (type) {

                    "iframe" -> {
                        val fixed = fixHostsLinks(fixUrl(url))
                        if (fixed.contains("embed69")) {
                            Embed69Extractor.load(fixed, data, subtitleCallback, callback)
                        } else {
                            loadExtractor(fixed, data, subtitleCallback, callback)
                        }
                    }

                    "mp4" -> {
                        callback.invoke(
                            newExtractorLink("Direct", "MP4", url) {
                                this.referer = data
                            }
                        )
                    }

                    else -> {
                        val fixed = fixHostsLinks(fixUrl(url))
                        loadExtractor(fixed, data, subtitleCallback, callback)
                    }
                }
            }

        // =========================
        // IFRAME PRINCIPAL
        // =========================
        val iframe = doc.selectFirst("#player-frame iframe")?.attr("src")

        if (!iframe.isNullOrEmpty()) {
            val fixed = fixHostsLinks(fixUrl(iframe))

            if (fixed.contains("embed69")) {
                Embed69Extractor.load(fixed, data, subtitleCallback, callback)
            } else {
                loadExtractor(fixed, data, subtitleCallback, callback)
            }
        }

        // =========================
        // SERVERS VIEJOS
        // =========================
        servers += doc.select("[data-server-btn]")
            .mapNotNull { it.attr("data-server-url") }

        servers += doc.select("li[onclick]")
            .mapNotNull {
                Regex("""go_to_player\('([^']+)""")
                    .find(it.attr("onclick"))
                    ?.groupValues?.getOrNull(1)
            }

        servers += doc.select("li[onclick]")
            .mapNotNull {
                Regex("""go_to_playerVast\('([^']+)""")
                    .find(it.attr("onclick"))
                    ?.groupValues?.getOrNull(1)
            }

        val clean = servers
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .distinct()

        clean.forEach { originalUrl ->

            val fixed = fixHostsLinks(originalUrl)

            if (fixed.contains("embed69")) {
                Embed69Extractor.load(fixed, originalUrl, subtitleCallback, callback)
                return@forEach
            }

            loadExtractor(fixed, originalUrl, subtitleCallback, callback)
        }

        return true
    }

    private fun fixHostsLinks(url: String): String {
        return url
            .replace("swdyu.com", "streamwish.to")
            .replace("cybervynx.com", "streamwish.to")
            .replace("dumbalag.com", "streamwish.to")
            .replace("wishembed.com", "streamwish.to")
            .replace("stwishe.com", "streamwish.to")

            .replace("mivalyo.com", "vidhidepro.com")
            .replace("dinisglows.com", "vidhidepro.com")
            .replace("dhtpre.com", "vidhidepro.com")
            .replace("vidhide.com", "vidhidepro.com")
            .replace("voidboost.net", "vidhidepro.com")

            .replace("filemoon.link", "filemoon.sx")
            .replace("filemoon.lat", "filemoon.sx")

            .replace("ok.ru/videoembed/", "ok.ru/video/")
            .replace("do7go.com", "dood.la")
            .replace("doodstream.com", "dood.la")

            .replace("sblona.com", "watchsb.com")
            .replace("sbfull.com", "watchsb.com")

            .replace("lulu.st", "lulustream.com")
            .replace("uqload.io", "uqload.com")
            .replace("voe.sx", "voe.unblockit.cat")
    }
}