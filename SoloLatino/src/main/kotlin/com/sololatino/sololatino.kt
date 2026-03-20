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
    // LINKS (FIX FINAL REAL)
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

        servers.distinct().forEach { originalUrl ->

            val fixedUrl = fixHostsLinks(originalUrl.trim())

            val cb: (ExtractorLink) -> Unit = { link ->
                lastServer = fixedUrl
                callback.invoke(link)
            }

            // =========================
            // 🔥 TOKYO MX BASE64 (FIX REAL)
            // =========================
            if (originalUrl.contains("re.sololatino.net") || originalUrl.contains("player?")) {

                try {
                    val decoded = Regex("""link=([^&]+)""")
                        .find(originalUrl)
                        ?.groupValues?.getOrNull(1)
                        ?.let { String(Base64.decode(it, Base64.DEFAULT)) }

                    if (!decoded.isNullOrEmpty()) {

                        val finalUrl = fixHostsLinks(decoded)

                        if (finalUrl.contains("embed69")) {
                            Embed69Extractor.load(finalUrl, originalUrl, subtitleCallback, cb)
                        } else {
                            loadExtractor(finalUrl, originalUrl, subtitleCallback, cb)
                        }

                        return@forEach
                    }

                } catch (_: Exception) {}

                // fallback
                loadExtractor(fixedUrl, originalUrl, subtitleCallback, cb)
                return@forEach
            }

            // =========================
            // RESTO (SIN ROMPER NADA)
            // =========================
            else when {

                fixedUrl.contains("embed69") -> {
                    Embed69Extractor.load(fixedUrl, originalUrl, subtitleCallback, cb)
                }

                else -> {
                    loadExtractor(fixedUrl, originalUrl, subtitleCallback, cb)
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
