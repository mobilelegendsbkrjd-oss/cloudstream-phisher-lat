package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack

// =========================
// Extractor para Upns
// =========================
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Upns"
    override var mainUrl = "https://latinlucha.upns.online"
}

// =========================
// MAIN API
// =========================
class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // =========================
    // HOMEPAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val categories = listOf(
            Pair("EN VIVO HOY", "$mainUrl/en-vivo/"),
            Pair("WWE", "$mainUrl/category/eventos/wwe/"),
            Pair("UFC", "$mainUrl/category/eventos/ufc/"),
            Pair("AEW", "$mainUrl/category/eventos/aew/"),
            Pair("Lucha Libre Mexicana", "$mainUrl/category/eventos/lucha-libre-mexicana/"),
            Pair("Indies", "$mainUrl/category/eventos/indies/")
        )

        val homePages = categories.map { (sectionName, url) ->

            if (url.contains("/en-vivo/")) {
                val liveItem = listOf(
                    newAnimeSearchResponse(
                        "Eventos en Vivo Hoy",
                        url,
                        TvType.TvSeries
                    ) {
                        this.posterUrl = defaultPoster
                    }
                )
                HomePageList(sectionName, liveItem)
            } else {

                val doc = app.get(url).document

                val items = doc.select("article, .post, .elementor-post")
                    .mapNotNull { element ->
                        val title = element.selectFirst("h2, h3, .entry-title")
                            ?.text()?.trim() ?: return@mapNotNull null

                        val href = element.selectFirst("a")
                            ?.attr("abs:href") ?: return@mapNotNull null

                        val poster = element.selectFirst("img")
                            ?.attr("abs:src")
                            ?.takeIf { it.isNotBlank() }
                            ?: element.selectFirst("img")
                                ?.attr("abs:data-src")
                            ?: defaultPoster

                        newAnimeSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    }

                HomePageList(sectionName, items)
            }
        }

        return newHomePageResponse(homePages)
    }

    // =========================
    // LOAD
    // =========================
    override suspend fun load(url: String): LoadResponse? {

        // ===== SECCIÓN EN VIVO =====
        if (url.contains("/en-vivo/")) {
            return loadLiveSection(url)
        }

        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()?.trim() ?: "Evento"

        val poster = document.selectFirst("meta[property='og:image']")
            ?.attr("content") ?: defaultPoster

        // SOLO BOTONES REALES DE VIDEO
        val episodes = document
            .select("a.btn-video")
            .mapNotNull { anchor ->
                val name = anchor.text().trim()
                val link = anchor.attr("abs:href")

                if (link.isBlank() ||
                    link.contains("descargar", true)
                ) return@mapNotNull null

                newEpisode(link) {
                    this.name = name
                }
            }
            .distinctBy { it.data }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = poster
            this.plot = document
                .selectFirst("meta[property='og:description']")
                ?.attr("content")
        }
    }

    // =========================
    // CARGA EN VIVO DINÁMICA
    // =========================
    private suspend fun loadLiveSection(url: String): LoadResponse? {

        val document = app.get(url).document
        val html = document.html()

        val regex = Regex(
            """<a href="(https://latinluchas\.com/[^"]+)".*?>(.*?)</a>""",
            RegexOption.IGNORE_CASE
        )

        val episodes = regex.findAll(html)
            .mapNotNull { match ->
                val link = match.groupValues.getOrNull(1) ?: return@mapNotNull null
                val name = match.groupValues.getOrNull(2)?.trim()
                    ?: return@mapNotNull null

                if (!link.contains("canal", true))
                    return@mapNotNull null

                newEpisode(link) {
                    this.name = name
                }
            }
            .distinctBy { it.data }
            .toList()

        return newTvSeriesLoadResponse(
            "Eventos en Vivo Hoy",
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = defaultPoster
            this.plot = "Eventos en vivo disponibles hoy."
        }
    }

    // =========================
    // LOAD LINKS
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->

            var src = iframe.attr("abs:src")
                .ifBlank { iframe.attr("abs:data-src") }
                .ifBlank { iframe.attr("src") }

            if (src.isBlank()) return@forEach

            if (src.startsWith("//"))
                src = "https:$src"

            when {
                src.contains("upns.online") ->
                    LatinLuchaUpns()
                        .getUrl(src, data, subtitleCallback, callback)

                else ->
                    loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}