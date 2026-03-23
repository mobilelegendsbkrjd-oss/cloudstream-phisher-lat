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

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document

        val lists = doc.select("section").mapNotNull { section ->
            val title = section.selectFirst("h2")?.text() ?: return@mapNotNull null

            val items = section.select(".card").mapNotNull { card ->
                val a = card.selectFirst("a") ?: return@mapNotNull null
                val link = fixUrl(a.attr("href"))

                val name = card.selectFirst(".card__title")?.text() ?: return@mapNotNull null
                val poster = card.selectFirst("img")?.attr("src")

                val type = if (link.contains("/serie/")) TvType.TvSeries else TvType.Movie

                newMovieSearchResponse(name, link, type) {
                    this.posterUrl = poster
                }
            }

            if (items.isEmpty()) null else HomePageList(title, items)
        }

        return newHomePageResponse(lists)
    }

    // =========================
    // SEARCH
    // =========================
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        for (page in 1..3) {
            val doc = app.get("$mainUrl/buscar?q=$query&page=$page").document

            val items = doc.select(".card").mapNotNull {
                val link = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val name = it.selectFirst(".card__title")?.text() ?: return@mapNotNull null
                val poster = it.selectFirst("img")?.attr("src")

                val type = if (link.contains("/serie/")) TvType.TvSeries else TvType.Movie

                newMovieSearchResponse(name, link, type) {
                    this.posterUrl = poster
                }
            }

            if (items.isEmpty()) break
            results.addAll(items)
        }

        return results.distinctBy { it.url }
    }

    // =========================
    // LOAD (🔥 TEMPORADAS FIX REAL)
    // =========================
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.substringBefore("|")
            ?.trim() ?: "Sin título"

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""

        val isSeries = url.contains("/serie/")

        return if (!isSeries) {

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }

        } else {

            val episodes = mutableListOf<Episode>()

            doc.select("a.ep-item").forEach { ep ->

                val epUrl = fixUrl(ep.attr("href"))

                val epNum = ep.selectFirst(".ep-num")
                    ?.text()
                    ?.replace("E", "")
                    ?.trim()
                    ?.toIntOrNull()

                val season = Regex("""temporada-(\d+)""")
                    .find(epUrl)
                    ?.groupValues?.getOrNull(1)
                    ?.toIntOrNull() ?: 1

                val epTitle = ep.selectFirst("p.text-sm")
                    ?.text()
                    ?.trim()

                val extra = ep.select("p.text-xs")
                    .map { it.text().trim() }

                val description = extra.firstOrNull {
                    !it.matches(Regex("""\d{2}/\d{2}/\d{4}"""))
                }

                val thumb = ep.selectFirst("img")?.attr("src")

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.description = description
                        this.episode = epNum
                        this.season = season
                        this.posterUrl = thumb ?: poster
                    }
                )
            }

            val sorted = episodes.sortedWith(
                compareBy({ it.season }, { it.episode })
            )

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sorted) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // =========================
    // LINKS (STREAMFLIX STYLE)
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        val servers = doc.select("button.server-btn")
            .mapNotNull { it.attr("data-server-url") }

        val extracted = mutableSetOf<String>()

        for (server in servers) {

            val links = getServersFromIframe(server, data)

            links.forEach {
                if (extracted.add(it)) {
                    loadExtractor(it, data, subtitleCallback, callback)
                }
            }
        }

        return extracted.isNotEmpty()
    }

    private suspend fun getServersFromIframe(
        iframeUrl: String,
        referer: String
    ): List<String> {

        val results = mutableListOf<String>()

        try {
            val res = app.get(iframeUrl, headers = mapOf("Referer" to referer))
            val html = res.text
            val doc = res.document

            Regex("""dataLink\s*=\s*(\[.*?\]);""")
                .find(html)?.groupValues?.getOrNull(1)?.let { json ->

                    val parsed = AppUtils.tryParseJson<List<Map<String, Any>>>(json)
                        ?: return@let

                    parsed.forEach { lang ->
                        val embeds = lang["sortedEmbeds"] as? List<Map<String, Any>>
                            ?: return@forEach

                        embeds.forEach {
                            val enc = it["link"] as? String ?: return@forEach
                            decodeBase64Link(enc)?.let { real ->
                                results.add(real)
                            }
                        }
                    }
                }

            doc.select("li[onclick]").forEach {
                Regex("""go_to_playerVast\(\s*'([^']+)'""")
                    .find(it.attr("onclick"))
                    ?.groupValues?.getOrNull(1)
                    ?.let { results.add(it) }
            }

            doc.selectFirst("iframe")?.attr("src")?.let {
                results.add(it)
            }

        } catch (_: Exception) {}

        return results
    }

    private fun decodeBase64Link(enc: String): String? {
        return try {
            val parts = enc.split(".")
            if (parts.size != 3) return null

            var payload = parts[1]
            val pad = payload.length % 4
            if (pad != 0) payload += "=".repeat(4 - pad)

            val json = String(Base64.decode(payload, Base64.DEFAULT))

            Regex("\"link\":\"(.*?)\"")
                .find(json)?.groupValues?.getOrNull(1)

        } catch (_: Exception) {
            null
        }
    }


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
