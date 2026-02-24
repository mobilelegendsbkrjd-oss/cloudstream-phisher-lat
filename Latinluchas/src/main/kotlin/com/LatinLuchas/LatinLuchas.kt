package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack
import org.jsoup.nodes.Document

// ==========================
// Extractor Canal Upns
// ==========================
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Upns"
    override var mainUrl = "https://latinlucha.upns.online"
}

// ==========================
// Main API
// ==========================
class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // =====================================================
    // HOMEPAGE (categorías normales + EN VIVO dinámico)
    // =====================================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val categories = listOf(
            Pair("WWE", "$mainUrl/category/eventos/wwe/"),
            Pair("UFC", "$mainUrl/category/eventos/ufc/"),
            Pair("AEW", "$mainUrl/category/eventos/aew/"),
            Pair("Lucha Libre Mexicana", "$mainUrl/category/eventos/lucha-libre-mexicana/"),
            Pair("Indies", "$mainUrl/category/eventos/indies/")
        )

        val homeLists = mutableListOf<HomePageList>()

        // =========================
        // Categorías normales
        // =========================
        categories.forEach { (title, url) ->
            val doc = app.get(url).document

            val items: List<SearchResponse> =
                doc.select("article, .post, .elementor-post")
                    .mapNotNull { element ->
                        val name =
                            element.selectFirst("h2, h3, .entry-title")
                                ?.text()?.trim() ?: return@mapNotNull null

                        val href =
                            element.selectFirst("a")
                                ?.attr("abs:href") ?: return@mapNotNull null

                        val poster =
                            element.selectFirst("img")?.attr("abs:src")
                                ?: element.selectFirst("img")?.attr("abs:data-src")
                                ?: defaultPoster

                        newAnimeSearchResponse(name, href, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    }

            homeLists.add(HomePageList(title, items))
        }

        // =========================
        // EN VIVO dinámico
        // =========================
        try {
            val liveDoc = app.get("$mainUrl/en-vivo/").document
            val liveHtml = liveDoc.html()

            val regex =
                Regex("""href="(https://latinluchas\.com/[^"]*(canal|nxt|dynamite|tna|roh|ufc)[^"]*)"""",
                    RegexOption.IGNORE_CASE)

            val liveItems: List<SearchResponse> =
                regex.findAll(liveHtml)
                    .mapNotNull { match ->
                        val link = match.groupValues.getOrNull(1)
                            ?: return@mapNotNull null

                        val cleanTitle = link
                            .substringAfter("latinluchas.com/")
                            .replace("-", " ")
                            .replace("/", "")
                            .uppercase()

                        newAnimeSearchResponse(cleanTitle, link, TvType.TvSeries) {
                            this.posterUrl = defaultPoster
                        }
                    }
                    .distinctBy { it.url }
                    .toList()

            if (liveItems.isNotEmpty()) {
                homeLists.add(HomePageList("EN VIVO HOY", liveItems))
            }

        } catch (_: Exception) {
        }

        return newHomePageResponse(homeLists)
    }

    // =====================================================
    // LOAD (solo opciones del evento actual)
    // =====================================================
    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document

        val title =
            document.selectFirst("h1.entry-title")
                ?.text()?.trim()
                ?: document.title()

        val poster =
            document.selectFirst("meta[property='og:image']")
                ?.attr("content")
                ?: defaultPoster

        val description =
            document.selectFirst("meta[property='og:description']")
                ?.attr("content")

        // SOLO botones reales del evento (NO acordeones globales)
        val episodes =
            document.select("a.btn-video")
                .mapNotNull { anchor ->

                    val name = anchor.text().trim()
                    val link = anchor.attr("abs:href")

                    if (link.isBlank()) return@mapNotNull null

                    if (name.contains("DESCARG", true)) return@mapNotNull null

                    newEpisode(link) {
                        this.name = name
                    }
                }
                .distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // =====================================================
    // LOAD LINKS (NO rompemos extractores existentes)
    // =====================================================
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

            if (src.startsWith("//")) src = "https:$src"

            when {
                src.contains("upns.online") -> {
                    LatinLuchaUpns().getUrl(src, data, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}