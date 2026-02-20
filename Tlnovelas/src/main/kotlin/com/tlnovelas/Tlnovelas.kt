package com.tlnovelas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TLNovelasProvider : MainAPI() {

    override var mainUrl = "https://tlnovelas.org"
    override var name = "TLNovelas"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.TvSeries)

    // 🔥 Categorías homepage
    override val mainPage = mainPageOf(
        "$mainUrl/category/telenovelas/" to "Telenovelas",
        "$mainUrl/category/capitulos/" to "Capítulos recientes"
    )

    // 🔎 BUSCADOR NORMAL
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    // 🔎 SUGERENCIAS ARREGLADAS
    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(title, href) {
            this.posterUrl = poster
        }
    }

    // 🏠 HOMEPAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/$page").document

        val home = document.select("article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    // 📺 CARGAR SERIE + EPISODIOS
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text() ?: return null
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst("p")?.text()

        val episodes = document.select("a[href*=\"/capitulo/\"]")
            .mapIndexed { index, element ->
                val epUrl = element.attr("href")
                val epTitle = element.text()

                newEpisode(epUrl) {
                    name = epTitle
                    episode = index + 1
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
        }
    }

    // 🎥 EXTRAER LINKS (VERSIÓN ESTABLE)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        var success = false

        // 1️⃣ Detectar iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                if (loadExtractor(fixUrl(src), data, subtitleCallback, callback)) {
                    success = true
                }
            }
        }

        // 2️⃣ Detectar embeds ocultos en scripts
        val html = document.html()

        Regex("""https?://[^"' ]+""")
            .findAll(html)
            .map { it.value }
            .distinct()
            .forEach { url ->
                if (
                    url.contains("m3u8") ||
                    url.contains("mp4") ||
                    url.contains("dood") ||
                    url.contains("stream") ||
                    url.contains("filemoon")
                ) {
                    if (loadExtractor(url, data, subtitleCallback, callback)) {
                        success = true
                    }
                }
            }

        // 3️⃣ Detectar m3u8 directo
        Regex("""https?://[^"' ]+\.m3u8[^"' ]*""")
            .findAll(html)
            .forEach {
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        it.value,
                        data,
                        0,
                        true
                    )
                )
                success = true
            }

        return success
    }
}
