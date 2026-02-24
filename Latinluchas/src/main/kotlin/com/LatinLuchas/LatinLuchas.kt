package com.latinluchas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.VidStack

// --- Extractor específico para Canal Upns ---
class LatinLuchaUpns : VidStack() {
    override var name = "LatinLucha Upns"
    override var mainUrl = "https://latinlucha.upns.online"
}

class LatinLuchas : MainAPI() {

    override var mainUrl = "https://latinluchas.com"
    override var name = "LatinLuchas"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val defaultPoster =
        "https://tv.latinluchas.com/tv/wp-content/uploads/2026/02/hq720.avif"

    // ==============================
    // 🔴 HOMEPAGE
    // ==============================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val homeSections = mutableListOf<HomePageList>()

        // ==============================
        // 🔴 SECCIÓN EN VIVO HOY
        // ==============================
        try {
            val liveDoc = app.get("$mainUrl/en-vivo/").document

            val title =
                liveDoc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" - LATINLUCHAS")
                    ?: "Evento en Vivo"

            val poster =
                liveDoc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: defaultPoster

            val liveItem = newAnimeSearchResponse(
                title,
                "$mainUrl/en-vivo/",
                TvType.TvSeries
            ) {
                this.posterUrl = poster
            }

            homeSections.add(
                HomePageList("🔴 EN VIVO HOY", listOf(liveItem))
            )
        } catch (_: Exception) {
        }

        // ==============================
        // 📂 CATEGORÍAS NORMALES (REPETICIONES)
        // ==============================
        val categories = listOf(
            Pair("WWE", "$mainUrl/category/eventos/wwe/"),
            Pair("UFC", "$mainUrl/category/eventos/ufc/"),
            Pair("AEW", "$mainUrl/category/eventos/aew/"),
            Pair("Lucha Libre Mexicana", "$mainUrl/category/eventos/lucha-libre-mexicana/"),
            Pair("Indies", "$mainUrl/category/eventos/indies/")
        )

        categories.forEach { (catName, url) ->
            try {
                val doc = app.get(url).document
                val items = doc.select("article, .post, .elementor-post")
                    .mapNotNull { element ->
                        val title =
                            element.selectFirst("h2, h3, .entry-title")
                                ?.text()?.trim()
                                ?: return@mapNotNull null

                        val href =
                            element.selectFirst("a")?.attr("abs:href")
                                ?: return@mapNotNull null

                        val poster =
                            element.selectFirst("img")?.attr("abs:src")
                                ?: element.selectFirst("img")?.attr("abs:data-src")
                                ?: defaultPoster

                        newAnimeSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    }

                homeSections.add(HomePageList(catName, items))
            } catch (_: Exception) {
            }
        }

        return newHomePageResponse(homeSections)
    }

    // ==============================
    // 📺 LOAD (REPETICIONES + EN VIVO)
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title =
            document.selectFirst("h1.entry-title")
                ?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.substringBefore(" - LATINLUCHAS")
                ?: "Evento"

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?: defaultPoster

        val plot =
            document.selectFirst("meta[property=og:description]")
                ?.attr("content")

        // ==============================
        // 🎥 SOLO BOTONES REALES DE VIDEO
        // ==============================
        val episodes = document
            .select("a.btn-video")
            .mapNotNull { anchor ->
                val name = anchor.text().trim()
                val link = anchor.attr("abs:href")

                if (link.isBlank() || name.contains("DESCARGA", true))
                    null
                else newEpisode(link) {
                    this.name = name
                }
            }
            .distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ==============================
    // 🔗 LOAD LINKS
    // ==============================
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

                else -> loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}