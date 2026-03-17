package com.sololatino

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class SoloLatino : MainAPI() {

    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "mx"

    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    // =========================
    // SAFE GET
    // =========================
    private suspend fun safeGet(url: String, referer: String = mainUrl): String? {
        return try {
            app.get(
                url,
                timeout = 30,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Accept" to "*/*",
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "Connection" to "keep-alive"
                )
            ).text
        } catch (_: Exception) {
            null
        }
    }

    // =========================
    // IMAGE
    // =========================
    private fun getImage(el: Element?): String? {
        return el?.attr("src")
            ?: el?.attr("data-src")
            ?: el?.attr("data-lazy-src")
    }

    // =========================
    // MAIN PAGE
    // =========================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val sections = listOf(
            "Películas" to "$mainUrl/peliculas",
            "Series" to "$mainUrl/series",
            "Animes" to "$mainUrl/animes"
        )

        val lists = mutableListOf<HomePageList>()

        for ((name, url) in sections) {

            val html = safeGet(url) ?: continue
            val doc = Jsoup.parse(html)

            val cards = doc.select("div.card, article.item")

            val items = cards.mapNotNull { card ->

                val a = card.selectFirst("a") ?: return@mapNotNull null
                val link = a.attr("href").let {
                    if (it.startsWith("/")) "$mainUrl$it" else it
                }

                val title = card.text()
                val poster = getImage(card.selectFirst("img"))

                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    posterUrl = poster
                }
            }

            if (items.isNotEmpty())
                lists.add(HomePageList(name, items))
        }

        return newHomePageResponse(lists)
    }

    // =========================
    // SEARCH
    // =========================
    override suspend fun search(query: String): List<SearchResponse> {

        return try {

            val doc = app.get("$mainUrl/buscar?q=${query.replace(" ", "+")}").document

            doc.select("div.card, article.item").mapNotNull {

                val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val title = it.text()
                val poster = getImage(it.selectFirst("img"))

                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    posterUrl = poster
                }
            }

        } catch (_: Exception) {
            emptyList()
        }
    }

    // =========================
    // LOAD
    // =========================
    override suspend fun load(url: String): LoadResponse? {

        val html = safeGet(url, mainUrl) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = Regex("""https://image\.tmdb\.org/t/p/[^"]+""")
            .find(html)
            ?.value

        val description = doc.selectFirst("meta[name=description]")
            ?.attr("content")
            ?: ""

        val isMovie = url.contains("/pelicula/")

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url, // 🔥 IMPORTANTE: NO embed69
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // 🔥 SERIES: usamos misma URL como episodio único
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                listOf(
                    newEpisode(url) {
                        this.name = "Ver"
                    }
                )
            ) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // =========================
    // LOAD LINKS (REAL FIX)
    // =========================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = safeGet(data, mainUrl) ?: return false

        // 🔥 1. buscar embed69 directo (ESTO ES LA CLAVE)
        val embed69 = Regex("""https://embed69\.org/f/tt\d+""")
            .find(html)
            ?.value

        if (embed69 != null) {

            Embed69Extractor.load(
                embed69,
                data,
                subtitleCallback,
                callback
            )

            return true
        }

        // 🔥 2. fallback iframe (por si cambia el sitio)
        val doc = Jsoup.parse(html)

        val iframe = doc.selectFirst("#iframePlayer")?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: return false

        val fixed = if (iframe.startsWith("//")) {
            "https:$iframe"
        } else if (iframe.startsWith("/")) {
            "$mainUrl$iframe"
        } else {
            iframe
        }

        loadExtractor(
            fixed,
            data,
            subtitleCallback,
            callback
        )

        return true
    }

    // =========================
    // 🔥 MINOCHINOS REAL
    // =========================
    private suspend fun extractMinochinos(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0"))
            val html = res.text

            Regex("""https?:\/\/[^\s"']+master\.txt""")
                .find(html)
                ?.value?.let { master ->

                    callback(
                        newExtractorLink(
                            "Minochinos",
                            "Minochinos",
                            master
                        ) {
                            this.type = ExtractorLinkType.M3U8
                            this.referer = "https://minochinos.com/"
                            this.quality = 720
                        }
                    )
                }

        } catch (_: Exception) {
        }
    }

    // =========================
    // FIX HOSTS
    // =========================
    private fun fixHostsLinks(url: String): String {
        return url
            .replace("hglink.to", "streamwish.to")
            .replace("filemoon.link", "filemoon.sx")
            .replace("do7go.com", "dood.la")
    }
}