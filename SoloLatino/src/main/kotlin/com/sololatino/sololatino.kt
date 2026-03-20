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
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val doc = app.get(mainUrl).document

        val lists = mutableListOf<HomePageList>()
        val tokyo = mutableListOf<HomePageList>()

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
                tokyo.add(list)
            } else {
                lists.add(list)
            }
        }

        lists.addAll(tokyo)

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

        // normales
        servers += doc.select("[data-server-btn]")
            .mapNotNull { it.attr("data-server-url") }

        // anime onclick
        servers += doc.select("li[onclick]")
            .mapNotNull {
                Regex("""go_to_player\('([^']+)""")
                    .find(it.attr("onclick"))
                    ?.groupValues?.getOrNull(1)
            }

        if (servers.isEmpty()) return false

        val sorted = servers.distinct()

        sorted.forEach { originalUrl ->

            val fixedUrl = fixHostsLinks(originalUrl.trim())

            val cb: (ExtractorLink) -> Unit = { link ->
                lastServer = fixedUrl
                callback.invoke(link)
            }

            // =========================
            // 🔥 TOKYO MX / BASE64 FIX
            // =========================
            if (originalUrl.contains("re.sololatino.net")) {

                try {
                    val decoded = Regex("""link=([^&]+)""")
                        .find(originalUrl)
                        ?.groupValues?.getOrNull(1)
                        ?.let {
                            String(Base64.decode(it, Base64.DEFAULT))
                        }

                    if (!decoded.isNullOrEmpty()) {

                        val finalUrl = fixHostsLinks(decoded)

                        loadExtractor(finalUrl, originalUrl, subtitleCallback, callback)

                        if (finalUrl.endsWith(".mp4") || finalUrl.contains(".m3u8")) {
                            callback.invoke(
                                newExtractorLink(
                                    "TokyoMX",
                                    "Direct",
                                    finalUrl
                                ) {
                                    this.type = if (finalUrl.contains("m3u8"))
                                        ExtractorLinkType.M3U8
                                    else ExtractorLinkType.VIDEO

                                    this.referer = originalUrl
                                }
                            )
                        }

                        return@forEach
                    }

                } catch (_: Exception) {}
            }

            // =========================
            // 🔥 OK.RU + MP4UPLOAD FIX
            // =========================
            else if (fixedUrl.contains("ok.ru") || fixedUrl.contains("mp4upload")) {

                try {
                    var extracted = false

                    loadExtractor(fixedUrl, data, subtitleCallback) {
                        extracted = true
                        callback.invoke(it)
                    }

                    if (!extracted) {

                        val html = app.get(fixedUrl).text

                        Regex("""https?://[^"'\s<>()]+?\.(mp4|m3u8)""")
                            .findAll(html)
                            .forEach {

                                val link = it.value

                                callback.invoke(
                                    newExtractorLink(
                                        "Direct Fix",
                                        if (link.contains("m3u8")) "HLS" else "MP4",
                                        link
                                    ) {
                                        this.type = if (link.contains("m3u8"))
                                            ExtractorLinkType.M3U8
                                        else ExtractorLinkType.VIDEO

                                        this.referer = "https://ok.ru/"
                                    }
                                )
                            }

                        Regex("""og:video["'][^>]+content=["']([^"']+)""")
                            .find(html)
                            ?.groupValues?.getOrNull(1)
                            ?.let {
                                callback.invoke(
                                    newExtractorLink("OK.ru OG", "MP4", it) {
                                        this.type = ExtractorLinkType.VIDEO
                                        this.referer = "https://ok.ru/"
                                    }
                                )
                            }
                    }

                } catch (_: Exception) {}
            }

            // =========================
            // RESTO
            // =========================
            else when {

                fixedUrl.contains("embed69") -> {
                    Embed69Extractor.load(fixedUrl, data, subtitleCallback, cb)
                }

                fixedUrl.endsWith(".mp4") -> {
                    callback.invoke(
                        newExtractorLink("Direct", "MP4", fixedUrl) {
                            this.type = ExtractorLinkType.VIDEO
                        }
                    )
                }

                else -> {
                    loadExtractor(fixedUrl, data, subtitleCallback, cb)
                }
            }
        }

        return true
    }

    // =========================
    // MIRRORS
    // =========================
    private fun fixHostsLinks(url: String): String {
        return url
            .replace("hglink.to", "streamwish.to")
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
